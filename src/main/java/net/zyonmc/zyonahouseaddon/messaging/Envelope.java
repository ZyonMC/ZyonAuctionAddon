package net.zyonmc.zyonahouseaddon.messaging;

import fr.maxlego08.zauctionhouse.api.item.ItemStatus;
import fr.maxlego08.zauctionhouse.api.item.StorageType;

import java.util.UUID;

/**
 * Wire format of every message on the bus. Versioned: a peer never acts on a version it does not know.
 * <p>
 * An envelope only SIGNALS a change. The receiver reads the real state from the shared database, so a
 * stale, duplicated or reordered message can never overwrite the truth.
 *
 * @param id     unique event id (duplicate protection)
 * @param origin server-id of the publisher; every server ignores its own events
 */
public record Envelope(int v, String id, String origin, EventType type, int itemId,
                       StorageType source, StorageType destination,
                       ItemStatus oldStatus, ItemStatus newStatus,
                       UUID seller, String buyer, String display, String price, long ts) {

    public static final int VERSION = 1;

    public static Envelope listed(String origin, int itemId) {
        return new Envelope(VERSION, newId(), origin, EventType.LISTED, itemId, null, null, null, null, null, null, null, null, now());
    }

    public static Envelope bought(String origin, int itemId, UUID seller, String buyer, String display, String price) {
        return new Envelope(VERSION, newId(), origin, EventType.BOUGHT, itemId, StorageType.LISTED, StorageType.PURCHASED,
                null, null, seller, buyer, display, price, now());
    }

    public static Envelope status(String origin, int itemId, ItemStatus oldStatus, ItemStatus newStatus) {
        return new Envelope(VERSION, newId(), origin, EventType.STATUS, itemId, null, null, oldStatus, newStatus, null, null, null, null, now());
    }

    /** {@code destination} may be {@code null} when the publisher does not know it. */
    public static Envelope removed(String origin, int itemId, StorageType source, StorageType destination) {
        return removed(origin, itemId, source, destination, null, null);
    }

    /** Variant that tells the seller's server whom to notify and about what. */
    public static Envelope removed(String origin, int itemId, StorageType source, StorageType destination, UUID seller, String display) {
        return new Envelope(VERSION, newId(), origin, EventType.REMOVED, itemId, source, destination, null, null, seller, null, display, null, now());
    }

    public static Envelope reload(String origin) {
        return new Envelope(VERSION, newId(), origin, EventType.RELOAD, 0, null, null, null, null, null, null, null, null, now());
    }

    public static Envelope resync(String origin) {
        return new Envelope(VERSION, newId(), origin, EventType.RESYNC, 0, null, null, null, null, null, null, null, null, now());
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
