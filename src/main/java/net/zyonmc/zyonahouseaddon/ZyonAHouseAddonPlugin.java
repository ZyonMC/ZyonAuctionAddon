package net.zyonmc.zyonahouseaddon;

import fr.maxlego08.zauctionhouse.api.AuctionPlugin;
import net.zyonmc.zyonahouseaddon.admin.ZahRedisCommand;
import net.zyonmc.zyonahouseaddon.bridge.RedisAuctionClusterBridge;
import net.zyonmc.zyonahouseaddon.config.InvalidSettingsException;
import net.zyonmc.zyonahouseaddon.config.Messages;
import net.zyonmc.zyonahouseaddon.config.Settings;
import net.zyonmc.zyonahouseaddon.listener.CacheAccess;
import net.zyonmc.zyonahouseaddon.listener.ClusterEventDispatcher;
import net.zyonmc.zyonahouseaddon.listener.ListedHandler;
import net.zyonmc.zyonahouseaddon.listener.ReloadHandler;
import net.zyonmc.zyonahouseaddon.listener.RemovedHandler;
import net.zyonmc.zyonahouseaddon.listener.ResyncHandler;
import net.zyonmc.zyonahouseaddon.listener.StatusHandler;
import net.zyonmc.zyonahouseaddon.lock.Keys;
import net.zyonmc.zyonahouseaddon.lock.LockManager;
import net.zyonmc.zyonahouseaddon.messaging.Deduplicator;
import net.zyonmc.zyonahouseaddon.messaging.EventBus;
import net.zyonmc.zyonahouseaddon.messaging.EventCodec;
import net.zyonmc.zyonahouseaddon.messaging.EventType;
import net.zyonmc.zyonahouseaddon.maintenance.PeriodicResync;
import net.zyonmc.zyonahouseaddon.notify.SellerNotifier;
import net.zyonmc.zyonahouseaddon.redis.RedisConnection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Entry point. Wires the pieces and installs the Redis bridge into zAuctionHouse.
 * <p>
 * Every precondition failure ends in a CLEAN refusal (the addon disables itself, the auction house keeps
 * running in single-server mode): a half-installed cluster bridge is the one state that can duplicate items.
 */
public final class ZyonAHouseAddonPlugin extends JavaPlugin {

    private RedisConnection redis;
    private ExecutorService workers;
    private PeriodicResync periodicResync;
    private final AtomicReference<Messages> messages = new AtomicReference<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Settings settings;
        try {
            settings = Settings.load(getConfig());
        } catch (InvalidSettingsException exception) {
            refuse("Invalid configuration: " + exception.getMessage());
            return;
        }

        if (!(getServer().getPluginManager().getPlugin("zAuctionHouse") instanceof AuctionPlugin auction)) {
            refuse("zAuctionHouse is not loaded.");
            return;
        }
        if (!isMariaDb(auction)) {
            refuse("zAuctionHouse storage-type must be MARIADB with ONE database shared by every server.");
            return;
        }

        try {
            this.redis = RedisConnection.connect(settings.redis(), getLogger());
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Cannot reach Redis at " + settings.redis().describe(), exception);
            refuse("Redis unreachable: the server stays in single-server mode.");
            return;
        }

        install(settings, auction);
    }

    private void install(Settings settings, AuctionPlugin auction) {
        var threads = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "ZyonAHouseAddon-worker-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        var keys = new Keys(settings.redis().prefix());
        var locks = new LockManager(this.redis, keys, settings.lockLease(), settings.terminalStateTtl());
        var bus = new EventBus(this.redis, keys, settings.serverId(), new EventCodec(),
                new Deduplicator(settings.dedupeWindow()), this.workers, getLogger(), settings.debug());

        var cache = new CacheAccess(auction.getAuctionManager());
        var storage = auction.getStorageManager();
        this.messages.set(settings.messages());
        var notifier = new SellerNotifier(this, auction::getAuctionManager, this.messages::get);
        var resync = new ResyncHandler(auction, cache, getLogger());
        var reload = new ReloadHandler(auction, this, this::reloadMessages, getLogger());
        var removed = new RemovedHandler(storage, cache, notifier);
        var dispatcher = new ClusterEventDispatcher(this.workers, getLogger())
                .register(EventType.LISTED, new ListedHandler(storage, cache, locks, getLogger()))
                .register(EventType.BOUGHT, removed)
                .register(EventType.REMOVED, removed)
                .register(EventType.STATUS, new StatusHandler(cache, getLogger(), settings.debug()))
                .register(EventType.RESYNC, resync)
                .register(EventType.RELOAD, reload);

        try {
            bus.start(dispatcher::dispatch);
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Cannot subscribe to the Redis bus", exception);
            refuse("Redis pub/sub unavailable: the server stays in single-server mode.");
            return;
        }

        var bridge = new RedisAuctionClusterBridge(locks, bus, settings.serverId());
        auction.setAuctionClusterBridge(bridge);
        if (auction.getAuctionClusterBridge() != bridge) {
            refuse("zAuctionHouse refused the cluster bridge (see its log above).");
            return;
        }

        this.redis.onPubSubReconnect(() -> resync.resync().exceptionally(error -> {
            getLogger().log(Level.SEVERE, "Resync after Redis reconnection failed", error);
            return null;
        }));

        var command = new ZahRedisCommand(settings, this.messages::get, this.redis, bus, resync, reload, getLogger());
        var pluginCommand = getCommand("zahredis");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        // Anything published between zAuctionHouse loading its data and our subscription would be missed.
        resync.resync().exceptionally(error -> {
            getLogger().log(Level.SEVERE, "Initial resync failed: stale data is possible until /zahredis resync", error);
            return null;
        });

        if (!settings.periodicResync().isZero()) {
            this.periodicResync = new PeriodicResync(resync::resync, settings.periodicResync(), getLogger());
            this.periodicResync.start();
        }

        getLogger().info("Cluster bridge installed. server-id=" + settings.serverId() + " redis=" + settings.redis().describe());
    }

    @Override
    public void onDisable() {
        // The bridge stays installed on purpose: with Redis closed every lock call FAILS, so the auction
        // house refuses operations instead of silently falling back to a lock that protects nothing.
        closeQuietly();
    }

    /** Re-reads ONLY the addon's texts (redis/lock settings need a restart). Runs on the main thread. */
    private void reloadMessages() {
        reloadConfig();
        this.messages.set(Messages.load(getConfig().getConfigurationSection("messages")));
    }

    private void refuse(String reason) {
        getLogger().severe("ZyonAHouseAddon disabled: " + reason);
        closeQuietly();
        getServer().getPluginManager().disablePlugin(this);
    }

    private void closeQuietly() {
        if (this.periodicResync != null) {
            this.periodicResync.close();
            this.periodicResync = null;
        }
        if (this.redis != null) {
            try {
                this.redis.close();
            } catch (RuntimeException exception) {
                getLogger().log(Level.WARNING, "Error closing Redis", exception);
            }
            this.redis = null;
        }
        if (this.workers != null) {
            this.workers.shutdown();
            try {
                if (!this.workers.awaitTermination(3, TimeUnit.SECONDS)) this.workers.shutdownNow();
            } catch (InterruptedException interrupted) {
                this.workers.shutdownNow();
                Thread.currentThread().interrupt();
            }
            this.workers = null;
        }
    }

    /**
     * Reads {@code storage-type} from zAuctionHouse's own config (the value it builds its connection from).
     * Deliberately NOT {@code StorageManager#getDatabaseConnection()}: its signature mentions the database
     * library, which the plugin ships relocated, so calling it from here is a NoSuchMethodError at runtime.
     */
    private static boolean isMariaDb(AuctionPlugin auction) {
        org.bukkit.plugin.Plugin bukkitPlugin = auction;   // getConfig() is Bukkit's, not part of the auction API
        return "MARIADB".equalsIgnoreCase(bukkitPlugin.getConfig().getString("storage-type", "SQLITE").trim());
    }
}
