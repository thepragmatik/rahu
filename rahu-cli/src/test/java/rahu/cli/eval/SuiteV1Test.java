package rahu.cli.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Suite v1 parsing/validation (artifacts.md Evaluation suite v1). */
class SuiteV1Test {

    @TempDir
    Path tmp;

    private Path write(String json) throws Exception {
        Path p = tmp.resolve("suite.json");
        Files.writeString(p, json);
        return p;
    }

    private static final String VALID = """
        {
          "schemaVersion": 1,
          "id": "smoke-v1",
          "purpose": "seed cases",
          "tasks": [
            {"id": "t1", "kind": "answer", "prompt": "Explain virtual threads.",
             "rubric": ["Explains benefit"], "tools": []},
            {"id": "t2", "kind": "analysis", "turns": ["q1", "q2"],
             "rubric": ["Keeps context"]}
          ]
        }
        """;

    @Test
    @DisplayName("Valid suite parses: unique ids, prompt or turns, rubric required")
    void validSuiteParses() throws Exception {
        SuiteV1 suite = SuiteV1.load(write(VALID));
        assertEquals("smoke-v1", suite.id());
        assertEquals(2, suite.tasks().size());
        assertEquals("Explain virtual threads.", suite.tasks().get(0).prompt());
        assertEquals(2, suite.tasks().get(1).turns().size());
    }

    @Test
    @DisplayName("Duplicate task ids / missing rubric fail with a path")
    void invalidSuitesFail() throws Exception {
        var dup = VALID.replace("\"id\": \"t2\"", "\"id\": \"t1\"");
        var ex = assertThrows(EvalError.class, () -> SuiteV1.load(write(dup)));
        assertTrue(ex.getMessage().contains("tasks[1].id"));

        var noRubric = VALID.replace("\"rubric\": [\"Explains benefit\"], ", "");
        var ex2 = assertThrows(EvalError.class, () -> SuiteV1.load(write(noRubric)));
        assertTrue(ex2.getMessage().contains("tasks[0].rubric"));
    }

    @Test
    @DisplayName("A task must have exactly one of prompt or nonempty turns")
    void promptXorTurns() throws Exception {
        // turn t1 gains "turns" while keeping its prompt -> invalid (both present)
        var both = VALID.replace(
            "\"prompt\": \"Explain virtual threads.\",",
            "\"prompt\": \"x\", \"turns\": [\"a\"],");
        var ex = assertThrows(EvalError.class, () -> SuiteV1.load(write(both)));
        assertTrue(ex.getMessage().contains("tasks[0]"));

        // t1 loses its prompt entirely -> invalid (neither present)
        var neither = VALID.replace("\"prompt\": \"Explain virtual threads.\",", "");
        assertThrows(EvalError.class, () -> SuiteV1.load(write(neither)));
    }

    @Test
    @DisplayName("Unknown fields are rejected (strict artifacts.md shape)")
    void unknownFieldRejected() throws Exception {
        var bad = VALID.replace("\"purpose\": \"seed cases\",",
            "\"purpose\": \"seed cases\", \"bogus\": 1,");
        var ex = assertThrows(EvalError.class, () -> SuiteV1.load(write(bad)));
        assertTrue(ex.getMessage().contains("bogus"));
    }
}
