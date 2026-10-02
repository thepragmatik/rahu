package rahu.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import rahu.core.CurrencyUnit;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;
import rahu.core.MoneyAmount;

/**
 * F2: the per-pool-model profile report behind {@code config validate --live-check}.
 *
 * <p>Rendering is pure so the admission verdict is testable without a catalog, a
 * network or a config file. Freshness is an INPUT here: the catalog decides what
 * fresh means, this class only applies the operator's stale permission.
 */
class LiveCheckReportTest {

    private static RahuConfig.PoolEntry poolEntry(String alias, String id) {
        return new RahuConfig.PoolEntry(alias, id, List.of(), null);
    }

    private static ModelProfile profile(String id, Integer context, String in, String out,
        boolean tools) {

        return new ModelProfile(new ModelRef(id), context, 4096,
            in == null ? null : new MoneyAmount(new BigDecimal(in), CurrencyUnit.USD),
            out == null ? null : new MoneyAmount(new BigDecimal(out), CurrencyUnit.USD),
            rahu.core.ReasoningPolicy.Effort.values(), false,
            Instant.parse("2026-10-02T00:00:00Z"), true, tools);
    }

    @Test
    void resolvedEntryRendersTheDocumentedLine() {
        var entries = LiveCheckReport.entries(
            List.of(poolEntry("nemo", "vendor/nemo")),
            e -> new LiveCheckReport.Resolution(
                profile("vendor/nemo", 131072, "0.000000019", "0.00000003", true), true),
            false);

        assertEquals(1, entries.size());
        assertEquals("nemo: ctx=131072 in=$0.019/M out=$0.030/M tools=true fresh=true",
            entries.get(0).rendered());
        assertTrue(entries.get(0).admitted());
        assertTrue(LiveCheckReport.allAdmitted(entries));
    }

    @Test
    void unknownPriceAndContextRenderAsQuestionMarks() {
        var entries = LiveCheckReport.entries(
            List.of(poolEntry("mystery", "vendor/mystery")),
            e -> new LiveCheckReport.Resolution(
                profile("vendor/mystery", null, null, null, false), true),
            false);

        assertEquals("mystery: ctx=? in=? out=? tools=false fresh=true",
            entries.get(0).rendered());
    }

    @Test
    void missingEvidenceRendersMissingAndBlocksAdmission() {
        var entries = LiveCheckReport.entries(
            List.of(poolEntry("ghost", "vendor/ghost")),
            e -> LiveCheckReport.Resolution.missing(),
            false);

        assertEquals("ghost: profile=missing", entries.get(0).rendered());
        assertFalse(entries.get(0).admitted());
        assertFalse(LiveCheckReport.allAdmitted(entries));
    }

    @Test
    void staleEvidenceBlocksUnlessTheOperatorAllowsIt() {
        Function<RahuConfig.PoolEntry, LiveCheckReport.Resolution> stale =
            e -> new LiveCheckReport.Resolution(
                profile("vendor/nemo", 131072, "0.000000019", "0.00000003", true), false);

        var refused = LiveCheckReport.entries(List.of(poolEntry("nemo", "vendor/nemo")),
            stale, false);
        assertEquals("nemo: ctx=131072 in=$0.019/M out=$0.030/M tools=true fresh=false",
            refused.get(0).rendered());
        assertFalse(refused.get(0).admitted(), "stale evidence must not buy paid admission");

        var allowed = LiveCheckReport.entries(List.of(poolEntry("nemo", "vendor/nemo")),
            stale, true);
        assertEquals("nemo: ctx=131072 in=$0.019/M out=$0.030/M tools=true fresh=false",
            allowed.get(0).rendered(), "the stale permission must not launder the line");
        assertTrue(allowed.get(0).admitted());
    }

    @Test
    void oneUnadmittedModelFailsTheWholeReport() {
        var entries = LiveCheckReport.entries(
            List.of(poolEntry("good", "vendor/good"), poolEntry("bad", "vendor/bad")),
            e -> "vendor/good".equals(e.id())
                ? new LiveCheckReport.Resolution(
                    profile("vendor/good", 8192, "0.00000001", "0.00000002", true), true)
                : LiveCheckReport.Resolution.missing(),
            false);

        assertEquals(2, entries.size());
        assertFalse(LiveCheckReport.allAdmitted(entries),
            "one pool model without evidence makes the pool inadmissible");
    }
}