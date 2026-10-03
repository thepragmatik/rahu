package rahu.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import rahu.cli.trace.RunTracer;

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

    /**
     * Where the run directory goes, relative to the working directory.
     *
     * <p>Package-private and settable so {@link DemoCommandTest} can assert on the
     * trace without writing into the repository. An earlier version of that test used
     * {@code @TempDir} and silently ignored it - the JVM caches its working
     * directory, so {@code user.dir} cannot be redirected - which meant the "isolated"
     * tests were writing real run directories into the repo. Seeding this instead
     * makes the isolation real. It defaults to the shipped behaviour.
     */
    static Path traceRoot = Path.of(".rahu", "runs");

    @Override
    public Integer call() throws Exception {
        long startNs = System.nanoTime();
        String runId = UUID.randomUUID().toString();

        // AUDIT-2026-10-03-j. This used to hand-assemble the JSONL envelope with a
        // private event() helper, which meant the demo trace was a SECOND,
        // independently-maintained writer for the same four event types. It had
        // already drifted: it omitted turnIndex, degraded and excludedCandidates,
        // and encoded usage as the string "unavailable" where the reader expects a
        // counters object. Nothing caught it, because nothing compared the two.
        //
        // So demo now emits through RunTracer - the same path a real turn uses. The
        // demo's job is to show what a real run looks like, and it cannot do that
        // while writing a different dialect.
        //
        // Usage is passed as null, which RunTracer encodes as explicit JSON nulls:
        // the demo made no model call, so "unobserved" is the honest claim and 0
        // would be a fabricated measurement. DemoCommandTest asserts exactly this,
        // so the distinction is checked rather than merely intended.
        try (RunTracer tracer = new RunTracer(traceRoot, runId, "offline-demo")) {
            tracer.runStarted(0, "synthetic", "synthetic");
            tracer.routeResolved("fast@low", "fast@low", "shadow", false, null, 0);
            tracer.modelCompleted("demo-fast", "demo-fast", "stop", null, null, null);
            tracer.runTerminated("ANSWER_COMPLETE", 1);
            if (tracer.failed()) {
                throw new IllegalStateException("demo trace write failed; refusing to "
                    + "report a run whose trace is incomplete");
            }
        }

        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        out.println("Rahu offline demo: routing kernel composed; no network calls were made.");
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        err.println("Route fast@low · shadow (suggested fast@low)");
        err.printf("Cost unavailable (offline demo) · 1 generation step · %d.%03d s · trace %s%n",
            elapsedMs / 1000L, elapsedMs % 1000L, runId);
        return 0;
    }
}
