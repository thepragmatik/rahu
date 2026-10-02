package rahu.core.codeintel;

import java.util.List;
import java.util.Map;

/**
 * Picks our reply out of a jdtls stream.
 *
 * <p>A language server interleaves three kinds of message on the same pipe:
 * notifications (no {@code id}), its own requests to us (an {@code id} we never
 * sent), and our replies. Returning the first frame read would hand back a log
 * message and silently answer the wrong question, so selection is by id.
 */
final class LspResponse {

    private LspResponse() {}

    /**
     * Returns the frame whose {@code id} equals {@code id}, or null if none does.
     *
     * <p>A null return means "not yet" — the caller keeps reading — and is kept
     * distinct from an error frame, which matches by id and carries {@code "error"}.
     *
     * <p>Framing in production is handled by {@link LspSession}; a NUL separator is
     * accepted here so several already-decoded frames can be offered at once.
     */
    static String findById(String frames, long id) {
        for (String candidate : candidates(frames)) {
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!matchesId(trimmed, id)) {
                continue;
            }
            return trimmed;
        }
        return null;
    }

    private static List<String> candidates(String frames) {
        // A single frame contains no NUL; a batch joined for testing does.
        return frames.indexOf('\0') < 0 ? List.of(frames) : List.of(frames.split("\0", -1));
    }

    /** True when this frame's id equals the given request id, quoted or not. */
    private static boolean matchesId(String frame, long id) {
        try {
            Map<String, Object> obj = LspJson.parseObject(frame);
            Object got = obj.get("id");
            if (got == null) {
                return false; // a notification carries no id
            }
            if (got instanceof Number n) {
                return n.longValue() == id;
            }
            // LSP permits a string id; a numeric-only match would miss these.
            return String.valueOf(id).equals(got.toString());
        } catch (RuntimeException e) {
            return false; // unparseable frame: not our reply, keep reading
        }
    }
}