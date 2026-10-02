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
        BigDecimal committed = reserved.amount().max(uncertain.amount());
        BigDecimal total = committed.add(amount.amount());
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
            parent.rollUp(actual);
        }
        if (settled.amount().add(uncertain.amount())
            .compareTo(allowance.amount()) > 0) {
            overshoot = true;
        }
    }

    /** Parent-side roll-up of a child's settled cost (counted once). */
    private void rollUp(MoneyAmount actual) {
        settled = add(settled, actual);
        if (settled.amount().add(uncertain.amount())
            .compareTo(allowance.amount()) > 0) {
            overshoot = true;
        }
    }

    /** Ambiguous paid outcome: retain full amount as uncertain liability. */
    public void markUncertain(Reservation reservation) {
        requireOpen(reservation);
        reserved = subtract(reserved, reservation.amount());
        open.remove(reservation.operationId());
        uncertain = add(uncertain, reservation.amount());
    }

    /** Releases a definitely-unused reservation (late privacy block). */
    public void release(Reservation reservation) {
        requireOpen(reservation);
        reserved = subtract(reserved, reservation.amount());
        open.remove(reservation.operationId());
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
        return reserved.amount().max(uncertain.amount());
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
