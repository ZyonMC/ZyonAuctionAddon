package net.zyonmc.zyonahouseaddon.messaging;

/** What happened. Unknown names decode to {@code null} and are dropped (newer peers, older addon). */
public enum EventType {
    /** A listing was published. */
    LISTED,
    /** A listing was bought: it left LISTED for PURCHASED. */
    BOUGHT,
    /** A transient status changed (being purchased, awaiting confirmation...). */
    STATUS,
    /** An item left a storage, possibly landing in another one. */
    REMOVED,
    /** Reload everything from the database. */
    RESYNC,
    /** Run zAuctionHouse's own reload (configuration files) on every server. */
    RELOAD
}
