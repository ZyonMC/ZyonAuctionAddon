package net.zyonmc.zyonahouseaddon.listener;

import fr.maxlego08.zauctionhouse.api.AuctionManager;
import fr.maxlego08.zauctionhouse.api.cache.PlayerCacheKey;
import fr.maxlego08.zauctionhouse.api.item.Item;
import fr.maxlego08.zauctionhouse.api.item.ItemStatus;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import fr.maxlego08.zauctionhouse.api.storage.StorageManager;
import fr.maxlego08.zauctionhouse.api.utils.IntList;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory stand-ins for the parts of the auction plugin the handlers touch. */
final class HandlerFixtures {

    private HandlerFixtures() {
    }

    /** A mutable item: id + status. */
    static Item item(int id, ItemStatus status) {
        var current = new AtomicReference<>(status);
        return (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[]{Item.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getId" -> id;
            case "getStatus" -> current.get();
            case "setStatus" -> {
                current.set((ItemStatus) args[0]);
                yield null;
            }
            case "hashCode" -> id;
            case "equals" -> proxy == args[0];
            case "toString" -> "Item#" + id + "[" + current.get() + "]";
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    /** Records every call the handlers make on the manager. */
    static final class FakeManager {
        final Map<StorageType, Map<Integer, Item>> storages = new EnumMap<>(StorageType.class);
        final List<String> calls = new ArrayList<>();
        final AuctionManager manager;

        FakeManager() {
            for (StorageType type : StorageType.values()) storages.put(type, new HashMap<>());
            this.manager = (AuctionManager) Proxy.newProxyInstance(AuctionManager.class.getClassLoader(), new Class<?>[]{AuctionManager.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "resolveItems" -> {
                        var ids = (IntList) args[1];
                        var out = new ArrayList<Item>();
                        for (int i = 0; i < ids.size(); i++) {
                            var item = storages.get((StorageType) args[0]).get(ids.getInt(i));
                            if (item != null) out.add(item);
                        }
                        return out;
                    }
                    case "addItem" -> {
                        var item = (Item) args[1];
                        storages.get((StorageType) args[0]).put(item.getId(), item);
                        calls.add("add:" + args[0] + ":" + item.getId());
                        return null;
                    }
                    case "removeItem" -> {
                        storages.get((StorageType) args[0]).remove((Integer) args[1]);
                        calls.add("remove:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    case "clearPlayersCache" -> {
                        calls.add("invalidate:" + java.util.Arrays.toString((PlayerCacheKey[]) args[0]));
                        return null;
                    }
                    case "updateListedItems" -> {
                        calls.add("refresh:" + ((Item) args[0]).getId() + ":" + args[1]);
                        return null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        void put(StorageType storage, Item item) {
            storages.get(storage).put(item.getId(), item);
        }

        boolean has(StorageType storage, int id) {
            return storages.get(storage).containsKey(id);
        }
    }

    /** A database holding the given items; anything else is GONE. */
    static StorageManager database(Map<Integer, Item> rows, Map<Integer, StorageManager.LookupState> specialStates) {
        return (StorageManager) Proxy.newProxyInstance(StorageManager.class.getClassLoader(), new Class<?>[]{StorageManager.class}, (proxy, method, args) -> {
            if (method.getName().equals("selectItemState")) {
                int id = (Integer) args[0];
                var special = specialStates.get(id);
                if (special == StorageManager.LookupState.UNAVAILABLE) return CompletableFuture.completedFuture(StorageManager.ItemLookupResult.unavailable());
                var row = rows.get(id);
                return CompletableFuture.completedFuture(row == null ? StorageManager.ItemLookupResult.gone() : StorageManager.ItemLookupResult.found(row));
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }
}
