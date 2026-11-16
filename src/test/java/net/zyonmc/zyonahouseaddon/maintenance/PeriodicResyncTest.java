package net.zyonmc.zyonahouseaddon.maintenance;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PeriodicResyncTest {

    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void runsRepeatedlyAndSurvivesFailures() throws Exception {
        var runs = new AtomicInteger();
        var enough = new CountDownLatch(3);
        try (var resync = new PeriodicResync(() -> {
            int run = runs.incrementAndGet();
            enough.countDown();
            return run == 1 ? CompletableFuture.failedFuture(new IllegalStateException("db down")) : CompletableFuture.completedFuture(null);
        }, Duration.ofMillis(40), LOG)) {
            resync.start();
            assertTrue(enough.await(3, TimeUnit.SECONDS), "a failed run must not stop the schedule");
        }
    }

    @Test
    void neverRunsTwoResyncsAtTheSameTime() throws Exception {
        var running = new AtomicInteger();
        var maxSeen = new AtomicInteger();
        var runs = new CountDownLatch(3);
        try (var resync = new PeriodicResync(() -> CompletableFuture.runAsync(() -> {
            maxSeen.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(120);          // slower than the interval
            } catch (InterruptedException ignored) {
            }
            running.decrementAndGet();
            runs.countDown();
        }), Duration.ofMillis(30), LOG)) {
            resync.start();
            assertTrue(runs.await(5, TimeUnit.SECONDS));
        }
        assertEquals(1, maxSeen.get());
    }

    @Test
    void stopsWhenClosed() throws Exception {
        var runs = new AtomicInteger();
        var resync = new PeriodicResync(() -> {
            runs.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }, Duration.ofMillis(30), LOG);
        resync.start();
        Thread.sleep(150);
        resync.close();
        int atClose = runs.get();
        Thread.sleep(200);
        assertEquals(atClose, runs.get());
    }
}
