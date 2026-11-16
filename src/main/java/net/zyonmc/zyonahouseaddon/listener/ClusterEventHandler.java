package net.zyonmc.zyonahouseaddon.listener;

import net.zyonmc.zyonahouseaddon.messaging.Envelope;

import java.util.concurrent.CompletableFuture;

/** Applies ONE kind of event received from another server to this server's state. */
public interface ClusterEventHandler {

    /** The returned future fails only on unexpected errors; "nothing to do" is a normal completion. */
    CompletableFuture<Void> handle(Envelope envelope);
}
