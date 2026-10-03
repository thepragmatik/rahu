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

    @Test
    @DisplayName("tools.resultBytes is a real limit, not a decorative config key")
    void configuredResultBytesIsEnforced() throws Exception {
        // tools.resultBytes was parsed, schema-exposed and NEVER APPLIED: the read
        // cap was the hardcoded constant MAX_READ_BYTES, so an operator setting
        // tools.resultBytes=2048 got the 64 KiB cap and no warning. This audit had
        // recorded the key as "blocked on a spec decision" for five increments; the
        // spec had in fact decided it twice -
        // configuration.md:24 lists `resultBytes` as a `tools` key, and tools.md:5
        // requires a descriptor to carry a "result byte limit".
        String big = "x".repeat(5000);
        Files.writeString(root.resolve("big.txt"), big);

        // A configured limit SMALLER than the file must bite.
        var small = new WorkspaceTools(new PathBoundary(root), 2048);
        var r = small.read("big.txt", null, null);
        assertEquals(ToolResult.Status.SUCCESS, r.status());
        assertTrue(r.truncated(),
            "a 5000-byte file under a configured 2048-byte limit must report truncation");
        assertTrue(r.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= 2048 + 256,
            "the returned content must respect the configured limit, not the "
                + "built-in 64 KiB one: got "
                + r.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(r.content().contains("[truncated:"),
            "truncation must be disclosed: " + r.content());

        // And a limit LARGER than the file must NOT truncate.
        var large = new WorkspaceTools(new PathBoundary(root), 1024 * 1024);
        var ok = large.read("big.txt", null, null);
        assertEquals(false, ok.truncated(),
            "a file inside a larger configured limit must come back whole");
        assertTrue(ok.content().contains(big), "and must not be cut short");

        // The default must remain 64 KiB, or every existing bound changes meaning.
        var defaulted = new WorkspaceTools(new PathBoundary(root));
        assertEquals(false, defaulted.read("big.txt", null, null).truncated(),
            "the default limit leaves a 5000-byte file intact");
    }

    @Test
    @DisplayName("tools.resultBytes bounds search results too, and the match cap stays separate")
    void configuredResultBytesBoundsSearchResults() throws Exception {
        // tools.md:5 says "a RESULT byte limit", so it bounds every result, not
        // just read. And the per-file SCAN bound must stay independent: if a small
        // result cap also shrank how much of a file the search read, a search would
        // stop finding text it was entitled to match and report a clean "no
        // matches" - absence of evidence presented as evidence of absence.
        Files.writeString(root.resolve("hits.txt"),
            "needle one\n".repeat(400));

        var roomy = new WorkspaceTools(new PathBoundary(root), 1024 * 1024);
        var many = roomy.search("needle", ".");
        assertTrue(many.content().contains("[truncated: match limit"),
            "400 matches must hit the 100-match cap: " + many.content().length());

        var tiny = new WorkspaceTools(new PathBoundary(root), 300);
        var few = tiny.search("needle", ".");
        int bytes = few.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(few.truncated(), "a search result over the configured cap truncates");
        assertTrue(bytes <= 300 + 256,
            "the search result must respect tools.resultBytes, got " + bytes);
        assertTrue(few.content().contains("[truncated:"),
            "and must say so rather than return a short result that looks complete");

        // THE DISTINGUISHING INPUT. Above, the result already tripped the 100-MATCH
        // cap, so truncated() and the marker were true for reasons that had nothing
        // to do with the byte cap - which is why a mutation removing the byte cap
        // from search survived. This case has FEW matches (under the match cap) whose
        // rendered lines are nonetheless over the byte cap. Only here is the byte
        // cap the sole reason for truncation.
        // In its own directory: hits.txt is still present from the first half of this
        // test, and searching "." would include its 400 matches. Confining the probe
        // is what makes the match count 2.
        Path fewDir = Files.createDirectories(root.resolve("fewonly"));
        Files.writeString(fewDir.resolve("few.txt"),
            "needle " + "w".repeat(900) + "\nneedle " + "w".repeat(900) + "\n");
        var fewRoomy = new WorkspaceTools(new PathBoundary(root), 1024 * 1024);
        var unfettered = fewRoomy.search("needle", "fewonly");
        assertTrue(!unfettered.truncated(),
            "two matches is under the match cap and a 1 MiB cap is far above 1.8 KiB, "
                + "so this must NOT be truncated: " + unfettered.content().length());
        int unfetteredBytes = unfettered.content()
            .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        var byteCapped = new WorkspaceTools(new PathBoundary(root), 400);
        var capped = byteCapped.search("needle", "fewonly");
        int cappedBytes = capped.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(cappedBytes <= 400 + 256,
            "the search byte cap must bite even when the match cap does not: got "
                + cappedBytes + " of an unfettered " + unfetteredBytes);
        assertTrue(capped.content().contains("tools.resultBytes"),
            "and the disclosure must name the BYTE cap, not the match cap: "
                + capped.content());
        // The FLAG, not just the bytes. A mutation that drops byteTruncated from the
        // returned boolean leaves the body already capped, so every byte assertion
        // above still passes - while truncated() reports FALSE for a result that was
        // visibly cut. That flag is what the caller records and what the trace
        // carries, so a false "complete" on a truncated result is the unobserved-as-
        // whole defect one level down.
        assertTrue(capped.truncated(),
            "a result cut by the byte cap must report truncated=true even when the "
                + "match cap did not fire");

        // The scan bound is independent: with a generous result cap the search must
        // still find matches in a file far larger than a tiny result cap.
        var probe = new WorkspaceTools(new PathBoundary(root), 1024 * 1024);
        assertTrue(probe.search("needle", ".").content().contains("hits.txt"),
            "a small result cap must not stop the search reading enough to match");
    }

    @Test
    @DisplayName("tools.resultBytes bounds list results too")
    void configuredResultBytesBoundsListResults() throws Exception {
        // The same rule as read and search: 500 paths can exceed a small cap, and a
        // list that comes back short with no marker reads as "that is all there is".
        for (int i = 0; i < 40; i++) {
            Files.writeString(root.resolve("f" + i + ".txt"), "x");
        }
        var tiny = new WorkspaceTools(new PathBoundary(root), 200);
        var r = tiny.list(".", 2);
        int bytes = r.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(r.truncated(), "an oversized list must report truncation");
        assertTrue(bytes <= 200 + 256, "list result must respect the cap, got " + bytes);
        assertTrue(r.content().contains("[truncated:"),
            "and must disclose it: " + r.content());
    }

    @Test
    @DisplayName("a nonsensical resultBytes is rejected at bind time, not clamped silently")
    void nonPositiveResultBytesIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new WorkspaceTools(new PathBoundary(root), 0),
            "a zero byte limit would silently truncate every result to nothing");
        assertThrows(IllegalArgumentException.class,
            () -> new WorkspaceTools(new PathBoundary(root), -1));
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
