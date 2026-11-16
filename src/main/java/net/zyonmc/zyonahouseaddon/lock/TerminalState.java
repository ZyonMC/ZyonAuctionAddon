package net.zyonmc.zyonahouseaddon.lock;

import fr.maxlego08.zauctionhouse.api.item.ItemStatus;
import fr.maxlego08.zauctionhouse.api.item.StorageType;

import java.util.Optional;

/**
 * Final facts about an item, recorded in Redis ONLY from committed transitions. They make the lock
 * refuse impossible actions quickly (buying an expired item); the shared database stays the arbiter.
 * <ul>
 *   <li>{@link #SOLD}: bought. Refused for a new purchase, still lockable to be CLAIMED by the buyer.</li>
 *   <li>{@link #REMOVED}: expired / withdrawn. Refused for a purchase, lockable to be claimed by the seller.</li>
 *   <li>{@link #DELETED}: claimed or destroyed. Refused for every scope.</li>
 * </ul>
 * The scope rules live in {@link LockScripts} (single source of truth, evaluated atomically in Redis).
 */
public enum TerminalState {
    SOLD,
    REMOVED,
    DELETED;

    /** State implied by an item landing in {@code destination}; empty when it is not terminal or unknown. */
    public static Optional<TerminalState> fromDestination(StorageType destination) {
        if (destination == null) return Optional.empty();
        return switch (destination) {
            case PURCHASED -> Optional.of(SOLD);
            case EXPIRED -> Optional.of(REMOVED);
            case DELETED -> Optional.of(DELETED);
            case LISTED -> Optional.empty();
        };
    }

    /**
     * State implied by a status change. {@code REMOVED} is deliberately NOT mapped: on its own it does not
     * say whether the item is claimable or destroyed, so only {@link #fromDestination} may record it.
     */
    public static Optional<TerminalState> fromStatus(ItemStatus status) {
        if (status == null) return Optional.empty();
        return switch (status) {
            case PURCHASED -> Optional.of(SOLD);
            case DELETED -> Optional.of(DELETED);
            default -> Optional.empty();
        };
    }
}
