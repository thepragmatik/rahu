package rahu.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.CurrencyUnit;
import rahu.core.MoneyAmount;

/**
 * S06 hierarchical ledger (runtime.md ledger and admission; A13). Exact
 * decimals; reserve/settle/uncertain separated; parent allowance constrains
 * children; reset never clears liability.
 */
class LedgerTest {

    private static MoneyAmount usd(String amount) {
        return new MoneyAmount(new BigDecimal(amount), CurrencyUnit.USD);
    }

    @Test
    @DisplayName("Reservation within allowance admits; settling replaces the reservation")
    void reserveAndSettle() {
        var ledger = new Ledger(usd("1.00"));
        var reservation = ledger.reserve(usd("0.40"), "gen-1");
        assertEquals(0, ledger.reserved().amount().compareTo(new BigDecimal("0.40")));
        assertEquals(0, ledger.remaining().amount().compareTo(new BigDecimal("0.60")));

        ledger.settle(reservation, usd("0.35"), true);
        assertEquals(0, ledger.settled().amount().compareTo(new BigDecimal("0.35")));
        assertEquals(0, ledger.reserved().amount().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("A13: reservation beyond allowance is denied before any paid call")
    void overReservationDenied() {
        var ledger = new Ledger(usd("1.00"));
        var first = ledger.reserve(usd("0.90"), "gen-1");
        var denied = ledger.tryReserve(usd("0.20"), "gen-2");
        assertTrue(denied.isEmpty(), "0.90 reserved + 0.20 requested > 1.00 allowance");
        ledger.release(first);
    }

    @Test
    @DisplayName("A13: ambiguous outcome retains uncertain liability and blocks new admission")
    void ambiguousRetainsLiability() {
        var ledger = new Ledger(usd("1.00"));
        var reservation = ledger.reserve(usd("0.50"), "gen-1");
        ledger.markUncertain(reservation);
        assertEquals(0, ledger.uncertain().amount().compareTo(new BigDecimal("0.50")));
        assertTrue(ledger.tryReserve(usd("0.60"), "gen-2").isEmpty(),
            "uncertain liability must constrain admission");
    }

    @Test
    @DisplayName("Session reset keeps settled + uncertain totals (reset never clears liability)")
    void resetKeepsLiability() {
        var ledger = new Ledger(usd("3.00"));
        var r1 = ledger.reserve(usd("1.00"), "gen-1");
        ledger.settle(r1, usd("1.00"), true);
        var r2 = ledger.reserve(usd("0.50"), "gen-2");
        ledger.markUncertain(r2);

        ledger.resetConversation();
        assertEquals(0, ledger.settled().amount().compareTo(new BigDecimal("1.00")));
        assertEquals(0, ledger.uncertain().amount().compareTo(new BigDecimal("0.50")));
        assertEquals(0, ledger.remaining().amount().compareTo(new BigDecimal("1.50")));
    }

    @Test
    @DisplayName("Overshoot after settlement is recorded and stops new paid work")
    void overshootStopsWork() {
        var ledger = new Ledger(usd("1.00"));
        var r = ledger.reserve(usd("0.50"), "gen-1");
        ledger.settle(r, usd("1.20"), true);
        assertTrue(ledger.overshoot());
        assertThrows(IllegalStateException.class, () -> ledger.reserve(usd("0.10"), "gen-2"));
    }

    @Test
    @DisplayName("Hierarchical views share one reservation (counted once, not summed)")
    void nestedViewsShareReservation() {
        var session = new Ledger(usd("3.00"));
        var run = session.childLedger(usd("1.00"));
        var reservation = run.reserve(usd("0.25"), "gen-1");
        run.settle(reservation, usd("0.25"), true);

        assertEquals(0, run.settled().amount().compareTo(new BigDecimal("0.25")));
        assertEquals(0, session.settled().amount().compareTo(new BigDecimal("0.25")),
            "child settlement rolls up once; double-counting forbidden");
    }

    @Test
    @DisplayName("A child settlement releases the PARENT's reservation too")
    void childSettleReleasesParentReservation() {
        var session = new Ledger(usd("3.00"));
        var run = session.childLedger(usd("1.00"));
        var r = run.reserve(usd("0.25"), "gen-1");
        run.settle(r, usd("0.25"), true);

        // The parent's reserved was incremented at reserve time. Settling the child
        // must give it back, or the parent exhausts its allowance across successive
        // runs that each settled correctly.
        assertEquals(0, session.reserved().amount().compareTo(BigDecimal.ZERO),
            "parent reservation must not leak after the child settles");
        assertEquals(0, session.remaining().amount().compareTo(new BigDecimal("2.75")),
            "remaining = allowance - settled - reserved - uncertain");
    }

    @Test
    @DisplayName("Successive child runs do not exhaust the parent allowance")
    void successiveChildRunsDoNotExhaustParent() {
        var session = new Ledger(usd("1.00"));
        // Each run settles cleanly for 0.25. Four runs cost 1.00 total, which is
        // exactly the session allowance -- the fifth has nothing left, but the first
        // four must each be admitted.
        for (int i = 1; i <= 4; i++) {
            var run = session.childLedger(usd("1.00"));
            var r = run.reserve(usd("0.25"), "gen-" + i);
            run.settle(r, usd("0.25"), true);
        }
        assertEquals(0, session.settled().amount().compareTo(new BigDecimal("1.00")));
        var fifth = session.childLedger(usd("1.00"));
        assertTrue(fifth.tryReserve(usd("0.25"), "gen-5").isEmpty(),
            "allowance is genuinely exhausted after four settled runs");
    }

    @Test
    @DisplayName("A child ambiguous outcome becomes an uncertain liability at the parent")
    void childUncertainRollsUpAsUncertainNotReleased() {
        var session = new Ledger(usd("3.00"));
        var run = session.childLedger(usd("1.00"));
        var r = run.reserve(usd("0.25"), "gen-1");
        run.markUncertain(r);

        // The parent reserved this amount too. Treating the child's ambiguity as a
        // RELEASE at the parent would hand back headroom that may already have been
        // spent -- the parent would then admit work it cannot pay for.
        assertEquals(0, session.uncertain().amount().compareTo(new BigDecimal("0.25")),
            "ambiguity must constrain the parent allowance");
        assertEquals(0, session.reserved().amount().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("A child release returns the parent's reservation without settling")
    void childReleaseReturnsParentReservation() {
        var session = new Ledger(usd("3.00"));
        var run = session.childLedger(usd("1.00"));
        var r = run.reserve(usd("0.25"), "gen-1");
        run.release(r);

        assertEquals(0, session.reserved().amount().compareTo(BigDecimal.ZERO));
        assertEquals(0, session.settled().amount().compareTo(BigDecimal.ZERO),
            "a released reservation was never paid");
        assertEquals(0, session.remaining().amount().compareTo(new BigDecimal("3.00")));
    }

    @Test
    @DisplayName("A child billing above estimate overshoots the PARENT")
    void childOvershootPropagatesToParent() {
        var session = new Ledger(usd("1.00"));
        var run = session.childLedger(usd("1.00"));
        var r = run.reserve(usd("0.50"), "gen-1");
        // The provider billed more than the reservation. The child's estimate is
        // committed AND the excess is settled, so the parent must overshoot too --
        // otherwise one child's overrun is invisible to the session that pays it.
        run.settle(r, usd("1.20"), true);
        assertTrue(session.overshoot(),
            "a child's overrun must stop new paid work at the parent as well");
        assertTrue(session.tryReserve(usd("0.01"), "gen-2").isEmpty());
    }

    @Test
    @DisplayName("Spend that is already SETTLED still counts against the allowance")
    void settledSpendCountsAgainstAllowance() {
        var ledger = new Ledger(usd("1.00"));
        var r = ledger.reserve(usd("1.00"), "gen-1");
        ledger.settle(r, usd("1.00"), true);

        // Admission compared only `reserved` (plus uncertain) against the allowance.
        // Settled money had left `reserved`, so after spending the allowance in full
        // the ledger looked empty again and admitted more paid work -- the cap was
        // only ever enforced against money NOT yet spent.
        assertEquals(0, ledger.remaining().amount().compareTo(BigDecimal.ZERO),
            "the allowance is spent");
        assertTrue(ledger.tryReserve(usd("0.01"), "gen-2").isEmpty(),
            "a fully spent allowance must refuse further paid work");
    }

    @Test
    @DisplayName("Partial spend leaves exactly the remainder")
    void partialSpendLeavesRemainder() {
        var ledger = new Ledger(usd("1.00"));
        var r = ledger.reserve(usd("0.40"), "gen-1");
        ledger.settle(r, usd("0.40"), true);
        assertEquals(0, ledger.remaining().amount().compareTo(new BigDecimal("0.60")));
        assertTrue(ledger.tryReserve(usd("0.60"), "gen-2").isPresent());
        assertTrue(ledger.tryReserve(usd("0.01"), "gen-3").isEmpty());
    }

    @Test
    @DisplayName("Negative amounts are rejected everywhere")
    void rejectsNegative() {
        var ledger = new Ledger(usd("1.00"));
        assertThrows(IllegalArgumentException.class, () -> ledger.reserve(
            new MoneyAmount(new BigDecimal("-0.01"), CurrencyUnit.USD), "x"));
    }
}
