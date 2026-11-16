package net.zyonmc.zyonahouseaddon.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {

    @Test
    void aConfiguredTextOverridesTheDefault() {
        var yaml = new YamlConfiguration();
        yaml.set("messages.item-sold", "<red>vendido <item>");

        var messages = Messages.load(yaml.getConfigurationSection("messages"));

        assertEquals("<red>vendido <item>", messages.get(MessageKey.ITEM_SOLD));
        assertEquals(MessageKey.ITEM_EXPIRED.fallback(), messages.get(MessageKey.ITEM_EXPIRED), "untouched keys keep their default");
    }

    @Test
    void aMissingSectionStillGivesEveryText() {
        var messages = Messages.load(null);
        for (MessageKey key : MessageKey.values()) assertNotNull(messages.get(key), key.name());
    }

    @Test
    void everyKeyIsDocumentedInTheShippedConfigWithItsDefault() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            var shipped = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
            for (MessageKey key : MessageKey.values()) {
                assertEquals(key.fallback(), shipped.getString("messages." + key.path()),
                        "config.yml must list " + key.path() + " with the same default as the code");
            }
        }
    }
}
