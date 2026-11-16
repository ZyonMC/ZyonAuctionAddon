package net.zyonmc.zyonahouseaddon.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.protocol.ConnectionWatchdog;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import net.zyonmc.zyonahouseaddon.config.Settings;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Lettuce wiring: one connection for commands and a SEPARATE one for pub/sub (a subscribed
 * connection cannot run commands).
 * <p>
 * Fail-closed by construction: while disconnected commands are REJECTED immediately
 * ({@link ClientOptions.DisconnectedBehavior#REJECT_COMMANDS}) instead of queuing, so a caller never waits
 * for a lock that will not come - it gets an error and aborts. Lettuce reconnects and resubscribes by itself.
 */
public final class RedisConnection implements AutoCloseable {

    private final Logger log;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> commands;
    private final StatefulRedisPubSubConnection<String, String> pubSub;
    private final List<Runnable> reconnectListeners = new CopyOnWriteArrayList<>();
    private volatile boolean pubSubUp = true;
    private volatile boolean closed;

    private RedisConnection(Logger log, RedisClient client, StatefulRedisConnection<String, String> commands,
                            StatefulRedisPubSubConnection<String, String> pubSub) {
        this.log = log;
        this.client = client;
        this.commands = commands;
        this.pubSub = pubSub;
    }

    /**
     * Opens both connections. Blocks until connected or timed out: call it from onEnable, never from a tick.
     *
     * @throws RedisConnectionException if Redis cannot be reached (the addon must then not install its bridge)
     */
    public static RedisConnection connect(Settings.Redis settings, Logger log) {
        var uri = RedisURI.builder()
                .withHost(settings.host())
                .withPort(settings.port())
                .withDatabase(settings.database())
                .withSsl(settings.ssl())
                .withTimeout(settings.timeout());
        if (!settings.password().isBlank()) {
            if (settings.user().isBlank()) uri.withPassword(settings.password().toCharArray());
            else uri.withAuthentication(settings.user(), settings.password().toCharArray());
        }

        RedisClient client = RedisClient.create(uri.build());
        client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .pingBeforeActivateConnection(true)
                .socketOptions(SocketOptions.builder().connectTimeout(settings.timeout()).keepAlive(true).build())
                .timeoutOptions(TimeoutOptions.enabled(settings.timeout()))
                .build());

        StatefulRedisConnection<String, String> commands = null;
        try {
            commands = client.connect();
            var pubSub = client.connectPubSub();
            var connection = new RedisConnection(log, client, commands, pubSub);
            connection.watchPubSubState();
            return connection;
        } catch (RuntimeException exception) {
            if (commands != null) commands.closeAsync();
            client.shutdownAsync();
            throw exception;
        }
    }

    private void watchPubSubState() {
        this.client.addListener(new io.lettuce.core.RedisConnectionStateListener() {
            @Override
            public void onRedisConnected(io.lettuce.core.RedisChannelHandler<?, ?> connection, SocketAddress socketAddress) {
                if (connection != RedisConnection.this.pubSub || closed) return;
                boolean wasDown = !pubSubUp;
                pubSubUp = true;
                if (wasDown) {
                    log.warning("Redis pub/sub connection restored: events may have been missed, resyncing.");
                    reconnectListeners.forEach(Runnable::run);
                }
            }

            @Override
            public void onRedisDisconnected(io.lettuce.core.RedisChannelHandler<?, ?> connection) {
                if (connection != RedisConnection.this.pubSub || closed) return;
                pubSubUp = false;
                log.severe("Redis pub/sub connection lost. Locked operations are refused until it is back.");
            }
        });
    }

    public RedisAsyncCommands<String, String> async() {
        return this.commands.async();
    }

    /** {@code true} while both connections are up. Informational: the real check is the command itself failing. */
    public boolean isHealthy() {
        return !this.closed && this.commands.isOpen() && this.pubSubUp;
    }

    public CompletableFuture<Long> publish(String channel, String message) {
        return this.commands.async().publish(channel, message).toCompletableFuture();
    }

    /** Subscribes (blocking, startup only). The listener runs on Lettuce's event loop: hand work off, never block. */
    public void subscribe(String channel, BiConsumer<String, String> listener) {
        this.pubSub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String receivedChannel, String message) {
                listener.accept(receivedChannel, message);
            }
        });
        this.pubSub.sync().subscribe(channel);
    }

    /** Called when the pub/sub connection comes back after an outage (events were possibly lost). */
    public void onPubSubReconnect(Runnable listener) {
        this.reconnectListeners.add(listener);
    }

    @Override
    public void close() {
        this.closed = true;
        this.reconnectListeners.clear();
        try {
            this.pubSub.close();
            this.commands.close();
        } finally {
            this.client.shutdown();
        }
    }
}
