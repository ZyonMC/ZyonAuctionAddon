package net.zyonmc.zyonahouseaddon.listener;

import fr.maxlego08.zauctionhouse.api.item.ItemStatus;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import fr.maxlego08.zauctionhouse.api.storage.StorageManager;
import net.zyonmc.zyonahouseaddon.messaging.Envelope;
import net.zyonmc.zyonahouseaddon.notify.SellerNotices;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.zyonmc.zyonahouseaddon.listener.HandlerFixtures.item;
import static org.junit.jupiter.api.Assertions.*;

class RemovedHandlerTest {

    private final HandlerFixtures.FakeManager fake = new HandlerFixtures.FakeManager();

    private final List<String> notices = new ArrayList<>();
    private final SellerNotices recorder = new SellerNotices() {
        @Override
        public void itemSold(Envelope envelope) {
            notices.add("sold:" + envelope.itemId());
        }

        @Override
        public void collectPayment(Envelope envelope) {
            notices.add("collect:" + envelope.itemId());
        }

        @Override
        public void itemExpired(Envelope envelope) {
            notices.add("expired:" + envelope.itemId());
        }
    };

    private RemovedHandler handler(Map<Integer, fr.maxlego08.zauctionhouse.api.item.Item> database) {
        return new RemovedHandler(HandlerFixtures.database(database, Map.of()), new CacheAccess(fake.manager), recorder);
    }

    @Test
    void theSellerIsToldWhenAListingExpiresOrIsWithdrawn() {
        fake.put(StorageType.LISTED, item(7, ItemStatus.AVAILABLE));
        handler(Map.of(7, item(7, ItemStatus.AVAILABLE))).handle(Envelope.removed("B", 7, StorageType.LISTED, StorageType.EXPIRED)).join();
        assertEquals(List.of("expired:7"), notices);
    }

    @Test
    void theSellerIsToldWhenAListingIsBought() {
        fake.put(StorageType.LISTED, item(8, ItemStatus.AVAILABLE));
        handler(Map.of(8, item(8, ItemStatus.AVAILABLE))).handle(Envelope.bought("B", 8, java.util.UUID.randomUUID(), "Steve", "Sword", "$1")).join();
        assertEquals(List.of("sold:8", "collect:8"), notices);
    }

    @Test
    void nothingElseTriggersAMessage() {
        fake.put(StorageType.EXPIRED, item(9, ItemStatus.REMOVED));
        handler(Map.of()).handle(Envelope.removed("B", 9, StorageType.EXPIRED, StorageType.DELETED)).join();   // a claim
        handler(Map.of()).handle(Envelope.removed("B", 10, StorageType.LISTED, null)).join();                   // unknown destination
        assertTrue(notices.isEmpty(), "claims and unknown moves must stay silent: " + notices);
    }

    @Test
    void anExpiredListingMovesFromListedToExpiredUsingTheDatabaseCopy() {
        fake.put(StorageType.LISTED, item(1, ItemStatus.AVAILABLE));
        var fromDb = item(1, ItemStatus.AVAILABLE);

        handler(Map.of(1, fromDb)).handle(Envelope.removed("B", 1, StorageType.LISTED, StorageType.EXPIRED)).join();

        assertFalse(fake.has(StorageType.LISTED, 1));
        assertSame(fromDb, fake.storages.get(StorageType.EXPIRED).get(1), "destination copy must come from the database");
        assertTrue(fake.calls.contains("refresh:1:false"), "open menus must drop the listing");
    }

    @Test
    void aDestroyedItemIsRemovedAndNeverAddedAnywhere() {
        fake.put(StorageType.EXPIRED, item(2, ItemStatus.REMOVED));

        handler(Map.of(2, item(2, ItemStatus.AVAILABLE))).handle(Envelope.removed("B", 2, StorageType.EXPIRED, StorageType.DELETED)).join();

        for (StorageType type : StorageType.values()) assertFalse(fake.has(type, 2), "still in " + type);
    }

    @Test
    void anItemMissingFromTheDatabaseIsNotResurrected() {
        fake.put(StorageType.LISTED, item(3, ItemStatus.AVAILABLE));

        handler(Map.of()).handle(Envelope.removed("B", 3, StorageType.LISTED, StorageType.EXPIRED)).join();

        assertFalse(fake.has(StorageType.LISTED, 3));
        assertFalse(fake.has(StorageType.EXPIRED, 3), "GONE in the database means nothing to show");
    }

    @Test
    void anUnknownDestinationOnlyRemovesFromTheSource() {
        fake.put(StorageType.LISTED, item(4, ItemStatus.AVAILABLE));

        handler(Map.of(4, item(4, ItemStatus.AVAILABLE))).handle(Envelope.removed("B", 4, StorageType.LISTED, null)).join();

        assertFalse(fake.has(StorageType.LISTED, 4));
        assertFalse(fake.has(StorageType.EXPIRED, 4));
        assertFalse(fake.has(StorageType.PURCHASED, 4));
    }

    @Test
    void anAlreadyPresentDestinationCopyIsKept() {
        var local = item(5, ItemStatus.AVAILABLE);
        fake.put(StorageType.EXPIRED, local);
        fake.put(StorageType.LISTED, item(5, ItemStatus.AVAILABLE));

        handler(Map.of(5, item(5, ItemStatus.AVAILABLE))).handle(Envelope.removed("B", 5, StorageType.LISTED, StorageType.EXPIRED)).join();

        assertSame(local, fake.storages.get(StorageType.EXPIRED).get(5));
    }

    @Test
    void aPlayerListInvalidationFollowsEveryMove() {
        fake.put(StorageType.LISTED, item(6, ItemStatus.AVAILABLE));
        handler(Map.of(6, item(6, ItemStatus.AVAILABLE))).handle(Envelope.removed("B", 6, StorageType.LISTED, StorageType.EXPIRED)).join();

        assertTrue(fake.calls.stream().anyMatch(call -> call.startsWith("invalidate:") && call.contains("ITEMS_LISTED")));
        assertTrue(fake.calls.stream().anyMatch(call -> call.startsWith("invalidate:") && call.contains("ITEMS_EXPIRED")));
    }
}
