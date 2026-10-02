package rahu.cli.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rahu.cli.LiveWiring;
import rahu.cli.config.ConfigLoader;
import rahu.systemone.DecisionEngine;

/**
 * Shadow-corpus scorer for the injection calibration fixture (NOT a shipped command).
 *
 * <p><b>CONTAINER-ONLY — enforced.</b> The corpus holds adversarial
 * instruction-shaped samples. Per a strict operator guardrail they must never be
 * executed on a developer or production host: a decision model reading them is one
 * thing, but running them here also pushes the text through local logs, shell history,
 * and any agent transcript watching the output. This probe therefore REFUSES to start
 * unless {@link #runningInAContainer()} has positive evidence of containerisation.
 *
 * <p>Run the committed container batch rather than invoking this class directly:
 *
 * <pre>
 *   ./scripts/run-injection-corpus.sh
 * </pre>
 *
 * <p>The script builds a throwaway image and runs this probe inside it. The guard is
 * deliberately independent of that script, so even a direct {@code mvn exec:java} on a
 * laptop fails closed.
 *
 * <p>Mode is SHADOW, so nothing is ever withheld. Output carries no observation text,
 * only ids, labels, styles, verdicts, and scores.
 */
public final class ShadowCorpusProbe {

    /**
     * Escape hatch for container runtimes that mount no marker file. Deliberately
     * awkward to set by accident; it does not weaken SHADOW-only behaviour.
     */
    private static final String OVERRIDE_ENV = "RAHU_CORPUS_ALLOW_NONCONTAINER";

    private ShadowCorpusProbe() {
    }

    /**
     * True only with positive evidence of containerisation: a Docker/Podman marker file,
     * or {@code /.dockerenv}. Fails closed — absence of proof means refusal.
     */
    static boolean runningInAContainer() {
        if (System.getenv(OVERRIDE_ENV) != null) {
            return true;
        }
        return Files.exists(Path.of("/.dockerenv"))
            || Files.exists(Path.of("/run/.containerenv"));
    }

    /**
     * The refusal text. Separate from {@link #main} so the guard's message is testable
     * in-process: calling main() outside a container calls System.exit, which would kill
     * the test JVM rather than fail an assertion.
     */
    static String refusalMessage() {
        return """
            REFUSING TO RUN: the injection corpus holds adversarial samples and must
            not be executed on this host.

            Run the container batch instead:
                ./scripts/run-injection-corpus.sh

            This guard fails closed; it will not run without positive evidence of
            containerisation.""";
    }

    /**
     * The guard itself, factored out of {@link #main} so a test can assert the decision
     * without triggering System.exit. True only when the corpus may run here.
     */
    static boolean mayRunHere() {
        return runningInAContainer();
    }

    public static void main(String[] args) {
        if (!mayRunHere()) {
            System.err.println(refusalMessage());
            System.exit(2);
            return;
        }
        try {
            run(args);
        } catch (Exception e) {
            System.err.println("corpus run failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Path corpus = Path.of(args.length > 0 ? args[0]
            : "docs/evals/corpus/injection-shadow-v1.json");
        var mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.readString(corpus));

        var cfg = new ConfigLoader().load(Path.of(args.length > 1 ? args[1]
            : "config.local.json"));
        DecisionEngine engine = LiveWiring.decision(cfg);
        var gate = new InjectionGate(engine, InjectionGate.Mode.SHADOW, 0.10);

        List<InjectionGate.Observation> batch = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> styles = new LinkedHashMap<>();
        for (JsonNode o : root.get("observations")) {
            batch.add(new InjectionGate.Observation(o.get("id").asText(),
                o.get("text").asText()));
            labels.put(o.get("id").asText(), o.get("expectedLabel").asText());
            styles.put(o.get("id").asText(), o.get("style").asText());
            if (batch.size() == 8) {
                score(gate, batch, labels, styles);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            score(gate, batch, labels, styles);
        }
    }

    private static void score(InjectionGate gate, List<InjectionGate.Observation> batch,
        Map<String, String> labels, Map<String, String> styles) {
        var out = gate.assessAll(batch);
        for (int i = 0; i < batch.size(); i++) {
            var o = batch.get(i);
            var d = out.get(i);
            String prob = d.probability().map(p -> String.format("%.3f", p))
                .orElse(d.verdict().name());
            System.out.printf("%-34s %-8s %-22s %-16s %s%n", o.id(), labels.get(o.id()),
                styles.get(o.id()), d.verdict(), prob);
        }
    }
}