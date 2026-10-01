package rahu.core.privacy;

/**
 * Content provenance (privacy.md): only locally established synthetic or
 * approved-nonsensitive views are eligible for outbound admission, subject to
 * scanning. Model/tool/user text cannot self-declare synthetic; only the
 * built-in fixture loader creates Synthetic provenance.
 */
public sealed interface Provenance permits
    Provenance.Synthetic, Provenance.ApprovedNonSensitive,
    Provenance.Restricted, Provenance.Unknown {

    /** Authored fixture content only — created by the fixture loader, never inferred. */
    record Synthetic(String fixtureId) implements Provenance {
        public Synthetic {
            if (fixtureId == null || fixtureId.isBlank()) {
                throw new IllegalArgumentException("fixtureId required");
            }
        }
    }

    /** Operator assessment of submitted text; still scanned, never a bypass. */
    record ApprovedNonSensitive(String assessedBy) implements Provenance {

        public ApprovedNonSensitive {
            if (assessedBy == null || assessedBy.isBlank()) {
                throw new IllegalArgumentException("assessedBy required");
            }
        }
    }

    /** Known-restricted material: blocked unconditionally. */
    record Restricted(String reasonCode) implements Provenance {

        public Restricted {
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("reasonCode required");
            }
        }
    }

    /** Default for anything without established provenance: blocked. */
    record Unknown() implements Provenance {

        public static final Unknown INSTANCE = new Unknown();
    }
}
