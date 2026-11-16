package net.zyonmc.zyonahouseaddon.lock;

import fr.maxlego08.zauctionhouse.api.item.StorageType;
import io.lettuce.core.ScriptOutputType;
import net.zyonmc.zyonahouseaddon.redis.RedisConnection;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Distributed per-item lock plus the terminal-state marks, on top of Redis. Knows nothing about Bukkit
 * or the auction plugin: it speaks item ids and opaque tokens, which keeps it unit-testable.
 * <p>
 * Every returned future FAILS only on transport errors (Redis down, timeout); contention and refusals
 * are normal values. That distinction is part of the bridge contract.
 */
public final class LockManager {

    public enum Outcome {ACQUIRED, BUSY, UNAVAILABLE}

    private final RedisConnection redis;
    private final Keys keys;
    private final Duration lease;
    private final Duration terminalTtl;

    public LockManager(RedisConnection redis, Keys keys, Duration lease, Duration terminalTtl) {
        this.redis = redis;
        this.keys = keys;
        this.lease = lease;
        this.terminalTtl = terminalTtl;
    }

    public Duration lease() {
        return this.lease;
    }

    /** Checks the terminal state for {@code scope} and takes the lock, atomically. */
    public CompletableFuture<Outcome> acquire(int itemId, String token, StorageType scope) {
        return LockScripts.ACQUIRE.<String>run(this.redis.async(), ScriptOutputType.VALUE,
                new String[]{this.keys.lock(itemId), this.keys.terminal(itemId)},
                token, Long.toString(this.lease.toMillis()), scopeName(scope)).thenApply(Outcome::valueOf);
    }

    /** Non-engaging availability for {@code scope} ({@code null} = display only). Ignores who holds the lock. */
    public CompletableFuture<Boolean> isAvailable(int itemId, StorageType scope) {
        return LockScripts.CHECK.<String>run(this.redis.async(), ScriptOutputType.VALUE,
                new String[]{this.keys.lock(itemId), this.keys.terminal(itemId)},
                scopeName(scope)).thenApply("AVAILABLE"::equals);
    }

    /** Releases only if {@code token} still owns the lock. {@code false} = it was lost or stolen. */
    public CompletableFuture<Boolean> release(int itemId, String token) {
        return LockScripts.RELEASE.<Long>run(this.redis.async(), ScriptOutputType.INTEGER,
                new String[]{this.keys.lock(itemId)}, token).thenApply(result -> result == 1L);
    }

    public CompletableFuture<Boolean> renew(int itemId, String token) {
        return LockScripts.RENEW.<Long>run(this.redis.async(), ScriptOutputType.INTEGER,
                new String[]{this.keys.lock(itemId)}, token, Long.toString(this.lease.toMillis())).thenApply(result -> result == 1L);
    }

    public CompletableFuture<Boolean> isHeldBy(int itemId, String token) {
        return LockScripts.HELD.<Long>run(this.redis.async(), ScriptOutputType.INTEGER,
                new String[]{this.keys.lock(itemId)}, token).thenApply(result -> result == 1L);
    }

    /** Records a committed terminal fact. DELETED is sticky. */
    public CompletableFuture<Void> mark(int itemId, TerminalState state) {
        return LockScripts.MARK.<Long>run(this.redis.async(), ScriptOutputType.INTEGER,
                new String[]{this.keys.terminal(itemId)}, state.name(), Long.toString(this.terminalTtl.toMillis())).thenApply(ignored -> null);
    }

    /** Forgets the mark: the item is on sale again (re-listed or restored). */
    public CompletableFuture<Void> clearMark(int itemId) {
        return this.redis.async().del(this.keys.terminal(itemId)).toCompletableFuture().thenApply(ignored -> null);
    }

    public CompletableFuture<Optional<TerminalState>> terminalState(int itemId) {
        return this.redis.async().get(this.keys.terminal(itemId)).toCompletableFuture()
                .thenApply(value -> value == null ? Optional.empty() : Optional.of(TerminalState.valueOf(value)));
    }

    private static String scopeName(StorageType scope) {
        return scope == null ? "" : scope.name();
    }
}
