package net.zyonmc.zyonahouseaddon.lock;

import fr.maxlego08.zauctionhouse.api.item.StorageType;
import net.zyonmc.zyonahouseaddon.RedisTestSupport;
import net.zyonmc.zyonahouseaddon.lock.LockManager.Outcome;
import net.zyonmc.zyonahouseaddon.redis.RedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** The anti-duplication core, against a real Redis. */
class LockManagerIT {

    private RedisConnection redis;
    private LockManager locks;
    private int id;

    @BeforeEach
    void setUp() {
        this.redis = RedisTestSupport.connectOrSkip();
        this.locks = new LockManager(this.redis, new Keys(RedisTestSupport.uniquePrefix()), Duration.ofSeconds(30), Duration.ofHours(1));
        this.id = 1000 + (int) (Math.random() * 100000);
    }

    @AfterEach
    void tearDown() {
        if (this.redis != null) this.redis.close();
    }

    private static String token() {
        return UUID.randomUUID().toString();
    }

    @Test
    void onlyOneOfManyConcurrentBuyersGetsTheLock() {
        List<CompletableFuture<Outcome>> attempts = new ArrayList<>();
        for (int i = 0; i < 50; i++) attempts.add(this.locks.acquire(this.id, token(), StorageType.LISTED));

        long acquired = attempts.stream().map(CompletableFuture::join).filter(outcome -> outcome == Outcome.ACQUIRED).count();
        assertEquals(1, acquired, "exactly one buyer may hold the lock");
        attempts.stream().map(CompletableFuture::join).filter(o -> o != Outcome.ACQUIRED)
                .forEach(outcome -> assertEquals(Outcome.BUSY, outcome));
    }

    @Test
    void onlyTheOwnerCanReleaseOrRenew() {
        String owner = token();
        assertEquals(Outcome.ACQUIRED, this.locks.acquire(this.id, owner, StorageType.LISTED).join());

        String intruder = token();
        assertFalse(this.locks.release(this.id, intruder).join(), "a stranger must not free the lock");
        assertFalse(this.locks.renew(this.id, intruder).join());
        assertFalse(this.locks.isHeldBy(this.id, intruder).join());

        assertTrue(this.locks.isHeldBy(this.id, owner).join());
        assertTrue(this.locks.renew(this.id, owner).join());
        assertTrue(this.locks.release(this.id, owner).join());
        assertFalse(this.locks.release(this.id, owner).join(), "second release finds nothing");
        assertEquals(Outcome.ACQUIRED, this.locks.acquire(this.id, token(), StorageType.LISTED).join());
    }

    @Test
    void anExpiredLeaseIsReportedAsLostAndCannotBeReleased() throws InterruptedException {
        var shortLived = new LockManager(this.redis, new Keys(RedisTestSupport.uniquePrefix()), Duration.ofMillis(300), Duration.ofHours(1));
        String owner = token();
        assertEquals(Outcome.ACQUIRED, shortLived.acquire(this.id, owner, StorageType.LISTED).join());

        Thread.sleep(500);

        assertFalse(shortLived.isHeldBy(this.id, owner).join(), "isHeldBy must expose the lost lease");
        assertFalse(shortLived.renew(this.id, owner).join());
        assertFalse(shortLived.release(this.id, owner).join());
        assertEquals(Outcome.ACQUIRED, shortLived.acquire(this.id, token(), StorageType.LISTED).join(), "lock is free again");
    }

    @Test
    void aSoldItemCannotBeBoughtAgainButTheBuyerCanClaimIt() {
        this.locks.mark(this.id, TerminalState.SOLD).join();

        assertEquals(Outcome.UNAVAILABLE, this.locks.acquire(this.id, token(), StorageType.LISTED).join());
        assertEquals(Outcome.UNAVAILABLE, this.locks.acquire(this.id, token(), StorageType.EXPIRED).join());
        assertEquals(Outcome.ACQUIRED, this.locks.acquire(this.id, token(), StorageType.PURCHASED).join());
    }

    @Test
    void aRemovedItemCannotBeBoughtButTheSellerCanClaimIt() {
        this.locks.mark(this.id, TerminalState.REMOVED).join();

        assertEquals(Outcome.UNAVAILABLE, this.locks.acquire(this.id, token(), StorageType.LISTED).join());
        assertEquals(Outcome.UNAVAILABLE, this.locks.acquire(this.id, token(), StorageType.PURCHASED).join());
        assertEquals(Outcome.ACQUIRED, this.locks.acquire(this.id, token(), StorageType.EXPIRED).join());
    }

    @Test
    void aDeletedItemIsRefusedEverywhereAndTheMarkIsSticky() {
        this.locks.mark(this.id, TerminalState.DELETED).join();
        for (StorageType scope : StorageType.values()) {
            assertEquals(Outcome.UNAVAILABLE, this.locks.acquire(this.id, token(), scope).join(), "scope " + scope);
        }
        assertFalse(this.locks.isAvailable(this.id, null).join());

        // A late SOLD/REMOVED must not weaken DELETED.
        this.locks.mark(this.id, TerminalState.SOLD).join();
        assertEquals(Optional.of(TerminalState.DELETED), this.locks.terminalState(this.id).join());
    }

    @Test
    void relistingClearsTheMark() {
        this.locks.mark(this.id, TerminalState.REMOVED).join();
        this.locks.clearMark(this.id).join();
        assertEquals(Optional.empty(), this.locks.terminalState(this.id).join());
        assertEquals(Outcome.ACQUIRED, this.locks.acquire(this.id, token(), StorageType.LISTED).join());
    }

    @Test
    void displayAvailabilityIgnoresLocksButNotTerminalStates() {
        assertEquals(Outcome.ACQUIRED, this.locks.acquire(this.id, token(), StorageType.LISTED).join());
        assertTrue(this.locks.isAvailable(this.id, null).join(), "a held lock is contention, not unavailability");
        assertTrue(this.locks.isAvailable(this.id, StorageType.LISTED).join());

        this.locks.mark(this.id, TerminalState.SOLD).join();
        assertTrue(this.locks.isAvailable(this.id, null).join(), "legacy predicate: only DELETED blocks");
        assertFalse(this.locks.isAvailable(this.id, StorageType.LISTED).join());
        assertTrue(this.locks.isAvailable(this.id, StorageType.PURCHASED).join());
    }

    @Test
    void whenRedisIsClosedEveryCallFailsInsteadOfPretendingToLock() {
        this.redis.close();
        var failure = assertThrows(Exception.class, () -> this.locks.acquire(this.id, token(), StorageType.LISTED).join());
        assertNotNull(failure, "fail-closed: a transport error must surface as a failed future");
        this.redis = null;
    }
}
