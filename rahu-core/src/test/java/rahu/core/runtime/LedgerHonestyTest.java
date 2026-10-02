package rahu.core.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.CurrencyUnit;
import rahu.core.MoneyAmount;

/**
 * Money honesty at the ledger boundary.
 *
 * <p>Every test here asserts a CONSEQUENCE an operator would observe, not that a
 * method was called. Each one was a real defect found by adversarial audit at
 * {@code dcfc0e1}, and each is recorded here so the same silence cannot return
 * unnoticed.
 */
class LedgerHonestyTest {

    private static MoneyAmount usd(String amount) {
        return new MoneyAmount(new BigDecimal(amount), CurrencyUnit.USD);
    }

    @Test
    @DisplayName("BOTH reserved and uncertain constrain admission, not just the larger")
    void reservedAndUncertainBothCount() {
        // Regression: tryReserve summed settled + MAX(reserved, uncertain) + requested.
        // BigDecimal.max returns the LARGER of the two, so the smaller bucket vanished
        // from the admission check entirely. The undercount is largest exactly when
        // reserved and uncertain differ, which is the normal case: one live request
        // plus one whose outcome is unknown.
        //
        // Allowance 2.00, uncertain 0.90, reserved 0.20.
        //   correct: 0.90 + 0.20 + 0.95 = 2.05  -> refuse (real liability 2.05 > 2.00)
        //   buggy:  max(0.20, 0.90) = 0.90, so 0.90 + 0.95 = 1.85 -> admit
        // The buggy path admits work that overspends the allowance by 0.05.
        var ledger = new Ledger(usd("2.00"));
        ledger.markUncertain(ledger.reserve(usd("0.90"), "gen-uncertain"));
        ledger.reserve(usd("0.20"), "gen-live");

        assertTrue(ledger.tryReserve(usd("0.95"), "gen-3").isEmpty(),
            "0.90 uncertain + 0.20 reserved = 1.10 committed; 1.10 + 0.95 exceeds 2.00");
    }

    @Test
    @DisplayName("An unsuccessful operation is an uncertain liability, not a settled charge")
    void unsuccessfulSettlementIsUncertainNotSettled() {
        // `settle(..., false)` used to ignore the boolean and book the amount as a
        // confident settled cost. A failed request may have been processed and
        // billed before the failure was observed, so reporting it as a known real
        // charge overstates certainty -- and the caller passing `false` reasonably
        // believed otherwise.
        var ledger = new Ledger(usd("2.00"));
        var reservation = ledger.reserve(usd("0.40"), "gen-1");
        ledger.settle(reservation, usd("0.40"), false);

        assertEquals(0, ledger.settled().amount().compareTo(BigDecimal.ZERO),
            "an unconfirmed outcome must not appear as a settled real cost");
        assertEquals(0, ledger.uncertain().amount().compareTo(new BigDecimal("0.40")),
            "the amount is still owed, so it is an uncertain liability");
        assertEquals(0, ledger.reserved().amount().compareTo(BigDecimal.ZERO),
            "the reservation is consumed either way");
    }

    @Test
    @DisplayName("An overshot ledger reports a number instead of throwing")
    void overshootDoesNotThrow() {
        // remaining() built a MoneyAmount from a signed subtraction. MoneyAmount
        // rejects negatives by construction, so the one state an operator most needs
        // to read -- "I have overspent" -- was the one state that raised
        // IllegalArgumentException. /status has no catch, so the diagnostic command
        // died with a stack trace at exactly the moment the number mattered.
        var ledger = new Ledger(usd("1.00"));
        var reservation = ledger.reserve(usd("1.00"), "gen-1");
        ledger.settle(reservation, usd("1.50"), true);

        assertTrue(ledger.overshoot());
        assertDoesNotThrow(ledger::remaining,
            "reporting the balance must not throw, even when the balance is negative");
        assertEquals(0, ledger.remaining().amount().compareTo(BigDecimal.ZERO),
            "an overshot ledger has zero available for new work");
        assertEquals(0, ledger.overage().compareTo(new BigDecimal("-0.50")),
            "the signed headroom reports the true size of the overrun");
    }

    @Test
    @DisplayName("A healthy ledger still reports normal remaining headroom")
    void healthyLedgerIsUnaffected() {
        var ledger = new Ledger(usd("1.00"));
        var reservation = ledger.reserve(usd("0.40"), "gen-1");
        ledger.settle(reservation, usd("0.40"), true);

        assertEquals(0, ledger.remaining().amount().compareTo(new BigDecimal("0.60")));
        assertEquals(0, ledger.overage().compareTo(new BigDecimal("0.60")));
        assertTrue(ledger.tryReserve(usd("0.60"), "gen-2").isPresent(),
            "clamping must not change admission behaviour");
    }

    @Test
    @DisplayName("A child failure is uncertain at BOTH the child and the session")
    void childFailureIsUncertainAtBothLevels() {
        // An ambiguous child outcome must remain a liability at the parent too. If
        // only the child recorded it, the session would hand back headroom that may
        // already have been spent and then admit work it cannot pay for.
        var session = new Ledger(usd("2.00"));
        var run = session.childLedger(usd("1.00"));
        var reservation = run.reserve(usd("0.50"), "gen-1");
        run.settle(reservation, usd("0.50"), false);

        assertEquals(0, run.uncertain().amount().compareTo(new BigDecimal("0.50")));
        assertEquals(0, session.uncertain().amount().compareTo(new BigDecimal("0.50")),
            "the parent must carry the child's ambiguity");
        assertEquals(0, session.reserved().amount().compareTo(BigDecimal.ZERO));
    }
}
