package rahu.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import rahu.cli.live.ExitCode;

/**
 * {@code rahu trace inspect RUN_PATH} (cli.md:13): inspect run metadata and
 * completeness.
 *
 * <p>AUDIT-2026-10-03-f. cli.md has documented this since the specs were written.
 * It did not exist, which is the same finding as {@code replay} (AUDIT-e) one
 * line apart in the same table.
 *
 * <p>This is the read-only view of a run's evidence, and it exists because the
 * other trace surfaces answer questions about the <em>run</em>, not about the
 * <em>evidence</em>. It reports which events a trace holds, whether the record is
 * complete, and whether the run left a routing outcome behind — so an operator
 * can tell "the run degraded" apart from "the trace is missing the record of what
 * the run did" before trusting anything downstream.
 *
 * <h2>What it deliberately does not do</h2>
 * <ul>
 *   <li><b>Dump payloads.</b> cli.md:50 requires that local inspection "must not
 *       dump protected payloads by default". This command reads payloads only to
 *       lift a small allowlist of scalar metadata out of them (route ids,
 *       terminal reason, token counts) and never prints a free-text field.
 *       {@code --verbose} adds event types and timings, still no payloads.</li>
 *   <li><b>No network, no tools.</b> Reading a run directory is the only I/O. The
 *       same guarantee A11 places on {@code replay}, and the same structural
 *       proof: no provider client appears in this class.</li>
 *   <li><b>Not a verdict.</b> An INCOMPLETE trace is reported as incomplete and
 *       exits 5, because per ExitCode.TRACE_INTEGRITY_FAILURE the answer may be
 *       correct while its evidence is not auditable. It does not say the run
 *       failed — only that this file cannot support a claim about the whole
 *       run.</li>
 * </ul>
 */
@Command(name = "trace",
    mixinStandardHelpOptions = true,
    description = "Inspect a run trace's metadata and completeness. Read-only; makes"
        + " no network or tool calls and does not dump payloads.",
    subcommands = { TraceInspectCommand.class })
public class TraceCommand implements java.util.concurrent.Callable<Integer> {

    @Override
    public Integer call() {
        // `rahu trace` alone lists what it can inspect, so the command is
        // discoverable rather than silently doing nothing.
        System.err.println("usage: rahu trace inspect RUN_PATH [--format text|json]"
            + " [--verbose]");
        return ExitCode.INVALID_INPUT;
    }

}