package net.zyonmc.zyonahouseaddon.listener;

import fr.maxlego08.zauctionhouse.api.item.ItemStatus;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import fr.maxlego08.zauctionhouse.api.storage.StorageManager;
import net.zyonmc.zyonahouseaddon.RedisTestSupport;
import net.zyonmc.zyonahouseaddon.lock.Keys;
import net.zyonmc.zyonahouseaddon.lock.LockManager;
import net.zyonmc.zyonahouseaddon.lock.TerminalState;
import net.zyonmc.zyonahouseaddon.messaging.Envelope;
import net.zyonmc.zyonahouseaddon.redis.RedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.logging.Logger;

import static net.zyonmc.zyonahouseaddon.listener.HandlerFixtures.item;
import static org.junit.jupiter.api.Assertions.*;

class ListedHandlerIT {

    private RedisConnection redis;
    private LockManager locks;
    private final HandlerFixtures.FakeManager fake = new HandlerFixtures.FakeManager();

    @BeforeEach
    void setUp() {
        redis = RedisTestSupport.connectOrSkip();
        locks = new LockManager(redis, new Keys(RedisTestSupport.uniquePrefix()), Duration.ofSeconds(30), Duration.ofHours(1));
    }

    @AfterEach
    void tearDown() {
        if (redis != null) redis.close();
    }

    private ListedHandler handler(StorageManager db) {
        return new ListedHandler(db, new CacheAccess(fake.manager), locks, Logger.getLogger("test"));
    }

    @Test
    void aNewListingIsLoadedFromTheDatabaseAndMenusRefresh() {
        var row = item(1, ItemStatus.AVAILABLE);
        handler(HandlerFixtures.database(Map.of(1, row), Map.of())).handle(Envelope.listed("B", 1)).join();

        assertSame(row, fake.storages.get(StorageType.LISTED).get(1));
        assertTrue(fake.calls.contains("refresh:1:true"));
    }

    @Test
    void aRepeatedListedEventDoesNotDuplicateWork() {
        var handler = handler(HandlerFixtures.database(Map.of(2, item(2, ItemStatus.AVAILABLE)), Map.of()));
        handler.handle(Envelope.listed("B", 2)).join();
        handler.handle(Envelope.listed("C", 2)).join();

        assertEquals(1, fake.calls.stream().filter(call -> call.startsWith("add:")).count());
    }

    @Test
    void aLateListedNeverResurrectsAnItemAlreadySold() {
        locks.mark(3, TerminalState.SOLD).join();

        handler(HandlerFixtures.database(Map.of(3, item(3, ItemStatus.AVAILABLE)), Map.of())).handle(Envelope.listed("B", 3)).join();

        assertFalse(fake.has(StorageType.LISTED, 3));
    }

    @Test
    void aListingTheDatabaseDoesNotHaveIsSkipped() {
        handler(HandlerFixtures.database(Map.of(), Map.of())).handle(Envelope.listed("B", 4)).join();
        assertFalse(fake.has(StorageType.LISTED, 4));
    }

    @Test
    void anUnreadableRowIsSkippedWithoutFailing() {
        handler(HandlerFixtures.database(Map.of(), Map.of(5, StorageManager.LookupState.UNAVAILABLE))).handle(Envelope.listed("B", 5)).join();
        assertFalse(fake.has(StorageType.LISTED, 5));
    }
}
