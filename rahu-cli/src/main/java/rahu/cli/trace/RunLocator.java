package rahu.cli.trace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Locating a run directory and judging whether its trace can support a claim.
 *
 * <p>AUDIT-2026-10-03-e/f. {@code rahu replay} and {@code rahu trace inspect}
 * (cli.md:13-14) both need the same two answers — "which directory is this run?"
 * and "does this trace support the claim you are about to make?" — and both were
 * going to need them implemented separately.
 *
 * <p>Duplication here would be a correctness risk rather than a style preference.
 * The integrity predicate is the one place where the codebase decides whether
 * evidence is trustworthy; two copies would drift, and the copy that drifts
 * looser is the one that silently starts approving incomplete traces. Extracted so
 * there is one definition, and {@code ReplayCommand} is refactored onto it, so
 * {@code replay} cannot be more permissive than {@code trace inspect} on the same
 * file.
 *
 * <p>Neither method performs I/O beyond reading {@code events.jsonl}. There is no
 * provider client, no dispatch and no write in this class (A11).
 */
public final class RunLocator {

    /**
     * Locates the run directory from either a run directory or a trace root.
     *
     * <p>Accepting the parent is convenience, not guesswork: the id is resolved by
     * requiring a directory that actually contains {@code events.jsonl}, and if
     * several qualify the caller is told rather than handed one of them. Picking
     * the first would report on a different run than the operator asked for, and
     * for a trace command that error is the whole output.
     */
    public static Optional<Path> locate(Path runPath) {
        if (runPath == null) {
            return Optional.empty();
        }
        if (Files.isRegularFile(runPath.resolve(TraceFiles.EVENTS))) {
            return Optional.of(runPath);
        }
        if (Files.isDirectory(runPath.resolve(TraceFiles.EVENTS))) {
            return Optional.of(runPath);
        }
        if (Files.isRegularFile(runPath)) {
            // A path to events.jsonl itself. Acceptable and unambiguous.
            return Optional.of(runPath.getParent() == null ? runPath : runPath.getParent());
        }
        List<Path> found = new ArrayList<>();
        if (Files.isDirectory(runPath)) {
            try (var stream = Files.list(runPath)) {
                stream.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve(TraceFiles.EVENTS)))
                    .forEach(found::add);
            } catch (IOException e) {
                System.err.println("cannot list " + runPath + ": " + e.getMessage());
            }
        }
        if (found.size() == 1) {
            return Optional.of(found.get(0));
        }
        if (found.size() > 1) {
            System.err.println(runPath + " holds " + found.size() + " runs; pass the run"
                + " directory explicitly. Candidates: "
                + found.stream().map(p -> p.getFileName().toString()).sorted().toList());
        }
        return Optional.empty();
    }

    /**
     * Detects a trace that cannot support a faithful claim about a completed run.
     *
     * <p>Checks, all about completeness rather than content:
     * <ul>
     *   <li>events.jsonl must exist;</li>
     *   <li>it must parse (a corrupt line means the writer died mid-record);</li>
     *   <li>sequence numbers must be strictly increasing from 1 with no gaps — a
     *       gap means events were lost, and a trace with holes cannot support
     *       "this is what happened";</li>
     *   <li>a RunStarted must exist (an empty file is not a run);</li>
     *   <li>a RunTerminated must exist (a run that stopped mid-turn has no final
     *       routing outcome to compare against).</li>
     * </ul>
     *
     * <p>The terminal check is what catches a trace written by a failed sink, which
     * is why A16's "stop without a terminal record" matters here.
     *
     * <p>{@link TraceReader} classifies a truncated final line as INCOMPLETE, which
     * is tolerated here in exactly one way: a trace truncated after its terminal
     * event still describes a completed run, because RunTerminated was written.
     * That distinction is the difference between "lost the tail of a finished run"
     * and "never finished", and collapsing them would refuse honest evidence.
     */
    public static Optional<String> integrityProblem(Path directory) {
        Path events = directory.resolve(TraceFiles.EVENTS);
        if (!Files.isRegularFile(events)) {
            return Optional.of("no " + TraceFiles.EVENTS + " in " + directory.getFileName());
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(events, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.of(TraceFiles.EVENTS + " is unreadable: " + e.getMessage());
        }

        boolean started = false;
        boolean terminated = false;
        int expectedSequence = 1;
        int parsed = 0;
        String parseFailure = null;
        int lastNonBlank = lines.size() - 1;
        while (lastNonBlank >= 0 && lines.get(lastNonBlank).isBlank()) {
            lastNonBlank--;
        }

        for (int i = 0; i <= lastNonBlank; i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node;
            try {
                node = mapper.readTree(line);
            } catch (IOException | RuntimeException e) {
                parseFailure = (i == lastNonBlank)
                    ? "truncated final line (line " + (i + 1) + ")"
                    : "corrupt event at line " + (i + 1);
                break;
            }
            int sequence = node.path("sequence").asInt(0);
            if (sequence != expectedSequence) {
                return Optional.of("sequence " + sequence + " at line " + (i + 1)
                    + " breaks the record: expected " + expectedSequence
                    + "; events are missing, so this trace cannot support a claim about"
                    + " the whole run");
            }
            expectedSequence++;
            parsed++;
            String type = node.path("type").asText("");
            if ("RunStarted".equals(type)) {
                started = true;
            }
            if ("RunTerminated".equals(type)) {
                terminated = true;
            }
        }

        if (parseFailure != null) {
            if (terminated) {
                // The run completed and then the tail was lost. Honest evidence of a
                // completed run; say exactly that rather than failing it.
                return Optional.empty();
            }
            return Optional.of(parseFailure);
        }
        if (parsed == 0) {
            return Optional.of(TraceFiles.EVENTS + " contains no events");
        }
        if (!started) {
            return Optional.of(TraceFiles.EVENTS + " has no RunStarted event");
        }
        if (!terminated) {
            return Optional.of(TraceFiles.EVENTS + " has no RunTerminated event; the run"
                + " did not complete, so it has no final routing outcome to compare"
                + " against");
        }
        return Optional.empty();
    }

    private RunLocator() {
    }
}