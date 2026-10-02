package rahu.core.privacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic local detectors (privacy.md): known high-risk patterns plus
 * operator-declared sensitive values. Detection is imperfect by design —
 * uncertainty blocks at the gate, and no claim of universal PII detection is
 * made anywhere. Canaries in tests are runtime-concatenated, never literals.
 */
public final class PrivacyScanner {

    /** High-risk pattern classes, compiled once. Order is irrelevant. */
    private static final List<Pattern> DETECTORS = List.of(
        // email-shaped identifiers
        Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        // key-shaped tokens (providers are named generically to avoid scanner echo)
        Pattern.compile("\\b(sk|pk|rk)-[A-Za-z0-9]{16,}\\b"),
        Pattern.compile("\\bghp_[A-Za-z0-9]{20,}\\b"),
        Pattern.compile("\\bAKIA[0-9A-Z]{12,}\\b"),
        // bearer/authorization material in text
        Pattern.compile("(?i)authorization[\":\\s]*bearer\\s+[A-Za-z0-9._-]{8,}"),
        // private key blocks
        Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        // password/secret assignments in config-like content
        Pattern.compile("(?i)(password|passwd|secret|api[_-]?key)\\s*[:=]\\s*\\S{6,}"),
        // long hex blobs (>=40) — probable credentials/signatures
        Pattern.compile("\\b[a-f0-9]{40,}\\b"));

    private PrivacyScanner() {
    }

    /** Scans content and returns findings (category + count, no raw values). */
    public static List<Finding> scan(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Pattern detector : DETECTORS) {
            var matcher = detector.matcher(content);
            int hits = 0;
            while (matcher.find()) {
                hits++;
            }
            if (hits > 0) {
                counts.merge(categoryOf(detector), hits, Integer::sum);
            }
        }
        List<Finding> findings = new ArrayList<>();
        counts.forEach((category, count) -> findings.add(new Finding(category, count)));
        return List.copyOf(findings);
    }

    private static String categoryOf(Pattern detector) {
        String src = detector.pattern();
        if (src.contains("@")) {
            return "identifier-email";
        }
        if (src.contains("PRIVATE KEY")) {
            return "private-key";
        }
        if (src.toLowerCase().contains("authorization")) {
            return "auth-header";
        }
        if (src.contains("password") || src.contains("secret") || src.contains("api")) {
            return "credential-assignment";
        }
        if (src.contains("AKIA") || src.contains("ghp_") || src.contains("(sk|pk|rk)")) {
            return "key-token";
        }
        return "hex-blob";
    }
}
