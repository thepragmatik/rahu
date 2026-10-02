package rahu.cli.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import rahu.core.ModelProfile;
import rahu.core.MoneyAmount;

/**
 * Renders one evidence line per configured pool model for
 * {@code rahu config validate --live-check} (routing.md candidate admission step 5).
 *
 * <p>Admission follows the paid-admission rule: a model buys execution only with
 * resolvable evidence that is fresh, unless the operator explicitly allowed stale
 * evidence in the catalog config. Rendering stays pure — freshness is an input, not
 * something this class measures — so the verdict is testable with no catalog, no
 * config file and no network.
 *
 * <p>Prices render per million tokens because per-token decimals are unreadable; a
 * price the catalog could not parse stays {@code ?} and never becomes {@code $0}.
 */
public final class LiveCheckReport {

    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");
    private static final int PRICE_SCALE = 6;
    private static final int MIN_PRICE_SCALE = 3;

    private LiveCheckReport() {
    }

    /**
     * A pool model's evidence as the catalog sees it.
     *
     * @param profile the resolved profile, or {@code null} when the catalog lacks it
     * @param fresh whether that evidence is within the configured staleness bound
     */
    public record Resolution(ModelProfile profile, boolean fresh) {

        public static Resolution missing() {
            return new Resolution(null, false);
        }
    }

    /**
     * One rendered pool-model line.
     *
     * @param rendered the operator-facing evidence line
     * @param admitted whether this model may take part in paid routing
     */
    public record Entry(String alias, String rendered, boolean admitted) {
    }

    public static List<Entry> entries(List<RahuConfig.PoolEntry> pool,
        Function<RahuConfig.PoolEntry, Resolution> lookup, boolean allowStale) {

        List<Entry> out = new ArrayList<>();
        for (RahuConfig.PoolEntry entry : pool) {
            Resolution resolution = lookup.apply(entry);
            ModelProfile profile = resolution == null ? null : resolution.profile();
            boolean fresh = resolution != null && resolution.fresh();
            boolean admitted = profile != null && (fresh || allowStale);
            out.add(new Entry(entry.alias(), render(entry.alias(), profile, fresh), admitted));
        }
        return List.copyOf(out);
    }

    /** A pool is admissible only when every one of its models is. */
    public static boolean allAdmitted(List<Entry> entries) {
        return entries.stream().allMatch(Entry::admitted);
    }

    private static String render(String alias, ModelProfile profile, boolean fresh) {
        if (profile == null) {
            return alias + ": profile=missing";
        }
        return alias + ": ctx=" + intOrUnknown(profile.contextTokensOpt())
            + " in=" + price(profile.inputUsdPerToken())
            + " out=" + price(profile.outputUsdPerToken())
            + " tools=" + profile.toolSupport()
            + " fresh=" + fresh;
    }

    private static String intOrUnknown(Optional<Integer> value) {
        return value.map(String::valueOf).orElse("?");
    }

    /**
     * Per-million rendering, never below three decimals: {@code $0.03} beside
     * {@code $0.019} reads as a units bug, and trailing-zero stripping keeps a
     * genuinely cheap model from printing as {@code $0.000}.
     */
    private static String price(MoneyAmount perToken) {
        if (perToken == null || perToken.amount() == null) {
            return "?";
        }
        BigDecimal perMillion = perToken.amount().multiply(PER_MILLION)
            .setScale(PRICE_SCALE, RoundingMode.HALF_UP)
            .stripTrailingZeros();
        if (perMillion.scale() < MIN_PRICE_SCALE) {
            perMillion = perMillion.setScale(MIN_PRICE_SCALE);
        }
        return "$" + perMillion.toPlainString() + "/M";
    }
}