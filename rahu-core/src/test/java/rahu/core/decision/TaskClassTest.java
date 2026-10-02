package rahu.core.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Labels v1 (systemone.md): unknown is the only safe default. */
class TaskClassTest {

    @Test
    @DisplayName("The six v1 labels parse case-insensitively")
    void labelsParse() {
        assertEquals(TaskClass.ANSWER, TaskClass.fromLabel("answer"));
        assertEquals(TaskClass.CODING, TaskClass.fromLabel("CODING"));
        assertEquals(TaskClass.ANALYSIS, TaskClass.fromLabel("analysis"));
        assertEquals(TaskClass.CLASSIFICATION, TaskClass.fromLabel("classification"));
        assertEquals(TaskClass.SUMMARISATION, TaskClass.fromLabel("summarisation"));
        assertEquals(TaskClass.UNKNOWN, TaskClass.fromLabel("unknown"));
    }

    @Test
    @DisplayName("Anything unrecognised degrades to UNKNOWN, never to a trusted class")
    void unrecognisedIsUnknown() {
        assertEquals(TaskClass.UNKNOWN, TaskClass.fromLabel("code-review"));
        assertEquals(TaskClass.UNKNOWN, TaskClass.fromLabel(""));
        assertEquals(TaskClass.UNKNOWN, TaskClass.fromLabel(null));
    }

    @Test
    @DisplayName("UNKNOWN is the least trusting class (never widens requirements)")
    void unknownIsLeastTrusting() {
        assertEquals(false, TaskClass.UNKNOWN.isConfident());
        assertEquals(true, TaskClass.CODING.isConfident());
    }
}
