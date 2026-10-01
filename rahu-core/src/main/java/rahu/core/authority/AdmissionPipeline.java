package rahu.core.authority;

import java.util.Optional;
import rahu.core.privacy.PrivacyGate;
import rahu.core.runtime.Ledger;
import rahu.core.runtime.RunStateMachine;

/**
 * The one deterministic admission sequence (safety.md). Every operation —
 * generation, decision, tool, summary — goes through this; registries cannot
 * execute around it. Denials happen before effects, with safe reasons only.
 *
 * Steps: 1 run-state active, 2 shape/effect validation, 3 boundary (effect
 * class), 4 privacy admission, 5 ledger reserve, 6 trace evidence, then a
 * separate exact-serialised dispatch recheck before transport.
 */
public final class AdmissionPipeline {

    public sealed interface Outcome permits Outcome.Approved, Outcome.Denied {
        record Approved(Ledger.Reservation reservation) implements Outcome {
        }

        record Denied(int step, String reason) implements Outcome {

            public Denied {
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("reason required");
                }
            }
        }
    }

    private final RunStateMachine run;
    private final Ledger ledger;
    private final PrivacyGate privacyGate;

    public AdmissionPipeline(RunStateMachine run, Ledger ledger, PrivacyGate privacyGate) {
        this.run = run;
        this.ledger = ledger;
        this.privacyGate = privacyGate;
    }

    /**
     * Evaluates steps 1-5 for a paid operation. Step 6 (trace write) and the
     * step-7 dispatch recheck belong to the caller at execution time.
     */
    public Outcome evaluate(ProposedOperation operation, rahu.core.MoneyAmount estimated) {
        // 1: run state must be active
        if (run.isTerminal()) {
            return new Outcome.Denied(1, "run is terminal (phase=" + run.phase() + ")");
        }
        // 2: shape validation
        if (operation.kind() == null || operation.kind().isBlank()
            || operation.targetRef() == null || operation.targetRef().isBlank()) {
            return new Outcome.Denied(2, "operation shape invalid");
        }
        // 3: boundary — alpha admits read-only effects only
        if (operation.effectClass() != EffectClass.READ_ONLY) {
            return new Outcome.Denied(3, "effect class " + operation.effectClass()
                + " is not admissible in alpha (read-only profile)");
        }
        // 4: privacy admission on the safe view
        var privacy = privacyGate.admitForDecision(operation.safeView());
        if (privacy instanceof PrivacyGate.Decision.Blocked blocked) {
            return new Outcome.Denied(4, blocked.category());
        }
        // 5: conservative cost reservation
        Optional<Ledger.Reservation> reservation = ledger.tryReserve(estimated,
            operation.kind() + ":" + operation.targetRef());
        if (reservation.isEmpty()) {
            return new Outcome.Denied(5, "cost admission denied");
        }
        return new Outcome.Approved(reservation.get());
    }

    /** Step 7: exact-serialised recheck immediately before transport. */
    public Optional<Outcome.Denied> recheckDispatch(String serialisedBody, String endpoint) {
        var decision = privacyGate.admitDispatch(serialisedBody, endpoint);
        if (decision instanceof PrivacyGate.Decision.Blocked blocked) {
            return Optional.of(new Outcome.Denied(7, blocked.category()));
        }
        return Optional.empty();
    }
}
