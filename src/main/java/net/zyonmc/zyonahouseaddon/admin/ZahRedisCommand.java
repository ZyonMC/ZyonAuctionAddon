package net.zyonmc.zyonahouseaddon.admin;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.zyonmc.zyonahouseaddon.config.MessageKey;
import net.zyonmc.zyonahouseaddon.config.Messages;
import net.zyonmc.zyonahouseaddon.config.Settings;
import net.zyonmc.zyonahouseaddon.listener.ReloadHandler;
import net.zyonmc.zyonahouseaddon.listener.ResyncHandler;
import net.zyonmc.zyonahouseaddon.messaging.Envelope;
import net.zyonmc.zyonahouseaddon.messaging.EventBus;
import net.zyonmc.zyonahouseaddon.redis.RedisConnection;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** {@code /zahredis status|resync}. */
public final class ZahRedisCommand implements TabExecutor {

    public static final String PERMISSION = "zyonahouseaddon.admin";
    private static final List<String> SUBCOMMANDS = List.of("status", "resync", "reload");

    private final Settings settings;
    private final Supplier<Messages> messages;
    private final RedisConnection redis;
    private final EventBus bus;
    private final ResyncHandler resync;
    private final ReloadHandler reload;
    private final Logger log;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ZahRedisCommand(Settings settings, Supplier<Messages> messages, RedisConnection redis, EventBus bus, ResyncHandler resync, ReloadHandler reload, Logger log) {
        this.settings = settings;
        this.messages = messages;
        this.redis = redis;
        this.bus = bus;
        this.resync = resync;
        this.reload = reload;
        this.log = log;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            send(sender, MessageKey.ADMIN_ONLY);
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "status" -> status(sender);
            case "resync" -> resync(sender);
            case "reload" -> reload(sender);
            default -> send(sender, MessageKey.USAGE);
        }
        return true;
    }

    private void status(CommandSender sender) {
        Messages texts = this.messages.get();
        String health = texts.get(this.redis.isHealthy() ? MessageKey.STATUS_CONNECTED : MessageKey.STATUS_DOWN);
        var component = this.miniMessage.deserialize(texts.get(MessageKey.PREFIX) + texts.get(MessageKey.STATUS_HEADER),
                Placeholder.unparsed("server", this.settings.serverId()),
                Placeholder.parsed("redis", health + " <dark_gray>" + this.settings.redis().describe() + "</dark_gray>"),
                Placeholder.parsed("mode", texts.get(MessageKey.STATUS_MODE)));
        sender.sendMessage(component);
    }

    /** Reloads this server from the database and tells every other server to do the same. */
    private void resync(CommandSender sender) {
        send(sender, MessageKey.RESYNC_STARTED);
        this.resync.resync()
                .thenCompose(ignored -> this.bus.publish(Envelope.resync(this.settings.serverId())))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        this.log.log(Level.SEVERE, "Resync failed", error);
                        send(sender, MessageKey.RESYNC_FAILED);
                    } else {
                        send(sender, MessageKey.RESYNC_DONE);
                    }
                });
    }

    /** Runs zAuctionHouse's reload here and on every other server (their files must already be in place). */
    private void reload(CommandSender sender) {
        send(sender, MessageKey.RELOAD_STARTED);
        this.reload.reload()
                .thenCompose(ignored -> this.bus.publish(Envelope.reload(this.settings.serverId())))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        this.log.log(Level.SEVERE, "Reload failed", error);
                        send(sender, MessageKey.RELOAD_FAILED);
                    } else {
                        send(sender, MessageKey.RELOAD_DONE);
                    }
                });
    }

    private void send(CommandSender sender, MessageKey key) {
        Messages texts = this.messages.get();
        sender.sendMessage(this.miniMessage.deserialize(texts.get(MessageKey.PREFIX) + texts.get(key)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION) || args.length != 1) return List.of();
        return SUBCOMMANDS.stream().filter(name -> name.startsWith(args[0].toLowerCase())).toList();
    }
}
