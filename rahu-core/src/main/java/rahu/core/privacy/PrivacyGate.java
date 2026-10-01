package rahu.core.privacy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outbound privacy gate (privacy.md; A33/A34). Initial admission before any
 * classification or reservation; final recheck on the exact serialised body
 * immediately before transport. Unknown/restricted fail closed. No bypass
 * exists; fallback cannot carry blocked content.
 */
public final class PrivacyGate {

    /** Sealed admission outcome. */
    public sealed interface Decision {
        record Admitted() implements Decision {
        }

        /** Safe reason code + category; never the offending value. */
        record Blocked(String reasonCode, String category) implements Decision {

            public Blocked {
                Objects.requireNonNull(reasonCode, "reasonCode");
                Objects.requireNonNull(category, "category");
            }
        }
    }

    public static final String TERMINAL_REASON = "PRIVACY_BLOCKED";

    /** Initial admission of a safe view before classification/reservation. */
    public Decision admitForDecision(SafeView view) {
        if (view.provenance() instanceof Provenance.Restricted r) {
            return new Decision.Blocked(TERMINAL_REASON, "restricted:" + r.reasonCode());
        }
        if (view.provenance() instanceof Provenance.Unknown) {
            return new Decision.Blocked(TERMINAL_REASON, "unknown-provenance");
        }
        List<Finding> findings = PrivacyScanner.scan(view.content());
        if (!findings.isEmpty()) {
            return new Decision.Blocked(TERMINAL_REASON,
                "protected-content:" + findings.get(0).category());
        }
        return new Decision.Admitted();
    }

    /**
     * Final recheck on the exact serialised outbound body before transport
     * (A33). Runs even when initial admission passed: adapter-added fields,
     * tool results and generated content are re-scanned here.
     */
    public Decision admitDispatch(String serialisedBody, String endpoint) {
        List<Finding> findings = PrivacyScanner.scan(serialisedBody);
        if (!findings.isEmpty()) {
            return new Decision.Blocked(TERMINAL_REASON,
                "dispatch-body:" + findings.get(0).category());
        }
        String endpointCheck = endpointPolicy(endpoint);
        if (endpointCheck != null) {
            return new Decision.Blocked(TERMINAL_REASON, endpointCheck);
        }
        return new Decision.Admitted();
    }

    /** HTTPS required except explicit loopback (privacy.md transport policy). */
    public static String endpointPolicy(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "endpoint-missing";
        }
        String lower = endpoint.toLowerCase();
        if (lower.startsWith("https://")) {
            return null;
        }
        if (lower.startsWith("http://127.0.0.1") || lower.startsWith("http://localhost")
            || lower.startsWith("http://[::1]")) {
            return null;
        }
        return "endpoint-policy:non-loopback-plaintext";
    }

    /** True when the provenance is eligible in principle (before scanning). */
    public static boolean provenanceEligible(Provenance provenance) {
        return provenance instanceof Provenance.Synthetic
            || provenance instanceof Provenance.ApprovedNonSensitive;
    }

    /** Convenience: scan findings, empty Optional when clean. */
    public static Optional<Finding> firstFinding(String content) {
        List<Finding> findings = PrivacyScanner.scan(content);
        return findings.isEmpty() ? Optional.empty() : Optional.of(findings.get(0));
    }
}
