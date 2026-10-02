package rahu.cli.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal .env loader (KEY=VALUE lines, # comments, optional export prefix).
 * The file is gitignored by the repo; values land in process memory only and
 * are never logged, traced, or echoed. Lookup order: real environment first,
 * then .env — so a shell export always wins.
 */
public final class DotEnv {

    private static volatile Map<String, String> cached = Map.of();

    private DotEnv() {
    }

    /** Loads (or reloads) .env from the given directory; missing file = empty. */
    public static synchronized void load(Path directory) {
        Path file = directory.resolve(".env");
        Map<String, String> out = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    if (line.startsWith("export ")) {
                        line = line.substring(7).strip();
                    }
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = line.substring(0, eq).strip();
                    String value = line.substring(eq + 1).strip();
                    if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    out.put(key, value);
                }
            } catch (IOException e) {
                throw new ConfigError(".env present but unreadable; fix permissions");
            }
        }
        cached = Map.copyOf(out);
    }

    /** Environment-first lookup; .env is the fallback, never the override. */
    public static Optional<String> get(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(fromEnv);
        }
        return Optional.ofNullable(cached.get(key));
    }

    /** Presence check only — never returns or logs the value. */
    public static boolean present(String key) {
        return get(key).isPresent();
    }
}
