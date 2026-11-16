package net.zyonmc.zyonahouseaddon.listener;

import fr.maxlego08.zauctionhouse.api.AuctionPlugin;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import net.zyonmc.zyonahouseaddon.messaging.Envelope;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Reloads the whole auction house from the shared database. The plugin's own {@code loadItems()} reads
 * OFFLINE first and swaps atomically, so a database failure leaves the current state untouched.
 */
public final class ResyncHandler implements ClusterEventHandler {

    private final AuctionPlugin plugin;
    private final CacheAccess cache;
    private final Logger log;

    public ResyncHandler(AuctionPlugin plugin, CacheAccess cache, Logger log) {
        this.plugin = plugin;
        this.cache = cache;
        this.log = log;
    }

    @Override
    public CompletableFuture<Void> handle(Envelope envelope) {
        return resync();
    }

    public CompletableFuture<Void> resync() {
        return CompletableFuture.runAsync(() -> this.plugin.getStorageManager().loadItems(), this.plugin.getExecutorService())
                .thenRun(() -> {
                    for (StorageType storage : StorageType.values()) this.cache.invalidatePlayerLists(storage);
                    this.log.info("Resync complete: items reloaded from the database.");
                });
    }
}
