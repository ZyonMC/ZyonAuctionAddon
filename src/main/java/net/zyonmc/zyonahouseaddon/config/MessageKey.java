package net.zyonmc.zyonahouseaddon.config;

/** Every player-facing text of the addon, its {@code config.yml} path (under {@code messages:}) and its default. */
public enum MessageKey {
    PREFIX("prefix", "<gradient:#f5a623:#f8d66d><bold>Auction</bold></gradient> <dark_gray>»</dark_gray> "),
    /** Placeholders: item, buyer, price. */
    ITEM_SOLD("item-sold", "<green>Your <white><item></white> was bought by <white><buyer></white> for <gold><price></gold>."),
    /** Placeholder: item. */
    ITEM_EXPIRED("item-expired", "<yellow>Your listing <white><item></white> is no longer on sale. Claim it in the auction house.</yellow>"),
    ADMIN_ONLY("admin-only", "<red>You do not have permission."),
    USAGE("usage", "<gray>Usage: /zahredis [status|resync|reload]"),
    /** Placeholders: server, redis, mode. */
    STATUS_HEADER("status-header", "<gray>Server <white><server></white> · Redis: <redis> · Mode: <mode>"),
    STATUS_CONNECTED("status-connected", "<green>connected</green>"),
    STATUS_DOWN("status-down", "<red>DOWN (operations are refused)</red>"),
    STATUS_MODE("status-mode", "distributed"),
    RESYNC_STARTED("resync-started", "<yellow>Resync started on every server...</yellow>"),
    RESYNC_DONE("resync-done", "<green>Resync done.</green>"),
    RESYNC_FAILED("resync-failed", "<red>Resync failed, see the console.</red>"),
    RELOAD_STARTED("reload-started", "<yellow>Reloading zAuctionHouse on every server...</yellow>"),
    RELOAD_DONE("reload-done", "<green>Reload sent to every server.</green>"),
    RELOAD_FAILED("reload-failed", "<red>Reload failed, see the console.</red>");

    private final String path;
    private final String fallback;

    MessageKey(String path, String fallback) {
        this.path = path;
        this.fallback = fallback;
    }

    public String path() {
        return this.path;
    }

    public String fallback() {
        return this.fallback;
    }
}
