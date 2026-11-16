package net.zyonmc.zyonahouseaddon.listener;

import fr.maxlego08.zauctionhouse.api.item.Item;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import fr.maxlego08.zauctionhouse.api.storage.StorageManager;
import net.zyonmc.zyonahouseaddon.messaging.Envelope;
import net.zyonmc.zyonahouseaddon.messaging.EventType;
import net.zyonmc.zyonahouseaddon.notify.SellerNotices;

import java.util.concurrent.CompletableFuture;

/**
 * Handles both REMOVED and BOUGHT: an item left {@code source} and may have landed in {@code destination}
 * (BOUGHT is LISTED -> PURCHASED). The destination copy always comes from the database, never from the event.
 */
public final class RemovedHandler implements ClusterEventHandler {

    private final StorageManager storage;
    private final CacheAccess cache;
    private final SellerNotices notices;

    public RemovedHandler(StorageManager storage, CacheAccess cache, SellerNotices notices) {
        this.storage = storage;
        this.cache = cache;
        this.notices = notices;
    }

    @Override
    public CompletableFuture<Void> handle(Envelope envelope) {
        int id = envelope.itemId();
        StorageType source = envelope.source();
        StorageType destination = envelope.destination();

        Item before = source == null ? null : this.cache.find(source, id).orElse(null);
        if (source != null) {
            this.cache.remove(source, id);
            this.cache.invalidatePlayerLists(source);
        }

        notifySeller(envelope);

        boolean landsInStorage = destination != null && destination != StorageType.DELETED;
        if (!landsInStorage) {
            refresh(before, false, source);
            return CompletableFuture.completedFuture(null);
        }

        return this.storage.selectItemState(id).thenAccept(result -> {
            Item view = before;
            if (result.isFound()) {
                view = view == null ? result.item() : view;
                if (!this.cache.contains(destination, id)) this.cache.put(destination, result.item());
                this.cache.invalidatePlayerLists(destination);
            }
            refresh(view, destination == StorageType.LISTED, source);
            // After the DB read: the PENDING payment row is written before the item moves, so it exists now.
            if (envelope.type() == EventType.BOUGHT) this.notices.collectPayment(envelope);
        });
    }

    /** Sold: LISTED -> PURCHASED. Expired/withdrawn: LISTED -> EXPIRED. Nothing else is worth a message. */
    private void notifySeller(Envelope envelope) {
        if (envelope.type() == EventType.BOUGHT) {
            this.notices.itemSold(envelope);
        } else if (envelope.source() == StorageType.LISTED && envelope.destination() == StorageType.EXPIRED) {
            this.notices.itemExpired(envelope);
        }
    }

    /** Only menus showing the LISTED storage need a refresh. */
    private void refresh(Item item, boolean added, StorageType source) {
        if (item == null) return;
        if (source == StorageType.LISTED || added) this.cache.refreshViews(item, added);
    }
}
