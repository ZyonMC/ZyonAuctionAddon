package net.zyonmc.zyonahouseaddon.messaging;

import net.zyonmc.zyonahouseaddon.lock.Keys;
import net.zyonmc.zyonahouseaddon.redis.RedisConnection;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Publishes and receives {@link Envelope}s. Incoming messages are filtered (own origin, duplicates,
 * unknown version) and handed to {@code executor} so Lettuce's event loop is never blocked.
 */
public final class EventBus {

    private final RedisConnection redis;
    private final Keys keys;
    private final String serverId;
    private final EventCodec codec;
    private final Deduplicator deduplicator;
    private final Executor executor;
    private final Logger log;
    private final boolean debug;

    public EventBus(RedisConnection redis, Keys keys, String serverId, EventCodec codec, Deduplicator deduplicator,
                    Executor executor, Logger log, boolean debug) {
        this.redis = redis;
        this.keys = keys;
        this.serverId = serverId;
        this.codec = codec;
        this.deduplicator = deduplicator;
        this.executor = executor;
        this.log = log;
        this.debug = debug;
    }

    /** Starts listening (blocking, startup only). */
    public void start(Consumer<Envelope> handler) {
        this.redis.subscribe(this.keys.channel(), (channel, json) -> receive(json, handler));
    }

    /** Publishes after the database commit. A failure is reported to the caller, never swallowed. */
    public CompletableFuture<Void> publish(Envelope envelope) {
        return this.redis.publish(this.keys.channel(), this.codec.encode(envelope)).thenApply(receivers -> {
            if (this.debug) this.log.info("[bus] -> " + envelope.type() + " item=" + envelope.itemId() + " receivers=" + receivers);
            return null;
        });
    }

    private void receive(String json, Consumer<Envelope> handler) {
        var decoded = this.codec.decode(json);
        if (decoded.isEmpty()) {
            if (this.debug) this.log.info("[bus] dropped an undecodable or unsupported message");
            return;
        }
        Envelope envelope = decoded.get();
        if (envelope.origin().equals(this.serverId)) return;
        if (!this.deduplicator.firstSeen(envelope.id())) return;
        if (this.debug) this.log.info("[bus] <- " + envelope.type() + " item=" + envelope.itemId() + " from=" + envelope.origin());

        try {
            this.executor.execute(() -> {
                try {
                    handler.accept(envelope);
                } catch (Throwable throwable) {
                    this.log.log(Level.SEVERE, "Failed to handle " + envelope.type() + " for item " + envelope.itemId(), throwable);
                }
            });
        } catch (RuntimeException rejected) {
            this.log.log(Level.WARNING, "Event executor rejected " + envelope.type() + " (shutting down?)", rejected);
        }
    }
}
