package net.zyonmc.zyonahouseaddon.listener;

import fr.maxlego08.zauctionhouse.api.AuctionPlugin;
import net.zyonmc.zyonahouseaddon.messaging.Envelope;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Runs zAuctionHouse's own {@code reload()} so every server re-reads ITS files. The addon does not copy
 * configuration between servers (each has its own server name and credentials): keep the files identical
 * with a shared folder or rsync, and this makes the change take effect everywhere at once.
 */
public final class ReloadHandler implements ClusterEventHandler {

    private final AuctionPlugin plugin;
    private final Plugin owner;
    private final Runnable reloadAddonMessages;
    private final Logger log;

    public ReloadHandler(AuctionPlugin plugin, Plugin owner, Runnable reloadAddonMessages, Logger log) {
        this.plugin = plugin;
        this.owner = owner;
        this.reloadAddonMessages = reloadAddonMessages;
        this.log = log;
    }

    @Override
    public CompletableFuture<Void> handle(Envelope envelope) {
        return reload();
    }

    /** Reload runs on the server's main thread (region-safe on Folia); the future completes when it is done. */
    public CompletableFuture<Void> reload() {
        var done = new CompletableFuture<Void>();
        this.owner.getServer().getGlobalRegionScheduler().run(this.owner, task -> {
            try {
                this.reloadAddonMessages.run();
                this.plugin.reload();
                this.log.info("zAuctionHouse and addon messages reloaded.");
                done.complete(null);
            } catch (Throwable throwable) {
                done.completeExceptionally(throwable);
            }
        });
        return done;
    }
}
