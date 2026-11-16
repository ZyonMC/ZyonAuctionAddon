package net.zyonmc.zyonahouseaddon.listener;

import fr.maxlego08.zauctionhouse.api.AuctionManager;
import fr.maxlego08.zauctionhouse.api.cache.PlayerCacheKey;
import fr.maxlego08.zauctionhouse.api.item.Item;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import fr.maxlego08.zauctionhouse.api.utils.IntArrayList;

import java.util.Optional;

/**
 * The only place that touches the plugin's in-memory auction state. Handlers read intent, this class
 * performs the mutation, so the surface used from the base plugin stays small and obvious.
 */
public final class CacheAccess {

    private final AuctionManager manager;

    public CacheAccess(AuctionManager manager) {
        this.manager = manager;
    }

    public Optional<Item> find(StorageType storage, int itemId) {
        var ids = new IntArrayList(1);
        ids.add(itemId);
        var found = this.manager.resolveItems(storage, ids);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    public boolean contains(StorageType storage, int itemId) {
        return find(storage, itemId).isPresent();
    }

    /** Finds the item in whichever storage currently holds it. */
    public Optional<Item> findAnywhere(int itemId) {
        for (StorageType storage : StorageType.values()) {
            if (storage == StorageType.DELETED) continue;
            var found = find(storage, itemId);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    public void put(StorageType storage, Item item) {
        this.manager.addItem(storage, item);
    }

    /** Terminal removal by id: the plugin marks the evicted instance DELETED so stale buttons cannot act on it. */
    public void remove(StorageType storage, int itemId) {
        this.manager.removeItem(storage, itemId);
    }

    /** Drops the per-player lists derived from {@code storage}; they are rebuilt lazily on next use. */
    public void invalidatePlayerLists(StorageType storage) {
        if (storage == null || storage == StorageType.DELETED) return;
        if (storage == StorageType.LISTED) {
            this.manager.clearPlayersCache(PlayerCacheKey.ITEMS_LISTED, PlayerCacheKey.ITEMS_SELLING, PlayerCacheKey.ITEMS_SEARCH);
        } else {
            this.manager.clearPlayersCache(storage.getPlayerCacheKey());
        }
    }

    /** Refreshes open auction menus (the plugin honours its own update-inventory-on-action setting). */
    public void refreshViews(Item item, boolean added) {
        this.manager.updateListedItems(item, added, null);
    }
}
