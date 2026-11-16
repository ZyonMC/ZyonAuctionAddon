package net.zyonmc.zyonahouseaddon.messaging;

import net.zyonmc.zyonahouseaddon.RedisTestSupport;
import net.zyonmc.zyonahouseaddon.lock.Keys;
import net.zyonmc.zyonahouseaddon.redis.RedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class EventBusIT {

    private RedisConnection redisA;
    private RedisConnection redisB;
    private final List<Envelope> receivedByA = new CopyOnWriteArrayList<>();
    private final List<Envelope> receivedByB = new CopyOnWriteArrayList<>();
    private EventBus busA;
    private EventBus busB;

    @BeforeEach
    void setUp() {
        redisA = RedisTestSupport.connectOrSkip();
        redisB = RedisTestSupport.connectOrSkip();
        var keys = new Keys(RedisTestSupport.uniquePrefix());
        var log = Logger.getLogger("test");
        busA = new EventBus(redisA, keys, "server-a", new EventCodec(), new Deduplicator(Duration.ofSeconds(60)), Runnable::run, log, false);
        busB = new EventBus(redisB, keys, "server-b", new EventCodec(), new Deduplicator(Duration.ofSeconds(60)), Runnable::run, log, false);
        busA.start(receivedByA::add);
        busB.start(receivedByB::add);
    }

    @AfterEach
    void tearDown() {
        if (redisA != null) redisA.close();
        if (redisB != null) redisB.close();
    }

    private static void await(List<?> list, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (list.size() < expected && System.nanoTime() < deadline) Thread.sleep(20);
    }

    @Test
    void aPublishedEventReachesTheOtherServerButNotItsOwnPublisher() throws Exception {
        busA.publish(Envelope.listed("server-a", 11)).join();

        await(receivedByB, 1);
        Thread.sleep(150);
        assertEquals(1, receivedByB.size());
        assertEquals(11, receivedByB.getFirst().itemId());
        assertTrue(receivedByA.isEmpty(), "a server must ignore its own events");
    }

    @Test
    void theSameEventDeliveredTwiceIsHandledOnce() throws Exception {
        var event = Envelope.removed("server-a", 5, null, null);
        busA.publish(event).join();
        busA.publish(event).join();

        await(receivedByB, 1);
        Thread.sleep(200);
        assertEquals(1, receivedByB.size());
    }

    @Test
    void eventsFromOnePublisherArriveInOrder() throws Exception {
        for (int id = 1; id <= 50; id++) busA.publish(Envelope.listed("server-a", id)).join();

        await(receivedByB, 50);
        assertEquals(50, receivedByB.size());
        for (int i = 0; i < 50; i++) assertEquals(i + 1, receivedByB.get(i).itemId());
    }

    @Test
    void junkOnTheChannelIsIgnored() throws Exception {
        redisA.publish(new Keys("ignored").channel(), "x"); // other channel: never reaches the bus
        busA.publish(Envelope.listed("server-a", 99)).join();
        await(receivedByB, 1);
        assertEquals(1, receivedByB.size());
    }
}
