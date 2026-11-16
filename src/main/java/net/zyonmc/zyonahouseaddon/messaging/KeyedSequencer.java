package net.zyonmc.zyonahouseaddon.messaging;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Runs asynchronous tasks one after another PER KEY, while different keys run in parallel.
 * <p>
 * Events about the same item are handled with database reads in between; without this a slow LISTED
 * handler could finish AFTER the BOUGHT that followed it and resurrect a sold listing in the cache.
 * A failed task never blocks the ones queued behind it.
 */
public final class KeyedSequencer<K> {

    private final ConcurrentHashMap<K, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
    private final Executor executor;

    public KeyedSequencer(Executor executor) {
        this.executor = executor;
    }

    /** @return a future completing with the task outcome (exceptionally if the task failed) */
    public CompletableFuture<Void> submit(K key, Supplier<CompletableFuture<Void>> task) {
        CompletableFuture<Void> outcome = new CompletableFuture<>();
        // The task is chained asynchronously: it must never run inside compute().
        CompletableFuture<Void> tail = this.tails.compute(key, (k, previous) -> {
            CompletableFuture<Void> base = previous == null ? CompletableFuture.completedFuture(null) : previous;
            return base.thenComposeAsync(ignored -> {
                CompletableFuture<Void> running;
                try {
                    running = task.get();
                } catch (Throwable throwable) {
                    running = CompletableFuture.failedFuture(throwable);
                }
                return running.whenComplete((value, error) -> {
                    if (error == null) outcome.complete(null);
                    else outcome.completeExceptionally(error);
                }).handle((value, error) -> (Void) null);
            }, this.executor);
        });
        tail.thenRun(() -> this.tails.remove(key, tail));
        return outcome;
    }

    int pendingKeys() {
        return this.tails.size();
    }
}
