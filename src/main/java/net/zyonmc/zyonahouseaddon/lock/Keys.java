package net.zyonmc.zyonahouseaddon.lock;

/** Redis key and channel names, all under the configured prefix. */
public final class Keys {

    private final String prefix;

    public Keys(String prefix) {
        this.prefix = prefix;
    }

    public String lock(int itemId) {
        return this.prefix + ":lock:" + itemId;
    }

    public String terminal(int itemId) {
        return this.prefix + ":term:" + itemId;
    }

    public String channel() {
        return this.prefix + ":bus";
    }
}
