package rahu.core.privacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A model-visible safe view bound to immutable source bytes (privacy.md).
 * Hash, provenance and content come from the same snapshot; approval binds to
 * the exact hash — any source mutation invalidates it (A34).
 */
public record SafeView(String id, Provenance provenance, String sha256, String content) {

    public SafeView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(content, "content");
        sha256 = sha256Of(content);
    }

    /** Convenience factory computing the hash itself. */
    public static SafeView of(String id, Provenance provenance, String content) {
        return new SafeView(id, provenance, sha256Of(content), content);
    }

    public static String sha256Of(String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
