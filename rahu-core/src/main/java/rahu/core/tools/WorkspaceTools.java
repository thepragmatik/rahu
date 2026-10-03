package rahu.core.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The three read-only dogfood tools (tools.md): bounded list/read/search behind
 * the PathBoundary. Serial execution is the caller's contract; these methods
 * are pure and stateless per call. Results are model-visible safe content —
 * privacy admission still applies upstream of any model dispatch.
 */
public final class WorkspaceTools {

    private static final int MAX_LIST_ENTRIES = 500;
    private static final int MAX_LIST_DEPTH = 3;
    /**
     * The documented default cap, used when {@code tools.resultBytes} is absent.
     *
     * <p>This used to be the cap ITSELF rather than the default, which is why
     * {@code tools.resultBytes} was parsed, schema-exposed and never applied: an
     * operator who set {@code resultBytes: 2048} still got 64 KiB with no warning.
     * configuration.md:24 lists it as a real {@code tools} key and tools.md:5
     * requires a descriptor to carry a "result byte limit", so the configured
     * value has to be the one that bites.
     */
    public static final int DEFAULT_RESULT_BYTES = 64 * 1024;

    private static final int MAX_READ_LINES = 1000;
    private static final int MAX_SEARCH_MATCHES = 100;

    /**
     * Bytes scanned from any ONE file during {@code workspace.search}.
     *
     * <p>Deliberately separate from {@code resultBytes}: this bounds how much of a
     * file the search reads while matching, whereas {@code resultBytes} bounds the
     * result handed back to the model. Conflating them would make a small configured
     * result cap silently change which matches a search can FIND, rather than only
     * how much it reports - a search would stop matching text it was entitled to look
     * at, and report a clean "no matches" instead.
     */
    private static final int MAX_SEARCH_BYTES_PER_FILE = 64 * 1024;

    private final PathBoundary boundary;
    private final int resultBytes;

    public WorkspaceTools(PathBoundary boundary) {
        this(boundary, DEFAULT_RESULT_BYTES);
    }

    /**
     * @param resultBytes the per-result byte cap ({@code tools.resultBytes}). Must
     *     be positive: a zero or negative cap would silently truncate every result
     *     to nothing, which reads as "the file is empty" rather than as a
     *     misconfiguration. Rejected here so the operator finds out at startup
     *     instead of wondering why every tool call comes back blank.
     */
    public WorkspaceTools(PathBoundary boundary, int resultBytes) {
        if (resultBytes <= 0) {
            throw new IllegalArgumentException(
                "tools.resultBytes must be positive, got " + resultBytes);
        }
        this.boundary = boundary;
        this.resultBytes = resultBytes;
    }

    /** Dispatches a validated tool call, enforcing the A09 call-ID contract. */
    public ToolResult executeToolCall(String callId, String toolName, String argumentsJson,
        ToolCallLog log) {
        return executeToolCall(callId, toolName, argumentsJson, log, null);
    }

    /**
     * Dispatches against a registry as well as the built-in workspace tools.
     *
     * <p>AUDIT-2026-10-03-x: this overload is what makes A26's extension path real.
     * The only dispatch was a hardcoded three-case switch that never consulted the
     * registry, so a tool could be registered, advertised to the provider and still
     * come back {@code unknown tool}. The registry is consulted only for names the
     * switch does not handle, so the shipped tools keep their exact behaviour and an
     * absent registry degrades to the previous semantics rather than to a null error.
     *
     * <p>Reaching a registered tool's executor means {@link ToolRegistry#of} already
     * admitted it as READ_ONLY — registration is the only way in, and it refuses
     * every other effect class.
     */
    public ToolResult executeToolCall(String callId, String toolName, String argumentsJson,
        ToolCallLog log, ToolRegistry registry) {
        String canonical = CanonicalJson.canonicalize(argumentsJson);
        ToolResult cached = log.lookup(callId, canonical);
        if (cached != null) {
            return cached;
        }
        ToolResult result = invoke(toolName, canonical, registry);
        log.record(callId, canonical, result);
        return result;
    }

    private ToolResult invoke(String toolName, String canonicalArgs, ToolRegistry registry) {
        SimpleArgs args = SimpleArgs.parse(canonicalArgs);
        var builtin = switch (toolName) {
            case "workspace.list" -> list(args.string("directory"), args.integer("depth"));
            case "workspace.read" -> read(args.string("path"),
                args.integer("fromLine"), args.integer("toLine"));
            case "workspace.search" -> search(args.string("text"), args.string("directory"));
            default -> null;
        };
        if (builtin != null) {
            return builtin;
        }
        var registered = registry == null ? Optional.<Tool>empty() : registry.find(toolName);
        if (registered.isEmpty()) {
            return ToolResult.invalid("unknown tool: " + toolName);
        }
        return registered.get().execute(canonicalArgs, boundary);
    }

    /** Lists workspace paths, depth 0-3, sorted, capped at 500 entries. */
    public ToolResult list(String directory, Integer depth) {
        int d = depth == null ? 2 : depth;
        if (d < 0 || d > MAX_LIST_DEPTH) {
            return ToolResult.invalid("depth must be within 0-3");
        }
        try {
            Path dir = boundary.resolve(directory == null ? "." : directory);
            if (!Files.isDirectory(dir)) {
                return ToolResult.invalid("not a directory within the workspace");
            }
            List<String> collected = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(dir, d + 1)) {
                walk.filter(p -> !p.equals(dir))
                    .filter(p -> !isExcludedPath(p))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_LIST_ENTRIES + 1L)
                    .forEach(p -> collected.add(render(p, dir)));
            }
            boolean truncated = collected.size() > MAX_LIST_ENTRIES;
            List<String> out = truncated ? collected.subList(0, MAX_LIST_ENTRIES) : collected;
            String body = String.join("\n", out) + (truncated
                ? "\n[truncated: entry limit " + MAX_LIST_ENTRIES + " reached]" : "");
            boolean byteTruncated = body.getBytes(StandardCharsets.UTF_8).length > resultBytes;
            if (byteTruncated) {
                body = new String(body.getBytes(StandardCharsets.UTF_8), 0, resultBytes,
                        StandardCharsets.UTF_8)
                    + "\n[truncated: tools.resultBytes " + resultBytes + " reached]\n";
            }
            return ToolResult.success(body, truncated || byteTruncated);
        } catch (PathBoundary.BoundaryViolation e) {
            return ToolResult.invalid(e.reason());
                } catch (UncheckedIOException e) {
            // Files.walk wraps a directory-read failure in UncheckedIOException, a
            // RuntimeException that catch (IOException) cannot see. Any
            // permission-restricted directory in a workspace used to abort the call.
            return ToolResult.failed("list I/O failed");
        } catch (IOException e) {
            return ToolResult.failed("list I/O failed");
        }
    }

    /** Reads a UTF-8 file, optional 1-based line range, capped at 64 KiB / 1000 lines. */
    public ToolResult read(String path, Integer fromLine, Integer toLine) {
        try {
            Path file = boundary.resolve(path);
            if (!boundary.isRegularFile(file)) {
                return ToolResult.invalid("not a regular file within the workspace");
            }
            byte[] raw = Files.readAllBytes(file);
            boolean byteTruncated = false;
            if (raw.length > resultBytes) {
                raw = java.util.Arrays.copyOf(raw, resultBytes);
                byteTruncated = true;
            }
            String text = new String(raw, StandardCharsets.UTF_8);
            List<String> lines = text.lines().toList();

            int from = fromLine == null ? 1 : Math.max(1, fromLine);
            int to = toLine == null ? lines.size() : Math.min(lines.size(), toLine);
            if (from > lines.size()) {
                // Not a successful empty result: the caller asked for lines that do
                // not exist. Reporting success-with-empty-content here made an
                // out-of-range read byte-identical to an empty file, so the model
                // could not tell "this file has no content" from "you asked past the
                // end" and could not correct its next request. Line counts are not
                // sensitive, so the message is safe to surface.
                return ToolResult.invalid("requested line range " + from + "-" + to
                    + " is past the end of a " + lines.size() + "-line file");
            }
            if (from > to) {
                return ToolResult.invalid("requested line range " + from + "-" + to
                    + " is inverted (fromLine must not exceed toLine)");
            }
            int end = Math.min(to, from + MAX_READ_LINES - 1);
            boolean lineTruncated = end < to;
            StringBuilder sb = new StringBuilder();
            for (int i = from - 1; i < end; i++) {
                sb.append(lines.get(i)).append('\n');
            }
            boolean truncated = byteTruncated || lineTruncated;
            if (truncated) {
                sb.append("[truncated: read limit reached (" + resultBytes
                    + " bytes / " + MAX_READ_LINES + " lines)]\n");
            }
            return ToolResult.success(sb.toString(), truncated);
        } catch (PathBoundary.BoundaryViolation e) {
            return ToolResult.invalid(e.reason());
        } catch (IOException e) {
            return ToolResult.failed("read I/O failed");
        }
    }

    /** Literal substring search over UTF-8 files; no regex/shell semantics. */
    public ToolResult search(String needle, String directory) {
        if (needle == null || needle.isEmpty()) {
            return ToolResult.invalid("empty search text");
        }
        try {
            Path dir = boundary.resolve(directory == null ? "." : directory);
            if (!Files.isDirectory(dir)) {
                return ToolResult.invalid("not a directory within the workspace");
            }
            List<String> hits = new ArrayList<>();
            boolean truncated = false;
            boolean skippedUnreadable = false;
            try (Stream<Path> walk = Files.walk(dir, 6)) {
                var it = walk.filter(Files::isRegularFile)
                    .filter(p -> !isExcludedPath(p))
                    .sorted(Comparator.comparing(Path::toString))
                    .iterator();
                while (it.hasNext() && hits.size() <= MAX_SEARCH_MATCHES) {
                    Path p = it.next();
                    String content;
                    try {
                        byte[] raw = Files.readAllBytes(p);
                        if (raw.length > MAX_SEARCH_BYTES_PER_FILE) {
                            raw = java.util.Arrays.copyOf(raw, MAX_SEARCH_BYTES_PER_FILE);
                        }
                        content = new String(raw, StandardCharsets.UTF_8);
                    } catch (IOException io) {
                        // Unreadable files are skipped rather than fatal, but the skip
                        // must be visible: without it a search that examined 9 of 10
                        // files is indistinguishable from one that examined all 10,
                        // and "no matches" would report absence of evidence as
                        // evidence of absence.
                        skippedUnreadable = true;
                        continue;
                    }
                    String[] lines = content.split("\n", -1);
                    for (int i = 0; i < lines.length && hits.size() <= MAX_SEARCH_MATCHES; i++) {
                        if (lines[i].contains(needle)) {
                            if (hits.size() == MAX_SEARCH_MATCHES) {
                                truncated = true;
                                break;
                            }
                            hits.add(render(p, dir) + ":" + (i + 1) + ": " + lines[i].strip());
                        }
                    }
                }
            }
            String body = String.join("\n", hits) + (truncated
                ? "\n[truncated: match limit " + MAX_SEARCH_MATCHES + " reached]\n" : "")
                + (skippedUnreadable ? "\n[note: some entries were unreadable and not searched]\n" : "");
            // tools.md:5 bounds the RESULT, so the configured cap applies here as it
            // does to read. The disclosure line is kept even when the byte cap is
            // what truncated, so the model is never handed a short result that looks
            // like a complete one.
            boolean byteTruncated = body.getBytes(StandardCharsets.UTF_8).length > resultBytes;
            if (byteTruncated) {
                body = new String(body.getBytes(StandardCharsets.UTF_8), 0, resultBytes,
                        StandardCharsets.UTF_8)
                    + "\n[truncated: tools.resultBytes " + resultBytes + " reached]\n";
            }
            return ToolResult.success(body, truncated || skippedUnreadable || byteTruncated);
        } catch (PathBoundary.BoundaryViolation e) {
            return ToolResult.invalid(e.reason());
                } catch (UncheckedIOException e) {
            return ToolResult.failed("search I/O failed");
        } catch (IOException e) {
            return ToolResult.failed("search I/O failed");
        }
    }

    private boolean isExcludedPath(Path p) {
        try {
            boundary.resolve(boundary.root().relativize(p).toString());
            return false;
        } catch (PathBoundary.BoundaryViolation e) {
            return true;
        }
    }


    private static String render(Path p, Path base) {
        Path rel = base.relativize(p);
        return Files.isDirectory(p) ? rel + "/" : rel.toString();
    }
}
