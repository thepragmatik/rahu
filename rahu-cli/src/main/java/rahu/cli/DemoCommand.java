package rahu.cli;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * A01: deterministic offline run using built-in synthetic fixtures. Zero external
 * calls; writes a metadata-only JSONL trace under .rahu/runs/.
 */
@Command(name = "demo",
    mixinStandardHelpOptions = true,
    description = "Deterministic offline run using built-in synthetic fixtures.")
public final class DemoCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        long startNs = System.nanoTime();
        String runId = UUID.randomUUID().toString();
        Path traceDir = Path.of(".rahu", "runs", runId);
        Files.createDirectories(traceDir);
        Path trace = traceDir.resolve(rahu.cli.trace.TraceFiles.EVENTS);

        StringBuilder sb = new StringBuilder();
        event(sb, 1, runId, startNs, "RunStarted",
            "\"sessionId\":\"offline-demo\",\"configHash\":\"synthetic\",\"catalogHash\":\"synthetic\"");
        event(sb, 2, runId, startNs, "RouteResolved",
            "\"mode\":\"shadow\",\"suggested\":\"fast@low\",\"executed\":\"fast@low\",\"fallbackCause\":null");
        event(sb, 3, runId, startNs, "ModelCompleted",
            "\"requestedModel\":\"demo-fast\",\"observedModel\":\"demo-fast\",\"effort\":\"low\","
                + "\"finishReason\":\"stop\",\"usage\":\"unavailable\"");
        event(sb, 4, runId, startNs, "RunTerminated",
            "\"reason\":\"ANSWER_COMPLETE\",\"generationSteps\":1,\"compactions\":0");
        Files.writeString(trace, sb.toString(), StandardCharsets.UTF_8);

        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        out.println("Rahu offline demo: routing kernel composed; no network calls were made.");
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        err.println("Route fast@low · shadow (suggested fast@low)");
        err.printf("Cost unavailable (offline demo) · 1 generation step · %d.%03d s · trace %s%n",
            elapsedMs / 1000L, elapsedMs % 1000L, runId);
        return 0;
    }

    private static void event(StringBuilder sb, int sequence, String runId, long startNs,
        String type, String payload) {
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        sb.append("{\"schemaVersion\":1,\"runId\":\"").append(runId)
            .append("\",\"sequence\":").append(sequence)
            .append(",\"timestamp\":\"").append(Instant.now())
            .append("\",\"elapsedMs\":").append(elapsedMs)
            .append(",\"type\":\"").append(type)
            .append("\",\"payload\":{").append(payload).append("}}\n");
    }
}
