package net.zyonmc.zyonahouseaddon.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.Map;

/**
 * The editable texts. A key missing from {@code config.yml} (an older file, a typo) falls back to its
 * default, so editing messages can never leave the addon without a text.
 */
public record Messages(Map<MessageKey, String> values) {

    public Messages {
        values = Map.copyOf(values);
    }

    public static Messages load(ConfigurationSection section) {
        Map<MessageKey, String> values = new EnumMap<>(MessageKey.class);
        for (MessageKey key : MessageKey.values()) {
            values.put(key, section == null ? key.fallback() : section.getString(key.path(), key.fallback()));
        }
        return new Messages(values);
    }

    public String get(MessageKey key) {
        return this.values.get(key);
    }
}
