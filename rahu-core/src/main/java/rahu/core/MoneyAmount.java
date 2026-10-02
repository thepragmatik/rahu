package rahu.core;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Exact-decimal money (ARCHITECTURE.md Money contract). Unknown cost is modeled
 * separately in {@link Cost.Unknown}, never as numeric zero.
 */
public record MoneyAmount(BigDecimal amount, CurrencyUnit currency) {

    public MoneyAmount {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be nonnegative, got " + amount);
        }
    }
}
