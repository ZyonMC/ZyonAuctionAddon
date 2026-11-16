package net.zyonmc.zyonahouseaddon.maintenance;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Safety net: re-runs the resync every {@code interval} so a missed event (a Redis blip, a plugin path that
 * did not notify the bridge) heals itself. Runs are never concurrent (fixed DELAY, and each run waits for
 * its own completion) and a failed run never cancels the schedule.
 */
public final class PeriodicResync implements AutoCloseable {

    private final Supplier<CompletableFuture<Void>> task;
    private final Duration interval;
    private final Logger log;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "ZyonAHouseAddon-periodic-resync");
        thread.setDaemon(true);
        return thread;
    });

    public PeriodicResync(Supplier<CompletableFuture<Void>> task, Duration interval, Logger log) {
        this.task = task;
        this.interval = interval;
        this.log = log;
    }

    public void start() {
        long millis = this.interval.toMillis();
        this.scheduler.scheduleWithFixedDelay(this::runOnce, millis, millis, TimeUnit.MILLISECONDS);
    }

    private void runOnce() {
        try {
            this.task.get().join();
        } catch (Throwable throwable) {
            this.log.log(Level.WARNING, "Periodic resync failed; retrying at the next interval", throwable);
        }
    }

    @Override
    public void close() {
        this.scheduler.shutdownNow();
    }
}
