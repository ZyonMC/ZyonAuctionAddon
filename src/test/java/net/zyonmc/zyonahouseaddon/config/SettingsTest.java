package net.zyonmc.zyonahouseaddon.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {

    private static YamlConfiguration valid() {
        var yaml = new YamlConfiguration();
        yaml.set("server-id", "survival-1");
        yaml.set("redis.host", "127.0.0.1");
        yaml.set("redis.port", 6379);
        yaml.set("redis.prefix", "zah");
        yaml.set("lock.lease-ms", 30000);
        return yaml;
    }

    @Test
    void aValidConfigurationLoads() throws Exception {
        var settings = Settings.load(valid());
        assertEquals("survival-1", settings.serverId());
        assertEquals(30_000, settings.lockLease().toMillis());
        assertEquals("127.0.0.1:6379/0", settings.redis().describe());
    }

    @Test
    void periodicResyncDefaultsToTenMinutesAndZeroDisablesIt() throws Exception {
        assertEquals(10, Settings.load(valid()).periodicResync().toMinutes());
        var yaml = valid();
        yaml.set("events.periodic-resync-minutes", 0);
        assertTrue(Settings.load(yaml).periodicResync().isZero());
    }

    @Test
    void thePlaceholderServerIdIsRefused() {
        var yaml = valid();
        yaml.set("server-id", "CHANGE_ME");
        assertThrows(InvalidSettingsException.class, () -> Settings.load(yaml));
        yaml.set("server-id", "");
        assertThrows(InvalidSettingsException.class, () -> Settings.load(yaml));
    }

    @Test
    void unsafeValuesAreRefused() {
        for (String[] bad : new String[][]{
                {"server-id", "has space"}, {"redis.prefix", "bad prefix!"}, {"redis.host", ""}}) {
            var yaml = valid();
            yaml.set(bad[0], bad[1]);
            assertThrows(InvalidSettingsException.class, () -> Settings.load(yaml), bad[0]);
        }
        for (Object[] bad : new Object[][]{
                {"redis.port", 0}, {"redis.port", 70000}, {"lock.lease-ms", 1000}, {"redis.timeout-ms", 10}, {"events.dedupe-window-seconds", 1}, {"events.periodic-resync-minutes", -1}}) {
            var yaml = valid();
            yaml.set((String) bad[0], bad[1]);
            assertThrows(InvalidSettingsException.class, () -> Settings.load(yaml), (String) bad[0]);
        }
    }

    @Test
    void theShippedConfigIsRejectedUntilTheServerIdIsSet() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            var shipped = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(stream));
            assertThrows(InvalidSettingsException.class, () -> Settings.load(shipped));
            shipped.set("server-id", "lobby-1");
            assertDoesNotThrow(() -> Settings.load(shipped));
        }
    }
}
