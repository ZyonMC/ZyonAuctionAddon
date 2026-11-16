package net.zyonmc.zyonahouseaddon;

import fr.maxlego08.zauctionhouse.api.item.Item;
import net.zyonmc.zyonahouseaddon.config.Settings;
import net.zyonmc.zyonahouseaddon.redis.RedisConnection;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Assumptions;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

/** Integration tests need a real Redis: -Dzah.test.redis=host:port or env ZAH_TEST_REDIS. Without it they are skipped. */
public final class RedisTestSupport {

    private RedisTestSupport() {
    }

    public static String uniquePrefix() {
        return "zahtest" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static RedisConnection connectOrSkip() {
        String target = System.getProperty("zah.test.redis", System.getenv("ZAH_TEST_REDIS"));
        Assumptions.assumeTrue(target != null && !target.isBlank(), "Set ZAH_TEST_REDIS=host:port to run the Redis integration tests");
        String[] parts = target.split(":");
        var settings = new Settings.Redis(parts[0], Integer.parseInt(parts[1]), "", "", 0, false, Duration.ofSeconds(2), "zahtest");
        return RedisConnection.connect(settings, Logger.getLogger("test"));
    }

    /** A minimal Item: only what the bridge reads. Any other call fails loudly. */
    public static Item item(int id, UUID seller) {
        return (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[]{Item.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getId" -> id;
            case "getSellerUniqueId" -> seller;
            case "getItemDisplay" -> "Diamond Sword";
            case "getFormattedPrice" -> "$100";
            case "hashCode" -> id;
            case "equals" -> proxy == args[0];
            case "toString" -> "Item#" + id;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    public static Player player(String name) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "hashCode" -> name.hashCode();
            case "equals" -> proxy == args[0];
            case "toString" -> "Player[" + name + "]";
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
}
