package net.zyonmc.zyonahouseaddon.listener;

import fr.maxlego08.zauctionhouse.api.item.ItemStatus;
import net.zyonmc.zyonahouseaddon.messaging.Envelope;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Mirrors a transient status change onto the local instance. It is applied ONLY if the local status is
 * the one the publisher left ({@code oldStatus}) and is not terminal: an item that is already purchased,
 * removed or deleted is never brought back by a late or reordered message.
 */
public final class StatusHandler implements ClusterEventHandler {

    private final CacheAccess cache;
    private final Logger log;
    private final boolean debug;

    public StatusHandler(CacheAccess cache, Logger log, boolean debug) {
        this.cache = cache;
        this.log = log;
        this.debug = debug;
    }

    @Override
    public CompletableFuture<Void> handle(Envelope envelope) {
        if (envelope.oldStatus() == null || envelope.newStatus() == null) return CompletableFuture.completedFuture(null);

        this.cache.findAnywhere(envelope.itemId()).ifPresent(item -> {
            ItemStatus current = item.getStatus();
            if (isTerminal(current) || current != envelope.oldStatus()) {
                if (this.debug) {
                    this.log.info("[status] item " + item.getId() + " kept " + current + " (event " + envelope.oldStatus() + " -> " + envelope.newStatus() + ")");
                }
                return;
            }
            item.setStatus(envelope.newStatus());
        });
        return CompletableFuture.completedFuture(null);
    }

    static boolean isTerminal(ItemStatus status) {
        return status == ItemStatus.PURCHASED || status == ItemStatus.REMOVED || status == ItemStatus.DELETED;
    }
}
