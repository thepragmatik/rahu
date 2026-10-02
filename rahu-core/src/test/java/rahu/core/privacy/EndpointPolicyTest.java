package rahu.core.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Endpoint transport policy, parsed rather than prefix-matched.
 *
 * <p>Audit finding F-3. {@code PrivacyGate.endpointPolicy} recognised loopback by
 * string prefix, so {@code http://localhost.evil.example} and
 * {@code http://127.0.0.1.evil.example} were treated as loopback and allowed to carry
 * plaintext. The exemption is about WHERE the bytes go, so it has to be decided by
 * the parsed host, not by how the string begins.
 */
class EndpointPolicyTest {

    @Test
    @DisplayName("https is permitted for any host")
    void httpsAnyHost() {
        assertNull(PrivacyGate.endpointPolicy("https://openrouter.ai/api/v1"));
        assertNull(PrivacyGate.endpointPolicy("https://localhost.evil.example/api"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1:8000",
        "http://localhost:8000",
        "http://[::1]:8000",
        "HTTP://LOCALHOST:8000",
    })
    @DisplayName("genuine loopback over plaintext is permitted")
    void loopbackAllowed(String endpoint) {
        assertNull(PrivacyGate.endpointPolicy(endpoint));
    }

    @Test
    @DisplayName("the whole 127.0.0.0/8 range is loopback, not just 127.0.0.1")
    void wholeLoopbackRangeAllowed() {
        assertNull(PrivacyGate.endpointPolicy("http://127.0.0.10:8000"));
        assertNull(PrivacyGate.endpointPolicy("http://127.1.2.3:8000"));
        assertEquals("endpoint-policy:non-loopback-plaintext",
            PrivacyGate.endpointPolicy("http://128.0.0.1:8000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost.evil.example/api",
        "http://127.0.0.1.evil.example/api",
        "http://localhosting.example",
        "http://localhost@evil.example",
        "http://127.0.0.1:8000.evil.example",
        "http://evil.example/localhost",
    })
    @DisplayName("a host that merely STARTS WITH a loopback name is not loopback")
    void lookalikeHostsRefused(String endpoint) {
        String policy = PrivacyGate.endpointPolicy(endpoint);
        assertNotNull(policy, "must not grant the plaintext loopback exemption to " + endpoint);
        assertEquals("endpoint-policy:non-loopback-plaintext", policy);
    }

    @Test
    @DisplayName("plaintext to a non-loopback host is refused")
    void nonLoopbackPlaintextRefused() {
        assertEquals("endpoint-policy:non-loopback-plaintext",
            PrivacyGate.endpointPolicy("http://openrouter.ai/api/v1"));
    }

    @Test
    @DisplayName("a missing endpoint is a distinct typed refusal, not a pass")
    void missingEndpointRefused() {
        assertEquals("endpoint-missing", PrivacyGate.endpointPolicy(null));
        assertEquals("endpoint-missing", PrivacyGate.endpointPolicy("  "));
    }
}