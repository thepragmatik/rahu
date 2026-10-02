package rahu.core.codeintel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A dependency-free LSP client for jdtls, spoken over stdio.
 *
 * <p>Surefire runs this class from the MODULE directory, so the workspace root is
 * found by walking up for a marker that only the repo root has, not by assuming
 * {@code user.dir} is the root.
 */
class JdtlsClientTest {

    /** Walks up from {@code user.dir} until it finds the repo root marker. */
    static Path workspaceRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("pom.xml")) && Files.isDirectory(p.resolve("rahu-core"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException(
                "repo root not found above user.dir=" + System.getProperty("user.dir"));
    }

    // ---- framing: pure, no server required ----

    @Test
    void framesEncodeAsContentLengthHeaderThenBody() {
        String body = "{\"jsonrpc\":\"2.0\"}";
        String frame = LspFrame.encode(body);
        assertTrue(frame.startsWith("Content-Length: " + body.length() + "\r\n\r\n"),
                "expected a Content-Length header, got: " + frame);
        assertTrue(frame.endsWith(body), "body must follow the blank line");
    }

    @Test
    void contentLengthCountsBytesNotCharacters() {
        // A non-ASCII payload is shorter in chars than in bytes. A char count here
        // truncates every following frame on the pipe.
        String body = "{\"q\":\"\u00e9\u00e9\"}";
        String frame = LspFrame.encode(body);
        String declared = frame.substring("Content-Length: ".length(), frame.indexOf("\r\n"));
        assertEquals(Integer.toString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length), declared,
                "Content-Length must be the UTF-8 BYTE count");
        assertEquals(body, LspFrame.decodeBody(frame));
    }

    @Test
    void frameParsesBodyLengthFromHeader() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1}";
        String frame = LspFrame.encode(body);
        assertEquals(body, LspFrame.decodeBody(frame),
                "a round-tripped frame must decode to the exact body");
    }

    @Test
    void decodeRejectsATruncatedBody() {
        String frame = LspFrame.encode("{\"jsonrpc\":\"2.0\",\"id\":1}");
        String truncated = frame.substring(0, frame.length() - 5);
        IllegalArgumentException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalArgumentException.class, () -> LspFrame.decodeBody(truncated));
        assertTrue(e.getMessage().contains("truncated"),
                "the error must say truncated, got: " + e.getMessage());
    }

    // ---- minimal JSON codec (rahu-core is JDK-only; there is no Jackson here) ----

    @Test
    void jsonCodecRoundTripsANestedRequest() {
        String json = LspJson.write(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "workspace/symbol",
                "params", Map.of("query", "DecisionEngine")));
        assertTrue(json.startsWith("{"), "an object must serialise as an object: " + json);
        assertTrue(json.contains("\"method\":\"workspace/symbol\""),
                "nested values must survive: " + json);

        Map<String, Object> back = LspJson.parseObject(json);
        assertEquals("2.0", back.get("jsonrpc"));
        assertEquals(1L, ((Number) back.get("id")).longValue(), "integers must parse as numbers");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) back.get("params");
        assertEquals("DecisionEngine", params.get("query"));
    }

    @Test
    void jsonCodecHandlesNullAndEmptyArray() {
        Map<String, Object> back = LspJson.parseObject("{\"r\":null,\"a\":[]}");
        assertTrue(back.containsKey("r"), "an explicit null key must survive");
        assertEquals(null, back.get("r"));
        assertEquals(List.of(), back.get("a"));
    }

    @Test
    void jsonCodecEscapesQuotesAndBackslashes() {
        String encoded = LspJson.write(Map.of("q", "a\"b\\c"));
        assertTrue(encoded.contains("\\\""), "a quote must be escaped: " + encoded);
        assertTrue(encoded.contains("\\\\"), "a backslash must be escaped: " + encoded);
        assertEquals("a\"b\\c", LspJson.parseObject(encoded).get("q"));
    }

    @Test
    void jsonCodecRejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> LspJson.parseObject("{\"unterminated\": "),
                "malformed JSON must throw, not return null");
    }

    // ---- response matching ----

    @Test
    void responseMatcherSelectsTheFrameCarryingOurId() {
        // jdtls interleaves notifications and its own requests with our replies.
        String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"window/logMessage\",\"params\":{}}";
        String ours = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}";
        String all = notification + "\u0000" + ours;
        String matched = LspResponse.findById(all, 7L);
        assertTrue(matched.contains("\"ok\":true"), "must pick our frame, got: " + matched);
        assertFalse(matched.contains("logMessage"), "must not return the notification");
    }

    @Test
    void responseMatcherReturnsNullWhenNoFrameCarriesTheId() {
        String all = "{\"jsonrpc\":\"2.0\",\"method\":\"window/logMessage\"}";
        assertEquals(null, LspResponse.findById(all, 99L),
                "an absent id must be distinguishable from an error frame");
    }

    @Test
    void responseMatcherAcceptsAStringId() {
        // LSP ids may arrive as strings; a numeric-only match would miss these.
        String ours = "{\"jsonrpc\":\"2.0\",\"id\":\"7\",\"error\":{\"code\":-32601}}";
        assertTrue(LspResponse.findById(ours, 7L).contains("\"error\""),
                "a quoted id must still match its numeric request id");
    }
}