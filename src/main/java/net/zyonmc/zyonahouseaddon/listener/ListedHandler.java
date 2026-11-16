package net.zyonmc.zyonahouseaddon.listener;

import fr.maxlego08.zauctionhouse.api.item.StorageType;
import fr.maxlego08.zauctionhouse.api.storage.StorageManager;
import net.zyonmc.zyonahouseaddon.lock.LockManager;
import net.zyonmc.zyonahouseaddon.messaging.Envelope;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** Another server published a listing: load it from the database into this server's LISTED storage. */
public final class ListedHandler implements ClusterEventHandler {

    private final StorageManager storage;
    private final CacheAccess cache;
    private final LockManager locks;
    private final Logger log;

    public ListedHandler(StorageManager storage, CacheAccess cache, LockManager locks, Logger log) {
        this.storage = storage;
        this.cache = cache;
        this.locks = locks;
        this.log = log;
    }

    @Override
    public CompletableFuture<Void> handle(Envelope envelope) {
        int id = envelope.itemId();
        if (this.cache.contains(StorageType.LISTED, id)) return CompletableFuture.completedFuture(null);

        // A late LISTED must never resurrect an item that was already sold/removed.
        return this.locks.terminalState(id).thenCompose(terminal -> {
            if (terminal.isPresent()) return CompletableFuture.completedFuture(null);
            return this.storage.selectItemState(id).thenAccept(result -> {
                if (!result.isFound()) {
                    if (result.state() == StorageManager.LookupState.UNAVAILABLE) {
                        this.log.warning("Listing " + id + " exists but cannot be rebuilt on this server (check economies.yml).");
                    }
                    return;
                }
                if (this.cache.contains(StorageType.LISTED, id)) return;
                this.cache.put(StorageType.LISTED, result.item());
                this.cache.invalidatePlayerLists(StorageType.LISTED);
                this.cache.refreshViews(result.item(), true);
            });
        });
    }
}
