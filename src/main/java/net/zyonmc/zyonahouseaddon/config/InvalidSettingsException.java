package net.zyonmc.zyonahouseaddon.config;

/** The addon configuration is unusable: the addon must not start with it. */
public final class InvalidSettingsException extends Exception {

    public InvalidSettingsException(String message) {
        super(message);
    }
}
