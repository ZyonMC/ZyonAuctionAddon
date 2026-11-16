package net.zyonmc.zyonahouseaddon.listener;

import fr.maxlego08.zauctionhouse.api.item.ItemStatus;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import net.zyonmc.zyonahouseaddon.messaging.Envelope;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static net.zyonmc.zyonahouseaddon.listener.HandlerFixtures.item;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusHandlerTest {

    private final HandlerFixtures.FakeManager fake = new HandlerFixtures.FakeManager();
    private final StatusHandler handler = new StatusHandler(new CacheAccess(fake.manager), Logger.getLogger("test"), false);

    @Test
    void theStatusIsMirroredWhenTheLocalOneMatchesWhatThePublisherLeft() {
        var item = item(1, ItemStatus.AVAILABLE);
        fake.put(StorageType.LISTED, item);

        handler.handle(Envelope.status("B", 1, ItemStatus.AVAILABLE, ItemStatus.IS_BEING_PURCHASED)).join();

        assertEquals(ItemStatus.IS_BEING_PURCHASED, item.getStatus());
    }

    @Test
    void anAlreadyPurchasedItemIsNeverBroughtBack() {
        // C-004: a late IS_PURCHASE_CONFIRM / AVAILABLE must not revive a PURCHASED item.
        var item = item(2, ItemStatus.PURCHASED);
        fake.put(StorageType.PURCHASED, item);

        handler.handle(Envelope.status("B", 2, ItemStatus.PURCHASED, ItemStatus.IS_PURCHASE_CONFIRM)).join();
        handler.handle(Envelope.status("B", 2, ItemStatus.IS_BEING_PURCHASED, ItemStatus.AVAILABLE)).join();

        assertEquals(ItemStatus.PURCHASED, item.getStatus());
    }

    @Test
    void deletedAndRemovedStayTerminal() {
        var deleted = item(3, ItemStatus.DELETED);
        var removed = item(4, ItemStatus.REMOVED);
        fake.put(StorageType.EXPIRED, deleted);
        fake.put(StorageType.EXPIRED, removed);

        handler.handle(Envelope.status("B", 3, ItemStatus.DELETED, ItemStatus.AVAILABLE)).join();
        handler.handle(Envelope.status("B", 4, ItemStatus.REMOVED, ItemStatus.AVAILABLE)).join();

        assertEquals(ItemStatus.DELETED, deleted.getStatus());
        assertEquals(ItemStatus.REMOVED, removed.getStatus());
    }

    @Test
    void aMismatchedOldStatusIsIgnored() {
        var item = item(5, ItemStatus.IS_REMOVE_CONFIRM);
        fake.put(StorageType.LISTED, item);

        handler.handle(Envelope.status("B", 5, ItemStatus.AVAILABLE, ItemStatus.IS_BEING_PURCHASED)).join();

        assertEquals(ItemStatus.IS_REMOVE_CONFIRM, item.getStatus(), "out-of-order message: keep what we know");
    }

    @Test
    void unknownItemsAndIncompleteEventsAreNoOps() {
        handler.handle(Envelope.status("B", 99, ItemStatus.AVAILABLE, ItemStatus.IS_BEING_PURCHASED)).join();
        handler.handle(Envelope.status("B", 99, null, null)).join();
        assertEquals(0, fake.calls.size());
    }
}
