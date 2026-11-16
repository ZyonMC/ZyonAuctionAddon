package net.zyonmc.zyonahouseaddon.bridge;

import fr.maxlego08.zauctionhouse.api.cluster.AuctionClusterBridge;
import fr.maxlego08.zauctionhouse.api.cluster.LockToken;
import fr.maxlego08.zauctionhouse.api.item.Item;
import fr.maxlego08.zauctionhouse.api.item.ItemStatus;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import net.zyonmc.zyonahouseaddon.lock.LockManager;
import net.zyonmc.zyonahouseaddon.lock.TerminalState;
import net.zyonmc.zyonahouseaddon.messaging.Envelope;
import net.zyonmc.zyonahouseaddon.messaging.EventBus;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * {@link AuctionClusterBridge} over Redis.
 * <p>
 * Contract followed to the letter (see the interface javadoc):
 * contention -> {@link LockToken#noop()}, terminal item -> {@link LockToken#unavailable()} (or noop from
 * {@code lockItem}), and a FAILED future ONLY for transport errors. While Redis is unreachable every locking
 * call fails: the plugin then refuses the operation instead of risking a duplication (fail-closed).
 * <p>
 * Ordering of every notification: record the terminal fact in Redis FIRST (so locks refuse immediately),
 * publish SECOND. The plugin calls these only after its database commit, so peers that re-read the
 * database always see the committed state.
 */
public final class RedisAuctionClusterBridge implements AuctionClusterBridge {

    private final LockManager locks;
    private final EventBus bus;
    private final String serverId;

    public RedisAuctionClusterBridge(LockManager locks, EventBus bus, String serverId) {
        this.locks = locks;
        this.bus = bus;
        this.serverId = serverId;
    }

    @Override
    public CompletableFuture<Boolean> checkAvailability(Item item) {
        return this.locks.isAvailable(item.getId(), null);
    }

    @Override
    public CompletableFuture<Boolean> checkAvailability(Item item, StorageType storageType) {
        return this.locks.isAvailable(item.getId(), storageType);
    }

    @Override
    public CompletableFuture<LockToken> lockItem(Item item, UUID buyerId, StorageType storageType) {
        var token = LockToken.issue(item, buyerId);
        return this.locks.acquire(item.getId(), token.value(), storageType)
                .thenApply(outcome -> outcome == LockManager.Outcome.ACQUIRED ? token : LockToken.noop());
    }

    @Override
    public CompletableFuture<LockToken> checkAndLock(Item item, UUID lockerId, StorageType storageType) {
        var token = LockToken.issue(item, lockerId);
        return this.locks.acquire(item.getId(), token.value(), storageType).thenApply(outcome -> switch (outcome) {
            case ACQUIRED -> token;
            case BUSY -> LockToken.noop();
            case UNAVAILABLE -> LockToken.unavailable();
        });
    }

    @Override
    public CompletableFuture<Void> unlockItem(Item item, LockToken lockToken, StorageType storageType) {
        return releaseLock(item, lockToken, storageType).thenApply(released -> null);
    }

    @Override
    public CompletableFuture<Boolean> releaseLock(Item item, LockToken lockToken, StorageType storageType) {
        if (lockToken == null || !lockToken.isAcquired()) return CompletableFuture.completedFuture(false);
        return this.locks.release(item.getId(), lockToken.value());
    }

    @Override
    public Duration lockLeaseDuration() {
        return this.locks.lease();
    }

    @Override
    public CompletableFuture<Boolean> renewLock(Item item, LockToken lockToken, StorageType storageType) {
        if (lockToken == null || !lockToken.isAcquired()) return CompletableFuture.completedFuture(false);
        return this.locks.renew(item.getId(), lockToken.value());
    }

    @Override
    public CompletableFuture<Boolean> isHeldBy(Item item, LockToken lockToken, StorageType storageType) {
        if (lockToken == null || !lockToken.isAcquired()) return CompletableFuture.completedFuture(false);
        return this.locks.isHeldBy(item.getId(), lockToken.value());
    }

    @Override
    public CompletableFuture<Void> notifyItemBought(Player player, Item item) {
        var envelope = Envelope.bought(this.serverId, item.getId(), item.getSellerUniqueId(), player.getName(),
                safe(item::getItemDisplay, "item"), safe(item::getFormattedPrice, "?"));
        return markThenPublish(this.locks.mark(item.getId(), TerminalState.SOLD), envelope);
    }

    @Override
    public CompletableFuture<Void> notifyItemListed(Item item) {
        // A fresh listing is on sale: drop any stale mark left by a previous life of this id.
        return markThenPublish(this.locks.clearMark(item.getId()), Envelope.listed(this.serverId, item.getId()));
    }

    @Override
    public CompletableFuture<Void> notifyItemStatusChange(Item item, ItemStatus oldStatus, ItemStatus newStatus) {
        var mark = TerminalState.fromStatus(newStatus)
                .map(state -> this.locks.mark(item.getId(), state))
                .orElseGet(() -> CompletableFuture.completedFuture(null));
        return markThenPublish(mark, Envelope.status(this.serverId, item.getId(), oldStatus, newStatus));
    }

    @Override
    public CompletableFuture<Void> removeItem(Item item, StorageType storageType) {
        return removeItem(item, storageType, null);
    }

    @Override
    public CompletableFuture<Void> removeItem(Item item, StorageType sourceStorageType, StorageType destinationStorageType) {
        var mark = TerminalState.fromDestination(destinationStorageType)
                .map(state -> this.locks.mark(item.getId(), state))
                .orElseGet(() -> destinationStorageType == StorageType.LISTED
                        ? this.locks.clearMark(item.getId())
                        : CompletableFuture.completedFuture(null));
        return markThenPublish(mark, Envelope.removed(this.serverId, item.getId(), sourceStorageType, destinationStorageType,
                item.getSellerUniqueId(), safe(item::getItemDisplay, "item")));
    }

    @Override
    public boolean isDistributed() {
        return true;
    }

    /**
     * Publishes even if the mark failed (peers must still learn about a committed change), then reports
     * the failure so the caller can log it. A failed mark is the more serious of the two errors.
     */
    private CompletableFuture<Void> markThenPublish(CompletableFuture<Void> mark, Envelope envelope) {
        return mark.handle((ok, markError) -> this.bus.publish(envelope).handle((published, publishError) -> {
            Throwable error = markError != null ? markError : publishError;
            if (error != null) throw new java.util.concurrent.CompletionException(error);
            return (Void) null;
        })).thenCompose(future -> future);
    }

    private static String safe(Supplier<String> supplier, String fallback) {
        try {
            String value = supplier.get();
            return value == null ? fallback : value;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
