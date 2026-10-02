package rahu.cli.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The repo's shipped suites must parse with the strict SuiteV1 parser. */
class ShippedSuitesParseTest {

    private static Path suite(String name) {
        // Surefire's working dir is the module dir; the repo root is its parent.
        Path moduleDir = Path.of(System.getProperty("user.dir"));
        Path repoRoot = moduleDir.resolve("docs").toFile().exists()
            ? moduleDir : moduleDir.getParent();
        return repoRoot.resolve("docs/evals/suites/" + name);
    }

    @Test
    @DisplayName("smoke-v1.json parses: 6 tasks, unique ids")
    void smokeSuiteParses() {
        SuiteV1 suite = SuiteV1.load(suite("smoke-v1.json"));
        assertEquals("smoke-v1", suite.id());
        assertEquals(6, suite.tasks().size());
    }

    @Test
    @DisplayName("dogfood-alpha-v1.json parses: 3 tasks incl. multi-turn and fixture task")
    void dogfoodSuiteParses() {
        SuiteV1 suite = SuiteV1.load(suite("dogfood-alpha-v1.json"));
        assertEquals("dogfood-alpha-v1", suite.id());
        assertEquals(3, suite.tasks().size());
        assertTrue(suite.tasks().stream().anyMatch(t -> t.isMultiTurn()),
            "repository-followup task is multi-turn");
    }
}
