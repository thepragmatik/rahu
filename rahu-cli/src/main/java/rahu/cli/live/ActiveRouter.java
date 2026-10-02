package rahu.cli.live;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import rahu.cli.config.RahuConfig;
import rahu.core.ExecutionCandidate;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;
import rahu.core.decision.DecisionResult;
import rahu.core.routing.CandidateFactory;
import rahu.core.routing.CandidateSet;
import rahu.core.routing.ModelPool;
import rahu.core.routing.OperationRequirements;
import rahu.core.routing.RouteResolution;
import rahu.core.routing.RouteResolver;
import rahu.core.routing.RoutingMode;

/**
 * Turns the configured pool into the candidates a live turn may execute, and
 * resolves which one the decision plane selects.
 *
 * <p>The labels handed to the decision are generated FROM the candidate set, never
 * from the raw pool: RouteResolver requires a decision's probability keys to cover
 * the candidate ids exactly, so a label built from the pool configuration would
 * silently degrade every turn to the baseline while routing looked healthy.
 *
 * <p>Admission is evidence-gated (F1/F2): a model without catalog profile evidence,
 * or without tool support when tools are exposed, is excluded here rather than
 * discovered at dispatch time.
 */
public final class ActiveRouter {

    /** Provenance tag recorded on candidates built from catalog evidence. */
    private static final String CATALOG_HASH = "catalog-evidence-v1";
    private static final int DEFAULT_CONTEXT_ALLOWANCE = 8192;
    private static final int DEFAULT_MAX_CANDIDATES = 32;
    private static final double DEFAULT_CONFIDENCE_FLOOR = 0.0;

    private final RahuConfig cfg;
    private final CandidateSet candidates;

    public ActiveRouter(RahuConfig cfg, Map<ModelRef, ModelProfile> profiles) {
        this.cfg = cfg;
        this.candidates = build(cfg, profiles);
    }

    public CandidateSet candidates() {
        return candidates;
    }

    public RoutingMode mode() {
        return RoutingMode.valueOf(cfg.routing().mode().toUpperCase(Locale.ROOT));
    }

    /**
     * The choice labels for the routing question, keyed by candidate id.
     *
     * <p>Key set equality with {@link #candidates()} is the contract: a decision
     * over any other label set degrades to the baseline by construction.
     */
    public Map<String, String> criteria() {
        Map<String, String> criteria = new LinkedHashMap<>();
        for (ExecutionCandidate candidate : candidates.candidates()) {
            criteria.put(candidate.id(), describe(cfg, candidate.model()));
        }
        return criteria;
    }

    /** Resolves the executed route for one turn. */
    public RouteResolution resolve(Optional<DecisionResult> decision) {
        var routing = cfg.routing();
        double floor = routing.confidenceFloor() == null
            ? DEFAULT_CONFIDENCE_FLOOR : routing.confidenceFloor();
        return new RouteResolver().resolve(mode(), decision,
            new RouteResolver.ResolutionInput(candidates,
                Optional.ofNullable(routing.baseline()),
                Optional.ofNullable(routing.fallback()),
                routing.confidenceField() == null ? "" : routing.confidenceField(),
                floor));
    }

    /** The pool alias behind a candidate id, for provider lookup. */
    public String aliasOf(String candidateId) {
        int at = candidateId.indexOf('@');
        if (at <= 0) {
            throw new IllegalArgumentException(
                "candidate id carries no reasoning policy: " + candidateId);
        }
        return candidateId.substring(0, at);
    }

    /** The operator-facing {@code route:} line. */
    public static String describe(RouteResolution resolution) {
        StringBuilder line = new StringBuilder("route: ")
            .append("suggested=").append(resolution.suggestedId().orElse("-"))
            .append(" executed=").append(resolution.executedId().orElse("-"))
            .append(" mode=").append(resolution.mode())
            .append(" degraded=").append(resolution.degraded())
            .append(" excluded=").append(resolution.exclusions().size());
        resolution.fallbackCause().ifPresent(cause -> line.append(" fallback=").append(cause));
        resolution.terminalReason().ifPresent(
            reason -> line.append(" terminal=").append(reason));
        return line.toString();
    }

    // ------------------------------------------------------------------ build

    private static CandidateSet build(RahuConfig cfg,
        Map<ModelRef, ModelProfile> profiles) {

        String poolName = cfg.routing() == null ? null : cfg.routing().pool();
        List<RahuConfig.PoolEntry> entries = cfg.pools() == null ? null
            : cfg.pools().get(poolName);

        List<rahu.core.routing.PoolModel> models = new ArrayList<>();
        if (entries != null) {
            for (RahuConfig.PoolEntry entry : entries) {
                models.add(new rahu.core.routing.PoolModel(entry.alias(),
                    new ModelRef(entry.id()),
                    new LinkedHashSet<>(policies(entry.reasoning()))));
            }
        }
        var set = new CandidateFactory().generate(
            new ModelPool(poolName, models), profiles, requirements(cfg),
            CATALOG_HASH, "config-" + cfg.schemaVersion());
        requireAdmissible(cfg, set);
        return cap(cfg, set);
    }

    /**
     * Fails fast when a configured route endpoint is not executable.
     *
     * <p>Without this, an evidence-excluded baseline or fallback surfaces as a
     * per-turn {@code NO_FEASIBLE_ROUTE} terminal — technically correct, operationally
     * unreadable, and only after paid turns have started. The operator gets the cause
     * at startup instead, named.
     */
    private static void requireAdmissible(RahuConfig cfg, CandidateSet set) {
        var executable = set.candidates().stream().map(ExecutionCandidate::id).toList();
        String pool = cfg.routing().pool();
        requireExecutable(set, executable, cfg.routing().baseline(), "routing.baseline", pool);
        requireExecutable(set, executable, cfg.routing().fallback(), "routing.fallback", pool);
    }

    private static void requireExecutable(CandidateSet set, List<String> executable,
        String candidateId, String field, String poolName) {

        if (candidateId == null || executable.contains(candidateId)) {
            return;
        }
        String cause = set.exclusions().stream()
            .filter(e -> e.ref().equals(candidateId))
            .map(CandidateSet.Exclusion::reason)
            .findFirst()
            .orElse("not in pool '" + poolName + "'");
        throw new IllegalStateException(field + " " + candidateId
            + " is not an executable candidate (" + cause
            + "); fix the pool or the catalog evidence");
    }

    /**
     * Applies the candidate cap without ever truncating the configured baseline
     * out of the executable set: the baseline is the one candidate a turn can
     * always fall back to, so dropping it would turn a bounded pool into a
     * routeless one.
     */
    private static CandidateSet cap(RahuConfig cfg, CandidateSet set) {
        int max = cfg.routing() == null || cfg.routing().maximumCandidates() == null
            ? DEFAULT_MAX_CANDIDATES : cfg.routing().maximumCandidates();
        List<ExecutionCandidate> candidates = set.candidates();
        if (candidates.size() <= max) {
            return set;
        }
        String baseline = cfg.routing().baseline();
        List<ExecutionCandidate> kept = new ArrayList<>();
        for (ExecutionCandidate candidate : candidates) {
            if (candidate.id().equals(baseline)) {
                kept.add(candidate);
                break;
            }
        }
        for (ExecutionCandidate candidate : candidates) {
            if (kept.size() >= max) {
                break;
            }
            if (!kept.contains(candidate)) {
                kept.add(candidate);
            }
        }
        return new CandidateSet(kept, set.exclusions());
    }

    private static OperationRequirements requirements(RahuConfig cfg) {
        boolean toolsExposed = cfg.tools() != null && cfg.tools().enabled() != null
            && !cfg.tools().enabled().isEmpty();
        int allowance = cfg.context() != null && cfg.context().maxPromptTokens() != null
            ? cfg.context().maxPromptTokens() : DEFAULT_CONTEXT_ALLOWANCE;
        return new OperationRequirements(/* isTextAnswer */ true, toolsExposed, allowance,
            /* structuredOutputRequired */ false);
    }

    private static String describe(RahuConfig cfg, ModelRef model) {
        if (cfg.pools() != null && cfg.routing() != null) {
            List<RahuConfig.PoolEntry> entries = cfg.pools().get(cfg.routing().pool());
            if (entries != null) {
                for (RahuConfig.PoolEntry entry : entries) {
                    if (entry.id().equals(model.providerNeutralId())
                        && entry.description() != null) {
                        return entry.description();
                    }
                }
            }
        }
        return model.providerNeutralId();
    }

    private static List<ReasoningPolicy> policies(List<String> reasoning) {
        if (reasoning == null || reasoning.isEmpty()) {
            return List.of(new ReasoningPolicy.ProviderDefault());
        }
        return reasoning.stream().map(ActiveRouter::policy).toList();
    }

    private static ReasoningPolicy policy(String label) {
        return switch (label) {
            case "default" -> new ReasoningPolicy.ProviderDefault();
            case "none" -> ReasoningPolicy.Disabled.INSTANCE;
            default -> ReasoningPolicy.ExplicitEffort.of(effort(label));
        };
    }

    private static ReasoningPolicy.Effort effort(String label) {
        try {
            return ReasoningPolicy.Effort.valueOf(label.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown reasoning policy: " + label, e);
        }
    }
}