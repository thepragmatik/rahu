package rahu.core;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A28: core must not reference adapter or CLI packages — dependency direction is
 * core ← adapters ← cli (ARCHITECTURE.md). Guards class-byte references.
 */
class CoreBoundaryTest {

    @Test
    void coreContainsNoAdapterOrCliReferences() throws IOException {
        Path classes = Path.of("target", "classes");
        if (!Files.isDirectory(classes)) {
            return; // not built yet; the reactor build always produces target/classes first
        }
        byte[] forbiddenAdapter = "rahu/openrouter".getBytes(StandardCharsets.UTF_8);
        byte[] forbiddenSystemOne = "rahu/systemone".getBytes(StandardCharsets.UTF_8);
        byte[] forbiddenCli = "rahu/cli".getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.walk(classes)) {
            files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                try {
                    byte[] bytes = Files.readAllBytes(p);
                    assertFalse(contains(bytes, forbiddenAdapter), () -> "adapter ref in " + p);
                    assertFalse(contains(bytes, forbiddenSystemOne), () -> "systemone ref in " + p);
                    assertFalse(contains(bytes, forbiddenCli), () -> "cli ref in " + p);
                } catch (IOException e) {
                    throw new IllegalStateException("unreadable class file: " + p, e);
                }
            });
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length > haystack.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
