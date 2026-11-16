package net.zyonmc.zyonahouseaddon.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DeduplicatorTest {

    @Test
    void anIdIsAcceptedOnceWithinTheWindow() {
        var clock = new AtomicLong(1_000);
        var dedupe = new Deduplicator(Duration.ofSeconds(10), clock::get);

        assertTrue(dedupe.firstSeen("a"));
        assertFalse(dedupe.firstSeen("a"));
        assertTrue(dedupe.firstSeen("b"));
    }

    @Test
    void memoryStaysBoundedAsTheWindowSlides() {
        var clock = new AtomicLong(0);
        var dedupe = new Deduplicator(Duration.ofSeconds(10), clock::get);

        for (int i = 0; i < 1_000; i++) {
            clock.addAndGet(100);          // 1000 ids over 100 s, window 10 s
            dedupe.firstSeen("id-" + i);
        }
        assertTrue(dedupe.size() < 200, "old ids must be purged, size=" + dedupe.size());
    }

    @Test
    void anIdForgottenAfterTheWindowIsAcceptedAgain() {
        var clock = new AtomicLong(0);
        var dedupe = new Deduplicator(Duration.ofSeconds(10), clock::get);
        assertTrue(dedupe.firstSeen("a"));

        clock.set(60_000);
        dedupe.firstSeen("other"); // triggers the purge
        assertTrue(dedupe.firstSeen("a"));
    }
}
