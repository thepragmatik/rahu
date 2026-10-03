package rahu.cli.live;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;

import rahu.cli.LiveWiring;
import rahu.cli.config.RahuConfig;
import rahu.core.CurrencyUnit;
import rahu.core.MoneyAmount;
import rahu.core.context.SessionState;
import rahu.core.model.ModelProvider;
import rahu.core.privacy.PrivacyGate;
import rahu.core.privacy.Provenance;
import rahu.core.tools.PathBoundary;
import rahu.core.tools.WorkspaceTools;
import rahu.core.tools.ToolCallLog;
import rahu.core.tools.ToolRegistry;
import rahu.systemone.DecisionEngine;

/**
 * The live assembly shared by {@code rahu run} and {@code rahu chat}.
 *
 * <p>Audit finding AUDIT-2026-10-03-g. {@code ChatCommand.runLive} held a 60-line
 * block that built the provider, decision engine, evidence-gated router, session,
 * path boundary, tool registry and tool loop. {@code rahu run} (cli.md:11) needs
 * exactly the same assembly. Copying it would have produced the shape this repo has
 * already paid for twice - two integrity predicates in AUDIT-f, two capture
 * integrities in AUDIT-e - where the copies drift and the drift is invisible because
 * both are exercised by the same suite. The wiring is where drift is most dangerous:
 * a tool silently left enabled in one command and disabled in the other is a privacy
 * difference, not a refactor.
 *
 * <p>Assembly is separate from <em>use</em>: this builds and validates, and the caller
 * decides what to do with it. That is what lets a test construct the live stack with a
 * fake provider and still exercise the real privacy, cost and trace paths - which is
 * how AUDIT-b's missing privacy gate survived a green suite.
 *
 * <p>Failures are typed rather than returned as codes so the caller can print the
 * operation, cause and next action that cli.md:40 requires, without every command
 * re-deriving the wording.
 */
public final class LiveAssembly {

    /** A refusal to build the live stack, with the message to show the operator. */
    public record Refused(String operation, String message) {
    }

    private LiveAssembly() {
    }

    /**
     * The effective {@code tools.resultBytes} cap.
     *
     * <p>Extracted so the config-to-executor path is TESTABLE on its own. It is not
     * decoration: a mutation that made assembly pass {@link
     * WorkspaceTools#DEFAULT_RESULT_BYTES} instead of the configured value survived
     * every executor test, because those tests construct {@code WorkspaceTools}
     * directly and never go through assembly. Only a test that reads the value the
     * way assembly does can catch a cut in the wiring.
     */
    public static int resultBytes(RahuConfig cfg) {
        Integer configured = cfg.tools().resultBytes();
        if (configured != null) {
            return configured;
        }
        // Only reachable for a hand-built ToolsConfig: ConfigLoader materialises
        // `resultBytes` with asInt(65536), so a loader-built config never yields null.
        // Kept because the record allows it, and a null here must not become a ZERO
        // cap - that would truncate every tool result to nothing while still
        // reporting success.
        return WorkspaceTools.DEFAULT_RESULT_BYTES;
    }

    /**
     * Builds the live stack, or explains why it cannot be built.
     *
     * <p>Order matters and is not incidental: adapters and credentials first (nothing
     * else can be attempted without them), then the evidence-gated router (paid
     * admission is evidence-gated, so an inadmissible pool must refuse here rather
     * than degrade every turn later), then the tool loop.
     */
    public static Result build(RahuConfig cfg, Provenance provenance, String sessionLabel,
        PrintWriters writers) {
        ModelProvider provider;
        DecisionEngine decision;
        try {
            provider = LiveWiring.generation(cfg);
            decision = LiveWiring.decision(cfg);
        } catch (RuntimeException e) {
            return Result.refused("live wiring", e.getMessage());
        }

        // Checked separately from construction: an adapter can be constructed with an
        // absent credential, and the failure would otherwise surface as a provider
        // error AFTER the turn was admitted and traced.
        String keyEnv = cfg.generation().apiKeyEnv();
        if (LiveWiring.keySupplier(keyEnv).get().isEmpty()) {
            return Result.refused("generation credential",
                keyEnv + " is not set; export it or put it in .env (gitignored)");
        }

        ActiveRouter router;
        try {
            router = new ActiveRouter(cfg, rahu.cli.live.ProfileEvidence.load(cfg,
                Path.of("docs/generated/model-profiles.json")));
        } catch (IOException e) {
            return Result.refused("profile evidence", e.getMessage()
                + " — run `rahu config validate --live-check` to fetch it");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.refused("profile evidence lookup", "interrupted");
        } catch (IllegalStateException e) {
            return Result.refused("routing", e.getMessage());
        }

        var boundary = new PathBoundary(Path.of(cfg.tools().root()), cfg.tools().exclusions());
        var loop = toolLoop(cfg, boundary, provider, decision, provenance);

        return Result.of(new Stack(cfg, provider, decision, router,
            newSession(cfg, sessionLabel), boundary, loop, provenance));
    }

    /**
     * The one place a live {@link ToolLoop} is constructed.
     *
     * <p>Extracted from {@link #build} so the config-to-executor wiring is testable
     * without a credential, a catalog fetch and a profile-evidence file. A helper
     * that only computes the cap was NOT enough: a mutation replacing
     * {@code resultBytes(cfg)} with the default at the original call site passed
     * every test, because nothing could observe what assembly handed the loop. The
     * cap is now passed through this method and read back off the built loop.
     */
    public static ToolLoop toolLoop(RahuConfig cfg, PathBoundary boundary, ModelProvider provider,
        DecisionEngine decision, Provenance provenance) {
        return new ToolLoop(workspaceRegistry(cfg, boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(), provenance,
            cfg.tools().maxCallsPerStep() == null ? 8 : cfg.tools().maxCallsPerStep(),
            new InjectionGate(decision, injectionMode(cfg.injection()),
                cfg.injection().thresholdOrDefault()),
            new SearchReranker(decision, rerankMode(cfg.search()),
                cfg.search().maxCandidatesOrDefault()),
            resultBytes(cfg));
    }

    /** Where the assembly writes; kept as a pair so no command invents its own split. */
    public record PrintWriters(java.io.PrintWriter out, java.io.PrintWriter err) {
    }

    public record Stack(
        RahuConfig cfg,
        ModelProvider provider,
        DecisionEngine decision,
        ActiveRouter router,
        SessionState session,
        PathBoundary boundary,
        ToolLoop loop,
        Provenance provenance) {

        /** A driver bound to this assembly; the caller owns its lifetime. */
        public LiveTurnDriver driver(PrintWriters writers) {
            return driver(writers, null);
        }

        /**
         * A driver for this assembly.
         *
         * <p>{@code slash} is {@code null} for {@code run}: a single bounded task has
         * no interactive commands (cli.md:15), so a {@code /}-prefixed task is the task
         * TEXT. It must be null rather than a lambda - a non-null handler makes the
         * driver treat slash lines as commands and skip the turn, which produced a
         * silent exit 0 with no answer and no trace.
         */
        public LiveTurnDriver driver(PrintWriters writers,
            java.util.function.Function<String, Integer> slash) {
            return new LiveTurnDriver(cfg, provider, decision, router, session, provenance,
                loop, slash, writers.out(), writers.err());
        }

        /**
         * The same driver with a slash-command handler attached.
         *
         * <p>{@code chat} needs one and {@code run} does not, but the driver is
         * otherwise identical. {@code run} is specified as a single bounded task with
         * no interactive commands (cli.md:15), so it takes the driver as built - the
         * handler is only consulted for a line starting with {@code /}, which a
         * non-interactive run never produces.
         */
        /**
         * The router's mode name, for the startup banner.
         *
         * <p>{@code name()} rather than the enum constant directly: a caller printing
         * a mode must not be able to accidentally emit a different value than the one
         * the router resolves under.
         */
        public String routerMode() {
            return router.mode().name();
        }

        public ActiveRouter router() {
            return router;
        }

        public SessionState session() {
            return session;
        }

    }

    /** Either the built stack or the refusal, so the caller cannot proceed on a null. */
    public sealed interface Result {

        record Built(Stack assembly) implements Result {
        }

        record Failure(Refused refusal) implements Result {
        }

        static Result of(Stack assembly) {
            return new Built(assembly);
        }

        static Result refused(String operation, String message) {
            return new Failure(new Refused(operation, message));
        }
    }

    // ------------------------------------------------------------- helpers

    /**
     * The registry for a live turn: the read-only workspace tools, narrowed to the
     * operator's {@code tools.enabled} list.
     *
     * <p>Audit finding F-4: {@code tools.enabled} and {@code tools.exclusions} were both
     * parsed into config and then never read, so an operator who disabled a tool still
     * got it advertised to the model. Narrowed here, once, for every command.
     */
    public static ToolRegistry workspaceRegistry(RahuConfig cfg, PathBoundary boundary) {
        ToolRegistry all = ToolRegistry.withWorkspace(boundary);
        var enabled = cfg.tools().enabled();
        if (enabled == null || enabled.isEmpty()) {
            return all;
        }
        return all.restrictedTo(new LinkedHashSet<>(enabled));
    }

    private static SessionState newSession(RahuConfig cfg, String label) {
        return new SessionState("rahu-" + label + "-" + System.nanoTime(),
            cfg.session().maxTurns(),
            new MoneyAmount(cfg.session().maxCostUsd(), CurrencyUnit.USD));
    }

    /** Config mode to gate mode; ConfigLoader has already refused anything else. */
    private static InjectionGate.Mode injectionMode(RahuConfig.InjectionConfig cfg) {
        return switch (cfg.modeOrDefault()) {
            case "shadow" -> InjectionGate.Mode.SHADOW;
            case "enforce" -> InjectionGate.Mode.ENFORCE;
            default -> InjectionGate.Mode.OFF;
        };
    }

    /** Config mode to rerank mode; ConfigLoader has already refused anything else. */
    private static SearchReranker.Mode rerankMode(RahuConfig.SearchConfig cfg) {
        return switch (cfg.modeOrDefault()) {
            case "shadow" -> SearchReranker.Mode.SHADOW;
            case "enforce" -> SearchReranker.Mode.ENFORCE;
            default -> SearchReranker.Mode.OFF;
        };
    }

    /**
     * Operator-recorded input provenance; anything but an explicit assessment fails
     * closed. Shared so {@code run} and {@code chat} cannot disagree about what
     * "unknown" means - a divergence here would be a privacy divergence.
     */
    public static Provenance provenance(RahuConfig cfg, String inputClassification) {
        String classification = inputClassification == null
            ? cfg.privacy().inputClassification() : inputClassification;
        return "approved-nonsensitive".equals(classification)
            ? new Provenance.ApprovedNonSensitive("operator-classification")
            : Provenance.Unknown.INSTANCE;
    }
}
