package rahu.core.authority;

import java.util.Objects;
import rahu.core.privacy.SafeView;

/**
 * A proposed operation entering the common deterministic pipeline (safety.md).
 * Model/tool/repository text can never create grants: this record carries only
 * what code constructed.
 */
public record ProposedOperation(
    String kind,
    String targetRef,
    EffectClass effectClass,
    SafeView safeView,
    String endpoint) {

    public ProposedOperation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(effectClass, "effectClass");
        Objects.requireNonNull(safeView, "safeView");
    }
}
