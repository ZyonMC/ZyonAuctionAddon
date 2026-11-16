package net.zyonmc.zyonahouseaddon.config;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Validated, immutable view of {@code config.yml}. Anything wrong is rejected at load time so the
 * addon never runs half-configured (a wrong lock lease or a duplicated server id breaks the
 * anti-duplication guarantees).
 */
public record Settings(String serverId, Redis redis, Duration lockLease, Duration terminalStateTtl,
                       Duration dedupeWindow, Duration periodicResync, boolean debug, Messages messages) {

    private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_.:-]{1,32}");
    private static final String PLACEHOLDER_ID = "CHANGE_ME";
    private static final long MIN_LEASE_MS = 5_000;

    public record Redis(String host, int port, String user, String password, int database, boolean ssl,
                        Duration timeout, String prefix) {

        /** Redis settings without the secret, safe to print in logs. */
        public String describe() {
            return host + ":" + port + "/" + database + (ssl ? " (ssl)" : "");
        }
    }

    public static Settings load(ConfigurationSection config) throws InvalidSettingsException {
        String serverId = config.getString("server-id", "").trim();
        if (serverId.isEmpty() || serverId.equalsIgnoreCase(PLACEHOLDER_ID)) {
            throw new InvalidSettingsException("server-id is not set. Give each server a UNIQUE id in config.yml.");
        }
        if (!SERVER_ID.matcher(serverId).matches()) {
            throw new InvalidSettingsException("server-id '" + serverId + "' is invalid (allowed: letters, digits, _ . -, max 64).");
        }

        var r = config.getConfigurationSection("redis");
        if (r == null) {
            throw new InvalidSettingsException("The 'redis' section is missing.");
        }
        String host = r.getString("host", "").trim();
        int port = r.getInt("port", 6379);
        int database = r.getInt("database", 0);
        long timeoutMs = r.getLong("timeout-ms", 3000);
        String prefix = r.getString("prefix", "zah").trim();
        if (host.isEmpty()) throw new InvalidSettingsException("redis.host is empty.");
        if (port < 1 || port > 65535) throw new InvalidSettingsException("redis.port must be between 1 and 65535.");
        if (database < 0) throw new InvalidSettingsException("redis.database must be >= 0.");
        if (timeoutMs < 250) throw new InvalidSettingsException("redis.timeout-ms must be >= 250.");
        if (!PREFIX.matcher(prefix).matches()) {
            throw new InvalidSettingsException("redis.prefix '" + prefix + "' is invalid (allowed: letters, digits, _ . : -, max 32).");
        }
        var redis = new Redis(host, port, r.getString("user", ""), r.getString("password", ""), database,
                r.getBoolean("ssl", false), Duration.ofMillis(timeoutMs), prefix);

        long leaseMs = config.getLong("lock.lease-ms", 30_000);
        if (leaseMs < MIN_LEASE_MS) {
            throw new InvalidSettingsException("lock.lease-ms must be >= " + MIN_LEASE_MS + " (a short lease expires mid-operation).");
        }
        long ttlHours = config.getLong("lock.terminal-state-ttl-hours", 720);
        if (ttlHours < 1) throw new InvalidSettingsException("lock.terminal-state-ttl-hours must be >= 1.");
        long dedupeSeconds = config.getLong("events.dedupe-window-seconds", 120);
        if (dedupeSeconds < 10) throw new InvalidSettingsException("events.dedupe-window-seconds must be >= 10.");


        long resyncMinutes = config.getLong("events.periodic-resync-minutes", 10);
        if (resyncMinutes < 0) throw new InvalidSettingsException("events.periodic-resync-minutes must be >= 0 (0 disables it).");

        return new Settings(serverId, redis, Duration.ofMillis(leaseMs), Duration.ofHours(ttlHours),
                Duration.ofSeconds(dedupeSeconds), Duration.ofMinutes(resyncMinutes), config.getBoolean("debug", false),
                Messages.load(config.getConfigurationSection("messages")));
    }
}
