package net.zyonmc.zyonahouseaddon.messaging;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Remembers event ids for a bounded window so a duplicated delivery is processed once. Memory stays bounded. */
public final class Deduplicator {

    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
    private final long windowMs;
    private final LongSupplier clock;
    private final AtomicLong nextPurge;

    public Deduplicator(Duration window) {
        this(window, System::currentTimeMillis);
    }

    Deduplicator(Duration window, LongSupplier clock) {
        this.windowMs = window.toMillis();
        this.clock = clock;
        this.nextPurge = new AtomicLong(clock.getAsLong() + this.windowMs);
    }

    /** @return {@code true} the first time this id is seen within the window */
    public boolean firstSeen(String eventId) {
        long now = this.clock.getAsLong();
        purgeIfDue(now);
        return this.seen.putIfAbsent(eventId, now + this.windowMs) == null;
    }

    int size() {
        return this.seen.size();
    }

    private void purgeIfDue(long now) {
        long due = this.nextPurge.get();
        if (now >= due && this.nextPurge.compareAndSet(due, now + this.windowMs / 4)) {
            this.seen.values().removeIf(expiry -> expiry <= now);
        }
    }
}
