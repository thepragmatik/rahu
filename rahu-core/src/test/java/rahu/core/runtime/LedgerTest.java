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
    @DisplayName("Negative amounts are rejected everywhere")
    void rejectsNegative() {
        var ledger = new Ledger(usd("1.00"));
        assertThrows(IllegalArgumentException.class, () -> ledger.reserve(
            new MoneyAmount(new BigDecimal("-0.01"), CurrencyUnit.USD), "x"));
    }
}
