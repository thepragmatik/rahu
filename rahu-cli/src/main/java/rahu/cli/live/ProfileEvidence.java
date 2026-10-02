package rahu.cli.live;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rahu.cli.config.RahuConfig;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;
import rahu.openrouter.ModelProfileCatalog;

/**
 * The catalog evidence a live run is allowed to route on, keyed by model.
 *
 * <p>Fail-closed by construction: a pool model with no evidence file entry, or with
 * evidence older than the configured staleness bound, is mapped to {@code null} so
 * CandidateFactory excludes it as {@code unknown-evidence} rather than admitting a
 * model whose price, context or tool support nobody has verified. The operator's
 * {@code allowStale} permission is the only way stale evidence survives that.
 *
 * <p>The catalog TTL governs REFETCH; {@code maximumStaleSeconds} governs ADMISSION.
 * Keeping them separate means an operator can be shown stale evidence, and can allow
 * it, without a network fetch being implied.
 */
public final class ProfileEvidence {

    private ProfileEvidence() {
    }

    /**
     * Loads profiles for every model in the configured pool, refetching the catalog
     * only when the cached evidence is past its TTL.
     *
     * @param evidenceFile the versioned evidence document; may not exist yet
     * @return pool model to profile, with {@code null} values for unproven models
     */
    public static Map<ModelRef, ModelProfile> load(RahuConfig cfg, Path evidenceFile)
        throws IOException, InterruptedException {

        RahuConfig.CatalogConfig catalog = cfg.catalog() == null
            ? RahuConfig.CatalogConfig.defaults() : cfg.catalog();
        var profiles = new ModelProfileCatalog(cfg.generation().baseUrl(), evidenceFile);
        Instant now = Instant.now();
        profiles.refreshIfNeeded(Duration.ofSeconds(catalog.ttlSeconds()), now);

        Duration maxStale = Duration.ofSeconds(catalog.staleSeconds());
        Map<ModelRef, ModelProfile> out = new LinkedHashMap<>();
        for (RahuConfig.PoolEntry entry : poolEntries(cfg)) {
            var ref = new ModelRef(entry.id());
            var profile = profiles.profile(ref);
            boolean fresh = profile != null && profiles.profileFresh(ref, maxStale, now);
            out.put(ref, profile != null && (fresh || catalog.staleAllowed()) ? profile : null);
        }
        return out;
    }

    private static List<RahuConfig.PoolEntry> poolEntries(RahuConfig cfg) {
        String pool = cfg.routing() == null ? null : cfg.routing().pool();
        List<RahuConfig.PoolEntry> entries = pool == null || cfg.pools() == null
            ? null : cfg.pools().get(pool);
        return entries == null ? List.of() : entries;
    }
}