package rahu.core.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A33/A34: zero sends for blocked requests; provenance/hash/re-scan discipline.
 * Canary literals are runtime-concatenated so source files stay clean.
 */
class PrivacyGateTest {

    // runtime-built canaries (never whole literals in source)
    private static final String EMAIL = "jane" + ".doe" + "@" + "example." + "test";
    private static final String KEY_TOKEN = "sk-" + "a".repeat(24);
    private static final String PRIVATE_KEY = "-----BEGIN " + "RSA PRIVATE" + " KEY-----";

    @Test
    @DisplayName("A33: unknown provenance blocks before any dispatch (zero sends)")
    void unknownProvenanceBlocks() {
        var gate = new PrivacyGate();
        var view = SafeView.of("v1", Provenance.Unknown.INSTANCE, "harmless looking text");
        var decision = gate.admitForDecision(view);
        assertInstanceOf(PrivacyGate.Decision.Blocked.class, decision);
        assertEquals("PRIVACY_BLOCKED", ((PrivacyGate.Decision.Blocked) decision).reasonCode());
    }

    @Test
    @DisplayName("A33: synthetic clean content admits; canary content blocks by category")
    void syntheticScanned() {
        var gate = new PrivacyGate();
        var clean = SafeView.of("v2", new Provenance.Synthetic("fixture-1"),
            "Explain the four planned modules.");
        assertInstanceOf(PrivacyGate.Decision.Admitted.class, gate.admitForDecision(clean));

        var withEmail = SafeView.of("v3", new Provenance.Synthetic("fixture-2"),
            "contact " + EMAIL + " for details");
        var blocked = (PrivacyGate.Decision.Blocked) gate.admitForDecision(withEmail);
        assertTrue(blocked.category().contains("identifier-email"));
        assertTrue(!blocked.category().contains(EMAIL),
            "diagnostics must not echo the offending value");
    }

    @Test
    @DisplayName("A33: dispatch recheck blocks canary appearing in adapter-serialised body")
    void dispatchRecheckBlocksCanary() {
        var gate = new PrivacyGate();
        var admitted = gate.admitForDecision(SafeView.of("v4",
            new Provenance.Synthetic("fixture-3"), "clean user request"));
        assertInstanceOf(PrivacyGate.Decision.Admitted.class, admitted);

        // adapter-serialised body gains a protected field late (e.g. tool echo)
        String body = "{\"messages\":[{\"role\":\"tool\",\"content\":\""
            + KEY_TOKEN + "\"}]}";
        var decision = gate.admitDispatch(body, "https://openrouter.test/api/v1");
        assertInstanceOf(PrivacyGate.Decision.Blocked.class, decision);
        assertTrue(((PrivacyGate.Decision.Blocked) decision).category().contains("key-token"));
    }

    @Test
    @DisplayName("A33: dispatch to non-loopback plaintext HTTP is blocked by endpoint policy")
    void endpointPolicyBlocks() {
        var gate = new PrivacyGate();
        var decision = gate.admitDispatch("{\"messages\":[]}", "http://example.test/api");
        assertInstanceOf(PrivacyGate.Decision.Blocked.class, decision);
        assertTrue(((PrivacyGate.Decision.Blocked) decision).category()
            .contains("endpoint-policy"));

        var loopback = gate.admitDispatch("{\"messages\":[]}", "http://127.0.0.1:8000/v1");
        assertInstanceOf(PrivacyGate.Decision.Admitted.class, loopback);
    }

    @Test
    @DisplayName("A34: source mutation changes the hash and invalidates prior approval")
    void mutationInvalidatesApproval() {
        var gate = new PrivacyGate();
        var view = SafeView.of("v5", new Provenance.ApprovedNonSensitive("operator"),
            "approved source text");
        assertInstanceOf(PrivacyGate.Decision.Admitted.class, gate.admitForDecision(view));

        var mutated = SafeView.of("v5", new Provenance.ApprovedNonSensitive("operator"),
            "approved source text " + EMAIL);
        var blocked = (PrivacyGate.Decision.Blocked) gate.admitForDecision(mutated);
        assertTrue(blocked.category().contains("identifier-email"),
            "mutated approved source must be re-scanned and blocked");
    }

    @Test
    @DisplayName("A34: restricted provenance blocks unconditionally, even with clean content")
    void restrictedBlocksUnconditionally() {
        var gate = new PrivacyGate();
        var view = SafeView.of("v6", new Provenance.Restricted("customer-data"),
            "nothing suspicious here");
        assertInstanceOf(PrivacyGate.Decision.Blocked.class, gate.admitForDecision(view));
    }

    @Test
    @DisplayName("Detector coverage: key tokens, private keys, credential assignments")
    void detectorClasses() {
        List<String> categories = PrivacyGate.firstFinding(
            "token " + KEY_TOKEN).map(Finding::category).stream().toList();
        assertTrue(categories.get(0).contains("key-token"));

        categories = PrivacyGate.firstFinding("block " + PRIVATE_KEY)
            .map(Finding::category).stream().toList();
        assertTrue(categories.get(0).contains("private-key"));

        categories = PrivacyGate.firstFinding("db_password" + " = hunter2sec")
            .map(Finding::category).stream().toList();
        assertTrue(categories.get(0).contains("credential-assignment"));
    }

    @Test
    @DisplayName("Recording sink pattern: zero writes when the gate blocks dispatch")
    void zeroDispatchOnBlock() {
        var gate = new PrivacyGate();
        var sink = new ArrayList<String>();
        String body = "{\"prompt\":\"call " + EMAIL + " now\"}";

        var decision = gate.admitDispatch(body, "https://openrouter.test/api/v1");
        if (decision instanceof PrivacyGate.Decision.Admitted) {
            sink.add(body); // transport would happen only here
        }
        assertEquals(0, sink.size(), "blocked dispatch must produce zero sends");
    }
}
