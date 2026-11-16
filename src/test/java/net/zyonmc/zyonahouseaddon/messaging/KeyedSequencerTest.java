package net.zyonmc.zyonahouseaddon.messaging;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KeyedSequencerTest {

    @Test
    void sameKeyTasksRunInSubmissionOrderEvenWhenAnEarlierOneIsSlow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            var sequencer = new KeyedSequencer<Integer>(pool);
            List<String> order = Collections.synchronizedList(new java.util.ArrayList<>());

            var slow = new CompletableFuture<Void>();
            var first = sequencer.submit(7, () -> slow.thenRun(() -> order.add("LISTED")));
            var second = sequencer.submit(7, () -> {
                order.add("BOUGHT");
                return CompletableFuture.completedFuture(null);
            });

            Thread.sleep(150);
            assertTrue(order.isEmpty(), "BOUGHT must wait for the slow LISTED");
            slow.complete(null);
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);

            assertEquals(List.of("LISTED", "BOUGHT"), order);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentKeysDoNotBlockEachOther() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            var sequencer = new KeyedSequencer<Integer>(pool);
            var blocked = new CompletableFuture<Void>();
            sequencer.submit(1, () -> blocked);

            var other = sequencer.submit(2, () -> CompletableFuture.completedFuture(null));
            other.get(2, TimeUnit.SECONDS);
            assertFalse(blocked.isDone());
            blocked.complete(null);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFailedTaskDoesNotBlockTheNextOneAndReportsItsError() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var sequencer = new KeyedSequencer<Integer>(pool);
            var ran = new AtomicInteger();

            var failing = sequencer.submit(5, () -> CompletableFuture.failedFuture(new IllegalStateException("boom")));
            var throwing = sequencer.submit(5, () -> {
                throw new IllegalArgumentException("thrown");
            });
            var after = sequencer.submit(5, () -> {
                ran.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            after.get(2, TimeUnit.SECONDS);
            assertEquals(1, ran.get());
            assertTrue(assertThrows(CompletionException.class, failing::join).getCause() instanceof IllegalStateException);
            assertTrue(assertThrows(CompletionException.class, throwing::join).getCause() instanceof IllegalArgumentException);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void idleKeysAreForgotten() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var sequencer = new KeyedSequencer<Integer>(pool);
            for (int key = 0; key < 100; key++) sequencer.submit(key, () -> CompletableFuture.completedFuture(null)).get(2, TimeUnit.SECONDS);
            for (int i = 0; i < 50 && sequencer.pendingKeys() > 0; i++) Thread.sleep(20);
            assertEquals(0, sequencer.pendingKeys(), "no per-key state may leak");
        } finally {
            pool.shutdownNow();
        }
    }
}
