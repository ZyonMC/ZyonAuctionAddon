package net.zyonmc.zyonahouseaddon.messaging;

import fr.maxlego08.zauctionhouse.api.item.ItemStatus;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventCodecTest {

    private final EventCodec codec = new EventCodec();

    @Test
    void roundTripKeepsEveryField() {
        var seller = UUID.randomUUID();
        var original = Envelope.bought("srv-1", 42, seller, "Steve", "Diamond Sword", "$100");

        var decoded = codec.decode(codec.encode(original)).orElseThrow();

        assertEquals(original, decoded);
        assertEquals(EventType.BOUGHT, decoded.type());
        assertEquals(StorageType.PURCHASED, decoded.destination());
        assertEquals(seller, decoded.seller());
    }

    @Test
    void statusAndRemovedRoundTrip() {
        var status = Envelope.status("a", 7, ItemStatus.AVAILABLE, ItemStatus.IS_BEING_PURCHASED);
        assertEquals(status, codec.decode(codec.encode(status)).orElseThrow());

        var removed = Envelope.removed("a", 7, StorageType.LISTED, null);
        var back = codec.decode(codec.encode(removed)).orElseThrow();
        assertNull(back.destination(), "an unknown destination must stay unknown, never default to something");
    }

    @Test
    void removedCarriesTheSellerAndDisplayForNotices() {
        var seller = UUID.randomUUID();
        var removed = Envelope.removed("a", 3, StorageType.LISTED, StorageType.EXPIRED, seller, "Diamond Sword");
        var back = codec.decode(codec.encode(removed)).orElseThrow();
        assertEquals(seller, back.seller());
        assertEquals("Diamond Sword", back.display());
    }

    @Test
    void reloadRoundTrips() {
        var reload = Envelope.reload("a");
        assertEquals(reload, codec.decode(codec.encode(reload)).orElseThrow());
        assertEquals(EventType.RELOAD, reload.type());
    }

    @Test
    void garbageIsDroppedNotThrown() {
        assertTrue(codec.decode(null).isEmpty());
        assertTrue(codec.decode("").isEmpty());
        assertTrue(codec.decode("not json").isEmpty());
        assertTrue(codec.decode("{").isEmpty());
        assertTrue(codec.decode("[]").isEmpty());
        assertTrue(codec.decode("{}").isEmpty());
    }

    @Test
    void unknownTypeIsDropped() {
        assertTrue(codec.decode("{\"v\":1,\"id\":\"x\",\"origin\":\"o\",\"type\":\"TELEPORT\",\"itemId\":1}").isEmpty());
    }

    @Test
    void aNewerVersionIsNeverActedOn() {
        assertTrue(codec.decode("{\"v\":" + (Envelope.VERSION + 1) + ",\"id\":\"x\",\"origin\":\"o\",\"type\":\"LISTED\",\"itemId\":1}").isEmpty());
        assertTrue(codec.decode("{\"v\":0,\"id\":\"x\",\"origin\":\"o\",\"type\":\"LISTED\",\"itemId\":1}").isEmpty());
    }

    @Test
    void messagesWithoutIdentityAreDropped() {
        assertTrue(codec.decode("{\"v\":1,\"origin\":\"o\",\"type\":\"LISTED\",\"itemId\":1}").isEmpty());
        assertTrue(codec.decode("{\"v\":1,\"id\":\"x\",\"type\":\"LISTED\",\"itemId\":1}").isEmpty());
    }
}
