package rahu.core;

/**
 * Sealed cost outcomes: settled actuals, estimates used for admission, and explicit
 * unknown. Unknown is a distinct state, never zero (runtime.md ledger semantics).
 */
public sealed interface Cost permits Cost.Settled, Cost.Estimated, Cost.Unknown {

    record Settled(MoneyAmount amount) implements Cost {
        public Settled {
            if (amount == null) {
                throw new IllegalArgumentException("amount required");
            }
        }
    }

    record Estimated(MoneyAmount amount) implements Cost {
        public Estimated {
            if (amount == null) {
                throw new IllegalArgumentException("amount required");
            }
        }
    }

    /** Cost could not be determined; carries a safe reason (no raw payload). */
    record Unknown(String reason) implements Cost {
        public Unknown {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason required");
            }
        }
    }
}
