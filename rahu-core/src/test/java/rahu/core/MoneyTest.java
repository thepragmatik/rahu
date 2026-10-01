package rahu.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S01 domain invariants for money types (runtime.md ledger semantics, A13). */
class MoneyTest {

    @Test
    @DisplayName("Unknown cost is distinct from numeric zero")
    void unknownIsNotZero() {
        var unknown = new Cost.Unknown("no price evidence");
        var zero = new Cost.Settled(new MoneyAmount(BigDecimal.ZERO, CurrencyUnit.USD));
        assertNotEquals(unknown, zero);
    }

    @Test
    @DisplayName("Negative amounts are rejected at construction")
    void rejectsNegative() {
        assertThrows(IllegalArgumentException.class,
            () -> new MoneyAmount(new BigDecimal("-0.01"), CurrencyUnit.USD));
    }

    @Test
    @DisplayName("Exact decimals are preserved without float drift")
    void exactDecimals() {
        var a = new MoneyAmount(new BigDecimal("1.005"), CurrencyUnit.USD);
        assertEquals(new BigDecimal("1.005"), a.amount());
    }
}
