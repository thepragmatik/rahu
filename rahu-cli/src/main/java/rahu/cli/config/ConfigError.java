package rahu.cli.config;

/** Actionable configuration error carrying the field path and next action (cli.md). */
public final class ConfigError extends RuntimeException {

    public ConfigError(String message) {
        super(message);
    }
}
