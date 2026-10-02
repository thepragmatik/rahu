package rahu.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S07 workspace tools (tools.md; A08, A09 semantics): bounded list/read/search. */
class WorkspaceToolsTest {

    @TempDir
    Path root;

    private WorkspaceTools tools() {
        return new WorkspaceTools(new PathBoundary(root));
    }

    @Test
    @DisplayName("workspace.list: sorted paths within depth bound; excludes skipped")
    void listSortedAndBounded() throws Exception {
        Files.createDirectories(root.resolve("src/main"));
        Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(root.resolve("src/Main.java"), "class Main {}");
        Files.writeString(root.resolve("src/main/App.java"), "class App {}");
        Files.writeString(root.resolve("target/classes/X.class"), "junk");

        ToolResult result = tools().list(".", 2);
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        String body = result.content();
        assertTrue(body.contains("src/Main.java"));
        assertTrue(body.contains("src/main/"));
        assertFalse_(body.contains("target"), "excluded dirs never appear");
    }

    private static void assertFalse_(boolean cond, String msg) {
        org.junit.jupiter.api.Assertions.assertFalse(cond, msg);
    }

    @Test
    @DisplayName("workspace.read: returns exact range and marks truncation explicitly")
    void readBoundedAndTruncated() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 3000; i++) {
            big.append("line ").append(i).append('\n');
        }
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/big.txt"), big.toString());

        ToolResult head = tools().read("docs/big.txt", 1, 5);
        assertEquals(ToolResult.Status.SUCCESS, head.status());
        assertTrue(head.content().contains("line 1"));
        assertTrue(head.content().contains("line 5"));
        assertFalse_(head.content().contains("line 6"), "range respected");

        ToolResult tail = tools().read("docs/big.txt", 2990, 3000);
        assertTrue(tail.content().contains("line 3000"));

        ToolResult all = tools().read("docs/big.txt", null, null);
        assertTrue(all.truncated(), "over-limit reads must report truncation");
        long contentLines = all.content().lines()
            .filter(l -> !l.startsWith("[truncated:")).count();
        assertTrue(contentLines <= 1000, "1000-line cap on file content");
    }

    @Test
    @DisplayName("workspace.search: literal only, bounded matches, skips exclusions")
    void searchLiteralBounded() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.java"), "findme here\nsecond findme line");
        Files.writeString(root.resolve("src/B.java"), "no match");
        Files.writeString(root.resolve("src/secret.pem"), "findme in key material");

        ToolResult result = tools().search("findme", null);
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertFalse_(result.content().contains("secret.pem"), "exclusions respected");
        assertTrue(result.content().contains("src/A.java"));
        assertTrue(result.content().contains("A.java:1") || result.content().contains("A.java:2"));
    }

    @Test
    @DisplayName("A08: invalid tool arguments are typed errors, never exceptions escaping")
    void invalidArgumentsTyped() throws Exception {
        assertEquals(ToolResult.Status.INVALID, tools().read("../escape.txt", null, null).status());
        assertEquals(ToolResult.Status.INVALID, tools().read(".env", null, null).status());
        assertEquals(ToolResult.Status.INVALID, tools().list(".", 9).status(),
            "depth above 3 is invalid");
        assertEquals(ToolResult.Status.INVALID, tools().search("x", "absolutely/nonexistent").status());
    }

    @Test
    @DisplayName("A09: repeated call ID with identical arguments reuses the outcome")
    void repeatedCallIdReusesOutcome() throws Exception {
        Files.writeString(root.resolve("a.txt"), "content");
        var registry = new ToolCallLog();
        var t = tools();

        ToolResult first = t.executeToolCall("call-1", "workspace.read",
            "{\"path\":\"a.txt\"}", registry);
        ToolResult second = t.executeToolCall("call-1", "workspace.read",
            "{\"path\":\"a.txt\"}", registry);
        assertEquals(first.content(), second.content());
        assertTrue(second.reused(), "identical repeat must be served from the log");

        assertThrows(ToolCallLog.ProtocolError.class, () -> t.executeToolCall("call-1",
            "workspace.read", "{\"path\":\"a.txt\",\"lines\":2}", registry),
            "same ID with changed arguments is a protocol error");
    }
}
