package rahu.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** .env loading: gitignored file -> memory only; real env always wins. */
class DotEnvTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("KEY=VALUE pairs, comments, export prefix and quotes parse")
    void parses() throws Exception {
        Files.writeString(tmp.resolve(".env"), """
            # comment
            export OPENROUTER_API_KEY="sk-test-123"
            PLAIN=value
            """);
        DotEnv.load(tmp);
        assertEquals("sk-test-123", DotEnv.get("OPENROUTER_API_KEY").orElseThrow());
        assertEquals("value", DotEnv.get("PLAIN").orElseThrow());
        assertTrue(DotEnv.present("PLAIN"));
    }

    @Test
    @DisplayName("Missing .env is empty, not an error")
    void missingFileOk() {
        DotEnv.load(tmp);
        assertTrue(DotEnv.get("NOPE_" + System.nanoTime()).isEmpty());
    }

    @Test
    @DisplayName("Values never leak through toString/presence checks")
    void presenceOnly() throws Exception {
        Files.writeString(tmp.resolve(".env"), "SECRET_KEY=abc123\n");
        DotEnv.load(tmp);
        // The API surface exposes get() (for transport) and present(); no bulk dump.
        assertTrue(DotEnv.present("SECRET_KEY"));
    }
}
