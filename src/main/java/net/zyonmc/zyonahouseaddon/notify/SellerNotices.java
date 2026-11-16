package net.zyonmc.zyonahouseaddon.notify;

import net.zyonmc.zyonahouseaddon.messaging.Envelope;

/** Messages sent to a seller who may be connected to a different server than the one that acted. */
public interface SellerNotices {

    /** The seller's listing was bought. */
    void itemSold(Envelope envelope);

    /** The seller's listing left the market (expired or withdrawn) and is waiting to be claimed. */
    void itemExpired(Envelope envelope);

    /**
     * The seller's money was left PENDING because the buyer's server did not have them online. If they are
     * connected here, pay it out now instead of waiting for the next login or a manual claim.
     */
    default void collectPayment(Envelope envelope) {
    }
}
