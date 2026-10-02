package rahu.core.codeintel;

import java.nio.charset.StandardCharsets;

/**
 * LSP base-protocol framing: {@code Content-Length: N\r\n\r\n<json>}.
 *
 * <p>Split out from {@link LspSession} so the wire format is testable without a
 * live server process — the protocol is easy to get subtly wrong and expensive to
 * debug through a pipe.
 */
final class LspFrame {

    private LspFrame() {}

    /** Encodes one message: a Content-Length header, a blank line, then the body. */
    static String encode(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        // Length is a BYTE count, not a char count: a non-ASCII payload would
        // otherwise under-report and truncate the next frame the reader sees.
        return "Content-Length: " + bytes.length + "\r\n\r\n" + body;
    }

    /**
     * Extracts the body declared by this frame's Content-Length header.
     *
     * @throws IllegalArgumentException if the header is absent or the body is
     *     shorter than declared — a silent short read would otherwise be parsed as
     *     a whole, wrong message.
     */
    static String decodeBody(String frame) {
        int sep = frame.indexOf("\r\n\r\n");
        if (sep < 0) {
            throw new IllegalArgumentException("LSP frame has no header/body separator: " + preview(frame));
        }
        String header = frame.substring(0, sep);
        String body = frame.substring(sep + 4);
        int declared = -1;
        for (String line : header.split("\r\n")) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                declared = Integer.parseInt(line.substring("Content-Length:".length()).trim());
            }
        }
        if (declared < 0) {
            throw new IllegalArgumentException("LSP frame has no Content-Length header: " + preview(header));
        }
        // The header counts BYTES, so the cut must be made on bytes. Cutting on
        // characters instead overruns the string as soon as the body is non-ASCII:
        // a byte count exceeds the char count, and codePointCount(0, declared)
        // then walks off the end.
        byte[] all = body.getBytes(StandardCharsets.UTF_8);
        if (all.length < declared) {
            throw new IllegalArgumentException(
                    "LSP frame truncated: declared " + declared + " bytes, got " + all.length);
        }
        return new String(all, 0, declared, StandardCharsets.UTF_8);
    }

    private static String preview(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }
}