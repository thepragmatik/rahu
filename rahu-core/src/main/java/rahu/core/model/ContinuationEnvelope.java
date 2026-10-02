package rahu.core.model;

import java.util.Objects;

/**
 * Adapter-owned opaque continuation data (openrouter.md): provider reasoning,
 * signatures, encrypted state. Core stores the reference; it never interprets,
 * logs, or forwards the contents to a different provider.
 */
public record ContinuationEnvelope(String providerId, byte[] opaqueData, boolean present) {

    public ContinuationEnvelope {
        Objects.requireNonNull(providerId, "providerId");
        opaqueData = opaqueData == null ? new byte[0] : opaqueData.clone();
    }

    public static ContinuationEnvelope empty(String providerId) {
        return new ContinuationEnvelope(providerId, new byte[0], false);
    }

    public byte[] data() {
        return opaqueData.clone();
    }
}
