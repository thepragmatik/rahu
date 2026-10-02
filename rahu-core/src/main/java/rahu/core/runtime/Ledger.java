package rahu.core.runtime;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import rahu.core.CurrencyUnit;
import rahu.core.MoneyAmount;

/**
 * Hierarchical cost ledger (runtime.md; A13). Views: run ⊂ session ⊂ experiment.
 * Reservation is held once and referenced by each view; totals are reported at
 * the owning level only. Exact decimals; uncertain liability is separate from
 * settled cost and constrains admission until resolved.
 */
public final class Ledger {

    private final MoneyAmount allowance;
    private final Ledger parent;
    private MoneyAmount settled = zero();
    private MoneyAmount reserved = zero();
    private MoneyAmount uncertain = zero();
    private boolean overshoot;
    private final Map<String, Reservation> open = new LinkedHashMap<>();

    /** A live reservation; immutable identity, mutable only through the ledger. */
    public record Reservation(String operationId, MoneyAmount amount) {
    }

    public Ledger(MoneyAmount allowance) {
        this(allowance, null);
    }

    private Ledger(MoneyAmount allowance, Ledger parent) {
        if (allowance == null) {
            throw new IllegalArgumentException("allowance required");
        }
        this.allowance = allowance;
        this.parent = parent;
    }

    /** Child view (run inside session, session inside experiment). */
    public Ledger childLedger(MoneyAmount childAllowance) {
        return new Ledger(childAllowance, this);
    }

    /** Conservative admission: reserved + uncertain + requested must fit allowance. */
    public Optional<Reservation> tryReserve(MoneyAmount amount, String operationId) {
        if (amount.amount().signum() < 0) {
            throw new IllegalArgumentException("reservation must be nonnegative");
        }
        if (overshoot) {
            return Optional.empty();
        }
        // ALL THREE components constrain admission. Settled money is money already
        // spent: including only reserved+uncertain meant that once a reservation
        // settled, it stopped counting, so an exhausted allowance looked empty again.
        BigDecimal total = settled.amount()
            .add(reserved.amount().max(uncertain.amount()))
            .add(amount.amount());
        if (total.compareTo(allowance.amount()) > 0) {
            return Optional.empty();
        }
        if (parent != null && parent.tryReserve(amount, operationId).isEmpty()) {
            return Optional.empty();
        }
        reserved = add(reserved, amount);
        var reservation = new Reservation(operationId, amount);
        open.put(operationId, reservation);
        return Optional.of(reservation);
    }

    public Reservation reserve(MoneyAmount amount, String operationId) {
        return tryReserve(amount, operationId).orElseThrow(() ->
            new IllegalStateException("cost admission denied: " + operationId
                + " needs " + amount.amount() + " but allowance is "
                + allowance.amount() + " (committed " + committedAmount() + ")"));
    }

    /** Settles a reservation with actual cost; released from reserved; rolls up once. */
    public void settle(Reservation reservation, MoneyAmount actual, boolean successful) {
        requireOpen(reservation);
        reserved = subtract(reserved, reservation.amount());
        open.remove(reservation.operationId());
        settled = add(settled, actual);
        if (parent != null) {
            parent.settleUp(reservation.amount(), actual);
        }
        if (settled.amount().add(uncertain.amount())
            .compareTo(allowance.amount()) > 0) {
            overshoot = true;
        }
    }

    /**
     * Parent-side handling of a child's terminal outcome (counted once).
     *
     * The parent's `reserved` was incremented when the CHILD reserved, so this must
     * release it for the same amount the child released. It must NOT release
     * `actual`: a provider that bills more than estimated must leave the full
     * estimate committed and additionally raise `overshoot`, so the parent refuses
     * further work rather than quietly recovering headroom that was never spent.
     *
     * Releasing the reservation and rolling up the settled cost are separate
     * operations because they are not equal.
     */
    private void settleUp(MoneyAmount released, MoneyAmount actual) {
        requireSameCurrency(released, actual);
        reserved = subtract(reserved, released);
        settled = add(settled, actual);
        if (settled.amount().add(uncertain.amount())
            .compareTo(allowance.amount()) > 0) {
            overshoot = true;
        }
    }

    /**
     * Parent-side handling when a child's outcome was AMBIGUOUS: the reservation
     * becomes an uncertain liability at the parent too, not a released one.
     */
    private void uncertainUp(MoneyAmount released) {
        reserved = subtract(reserved, released);
        uncertain = add(uncertain, released);
        if (settled.amount().add(uncertain.amount())
            .compareTo(allowance.amount()) > 0) {
            overshoot = true;
        }
    }

    /** Parent-side handling when a child's reservation was definitely never used. */
    private void releaseUp(MoneyAmount released) {
        reserved = subtract(reserved, released);
    }

    /** Ambiguous paid outcome: retain full amount as uncertain liability. */
    public void markUncertain(Reservation reservation) {
        requireOpen(reservation);
        reserved = subtract(reserved, reservation.amount());
        open.remove(reservation.operationId());
        uncertain = add(uncertain, reservation.amount());
        if (parent != null) {
            parent.uncertainUp(reservation.amount());
        }
    }

    /** Releases a definitely-unused reservation (late privacy block). */
    public void release(Reservation reservation) {
        requireOpen(reservation);
        reserved = subtract(reserved, reservation.amount());
        open.remove(reservation.operationId());
        if (parent != null) {
            parent.releaseUp(reservation.amount());
        }
    }

    /**
     * Conversation reset: clears nothing financial. Liabilities persist
     * (runtime.md; A32).
     */
    public void resetConversation() {
        // deliberate no-op for money; conversation content reset is separate state
    }

    public MoneyAmount settled() {
        return settled;
    }

    public MoneyAmount reserved() {
        return reserved;
    }

    public MoneyAmount uncertain() {
        return uncertain;
    }

    public MoneyAmount remaining() {
        return new MoneyAmount(
            allowance.amount().subtract(settled.amount())
                .subtract(reserved.amount()).subtract(uncertain.amount()),
            allowance.currency());
    }

    public boolean overshoot() {
        return overshoot;
    }

    private BigDecimal committedAmount() {
        return settled.amount()
            .add(reserved.amount().max(uncertain.amount()));
    }

    private void requireOpen(Reservation reservation) {
        if (!open.containsKey(reservation.operationId())) {
            throw new IllegalStateException(
                "reservation not open: " + reservation.operationId());
        }
    }

    private static MoneyAmount zero() {
        return new MoneyAmount(BigDecimal.ZERO, CurrencyUnit.USD);
    }

    private static MoneyAmount add(MoneyAmount a, MoneyAmount b) {
        requireSameCurrency(a, b);
        return new MoneyAmount(a.amount().add(b.amount()), a.currency());
    }

    private static MoneyAmount subtract(MoneyAmount a, MoneyAmount b) {
        requireSameCurrency(a, b);
        return new MoneyAmount(a.amount().subtract(b.amount()), a.currency());
    }

    private static void requireSameCurrency(MoneyAmount a, MoneyAmount b) {
        if (a.currency() != b.currency()) {
            throw new IllegalArgumentException("currency mismatch: "
                + a.currency() + " vs " + b.currency());
        }
    }
}
