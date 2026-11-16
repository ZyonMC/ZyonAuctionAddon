package net.zyonmc.zyonahouseaddon.bridge;

import fr.maxlego08.zauctionhouse.api.cluster.LockToken;
import fr.maxlego08.zauctionhouse.api.item.ItemStatus;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import net.zyonmc.zyonahouseaddon.RedisTestSupport;
import net.zyonmc.zyonahouseaddon.lock.Keys;
import net.zyonmc.zyonahouseaddon.lock.LockManager;
import net.zyonmc.zyonahouseaddon.lock.TerminalState;
import net.zyonmc.zyonahouseaddon.messaging.*;
import net.zyonmc.zyonahouseaddon.redis.RedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static net.zyonmc.zyonahouseaddon.RedisTestSupport.item;
import static net.zyonmc.zyonahouseaddon.RedisTestSupport.player;
import static org.junit.jupiter.api.Assertions.*;

/** Two simulated servers (A and B) sharing one Redis: the real multi-server scenarios. */
class RedisAuctionClusterBridgeIT {

    private RedisConnection redisA;
    private RedisConnection redisB;
    private RedisAuctionClusterBridge a;
    private RedisAuctionClusterBridge b;
    private LockManager locksA;
    private final List<Envelope> seenByB = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        redisA = RedisTestSupport.connectOrSkip();
        redisB = RedisTestSupport.connectOrSkip();
        var keys = new Keys(RedisTestSupport.uniquePrefix());
        var log = Logger.getLogger("test");
        locksA = new LockManager(redisA, keys, Duration.ofSeconds(30), Duration.ofHours(1));
        var locksB = new LockManager(redisB, keys, Duration.ofSeconds(30), Duration.ofHours(1));
        var busA = new EventBus(redisA, keys, "A", new EventCodec(), new Deduplicator(Duration.ofSeconds(60)), Runnable::run, log, false);
        var busB = new EventBus(redisB, keys, "B", new EventCodec(), new Deduplicator(Duration.ofSeconds(60)), Runnable::run, log, false);
        busB.start(seenByB::add);
        a = new RedisAuctionClusterBridge(locksA, busA, "A");
        b = new RedisAuctionClusterBridge(locksB, busB, "B");
    }

    @AfterEach
    void tearDown() {
        if (redisA != null) redisA.close();
        if (redisB != null) redisB.close();
    }

    private void awaitEvents(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (seenByB.size() < count && System.nanoTime() < deadline) Thread.sleep(20);
    }

    @Test
    void twoServersCannotBuyTheSameListingAtOnce() {
        var item = item(1, UUID.randomUUID());
        var winner = a.checkAndLock(item, UUID.randomUUID(), StorageType.LISTED).join();
        var loser = b.checkAndLock(item, UUID.randomUUID(), StorageType.LISTED).join();

        assertTrue(winner.isAcquired());
        assertTrue(loser.isNoop(), "contention is a NOOP token, not an error");
        assertFalse(loser.isUnavailable());
    }

    @Test
    void aStrangerTokenCannotReleaseAnotherServersLock() {
        var item = item(2, UUID.randomUUID());
        var token = a.lockItem(item, UUID.randomUUID(), StorageType.LISTED).join();
        var forged = LockToken.issue(item, UUID.randomUUID());

        assertFalse(b.releaseLock(item, forged, StorageType.LISTED).join());
        assertTrue(a.isHeldBy(item, token, StorageType.LISTED).join(), "A still holds it");
        assertTrue(b.lockItem(item, UUID.randomUUID(), StorageType.LISTED).join().isNoop());

        assertTrue(a.releaseLock(item, token, StorageType.LISTED).join());
        assertTrue(b.lockItem(item, UUID.randomUUID(), StorageType.LISTED).join().isAcquired());
    }

    @Test
    void releasingASentinelTokenIsHarmless() {
        var item = item(3, UUID.randomUUID());
        assertFalse(a.releaseLock(item, LockToken.noop(), StorageType.LISTED).join());
        assertFalse(a.releaseLock(item, LockToken.unavailable(), StorageType.LISTED).join());
        assertFalse(a.releaseLock(item, null, StorageType.LISTED).join());
        assertFalse(a.renewLock(item, LockToken.noop(), StorageType.LISTED).join());
        assertFalse(a.isHeldBy(item, null, StorageType.LISTED).join());
    }

    @Test
    void afterAPurchaseNobodyCanBuyAgainAndOnlyTheBuyerCanClaim() throws Exception {
        var seller = UUID.randomUUID();
        var item = item(4, seller);
        var token = a.checkAndLock(item, UUID.randomUUID(), StorageType.LISTED).join();

        a.notifyItemBought(player("Steve"), item).join();
        a.releaseLock(item, token, StorageType.LISTED).join();

        assertTrue(b.checkAndLock(item, UUID.randomUUID(), StorageType.LISTED).join().isUnavailable(), "no second purchase, even after the lock is gone");
        assertFalse(b.checkAvailability(item, StorageType.LISTED).join());
        assertTrue(b.checkAvailability(item, StorageType.PURCHASED).join());
        assertTrue(b.checkAndLock(item, UUID.randomUUID(), StorageType.PURCHASED).join().isAcquired(), "the claim must still work");

        awaitEvents(1);
        var event = seenByB.getFirst();
        assertEquals(EventType.BOUGHT, event.type());
        assertEquals(seller, event.seller());
        assertEquals("Steve", event.buyer());
        assertEquals("Diamond Sword", event.display());
    }

    @Test
    void anExpiredListingIsRefusedToBuyersButClaimableBySeller() {
        var item = item(5, UUID.randomUUID());
        a.removeItem(item, StorageType.LISTED, StorageType.EXPIRED).join();

        assertTrue(b.checkAndLock(item, UUID.randomUUID(), StorageType.LISTED).join().isUnavailable());
        assertTrue(b.checkAndLock(item, UUID.randomUUID(), StorageType.EXPIRED).join().isAcquired());
    }

    @Test
    void afterTheClaimTheItemIsDestroyedEverywhere() {
        var item = item(6, UUID.randomUUID());
        a.removeItem(item, StorageType.EXPIRED, StorageType.DELETED).join();

        for (StorageType scope : StorageType.values()) {
            assertTrue(b.checkAndLock(item, UUID.randomUUID(), scope).join().isUnavailable(), "scope " + scope);
        }
    }

    @Test
    void anUnknownDestinationRecordsNoTerminalFact() {
        var item = item(7, UUID.randomUUID());
        a.removeItem(item, StorageType.LISTED).join();   // two-argument form: destination unknown

        assertEquals(Optional.empty(), locksA.terminalState(7).join(), "never freeze an item on a guess");
        assertTrue(b.checkAndLock(item, UUID.randomUUID(), StorageType.LISTED).join().isAcquired());
    }

    @Test
    void relistingAnIdClearsItsOldMark() {
        var item = item(8, UUID.randomUUID());
        a.removeItem(item, StorageType.LISTED, StorageType.EXPIRED).join();
        a.notifyItemListed(item).join();

        assertTrue(b.checkAndLock(item, UUID.randomUUID(), StorageType.LISTED).join().isAcquired());
    }

    @Test
    void statusChangesBroadcastAndOnlyTerminalOnesRecordAMark() throws Exception {
        var item = item(9, UUID.randomUUID());
        a.notifyItemStatusChange(item, ItemStatus.AVAILABLE, ItemStatus.IS_BEING_PURCHASED).join();
        a.notifyItemStatusChange(item, ItemStatus.IS_BEING_REMOVED, ItemStatus.REMOVED).join();
        assertEquals(Optional.empty(), locksA.terminalState(9).join(), "REMOVED on its own is ambiguous");

        a.notifyItemStatusChange(item, ItemStatus.IS_BEING_PURCHASED, ItemStatus.PURCHASED).join();
        assertEquals(Optional.of(TerminalState.SOLD), locksA.terminalState(9).join());

        awaitEvents(3);
        assertEquals(3, seenByB.size());
        assertEquals(ItemStatus.IS_BEING_PURCHASED, seenByB.getFirst().newStatus());
    }

    @Test
    void whenRedisDiesLockingFailsInsteadOfSucceedingBlindly() {
        var item = item(10, UUID.randomUUID());
        redisA.close();

        assertThrows(Exception.class, () -> a.checkAndLock(item, UUID.randomUUID(), StorageType.LISTED).join());
        assertThrows(Exception.class, () -> a.lockItem(item, UUID.randomUUID(), StorageType.LISTED).join());
        assertThrows(Exception.class, () -> a.notifyItemListed(item).join());
        redisA = null;
    }

    @Test
    void theBridgeAdvertisesItselfAsDistributedWithALease() {
        assertTrue(a.isDistributed());
        assertEquals(Duration.ofSeconds(30), a.lockLeaseDuration());
    }
}
