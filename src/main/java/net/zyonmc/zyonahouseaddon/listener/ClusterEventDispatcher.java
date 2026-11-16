package net.zyonmc.zyonahouseaddon.listener;

import net.zyonmc.zyonahouseaddon.messaging.Envelope;
import net.zyonmc.zyonahouseaddon.messaging.EventType;
import net.zyonmc.zyonahouseaddon.messaging.KeyedSequencer;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Routes each event to its handler, serialized per item so the effects keep the publisher's order. */
public final class ClusterEventDispatcher {

    private static final int GLOBAL_KEY = 0;

    private final Map<EventType, ClusterEventHandler> handlers = new EnumMap<>(EventType.class);
    private final KeyedSequencer<Integer> sequencer;
    private final Logger log;

    public ClusterEventDispatcher(Executor executor, Logger log) {
        this.sequencer = new KeyedSequencer<>(executor);
        this.log = log;
    }

    public ClusterEventDispatcher register(EventType type, ClusterEventHandler handler) {
        this.handlers.put(type, handler);
        return this;
    }

    public void dispatch(Envelope envelope) {
        ClusterEventHandler handler = this.handlers.get(envelope.type());
        if (handler == null) return;

        boolean global = envelope.type() == EventType.RESYNC || envelope.type() == EventType.RELOAD;
        int key = global ? GLOBAL_KEY : envelope.itemId();
        this.sequencer.submit(key, () -> handler.handle(envelope)).exceptionally(error -> {
            this.log.log(Level.SEVERE, "Handler for " + envelope.type() + " (item " + envelope.itemId() + ") failed", error);
            return null;
        });
    }
}
