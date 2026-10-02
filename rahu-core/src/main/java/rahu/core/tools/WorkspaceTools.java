package rahu.core.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private static final int MAX_READ_BYTES = 64 * 1024;
    private static final int MAX_READ_LINES = 1000;
    private static final int MAX_SEARCH_MATCHES = 100;
    private static final int MAX_SEARCH_BYTES = 64 * 1024;

    private final PathBoundary boundary;

    public WorkspaceTools(PathBoundary boundary) {
        this.boundary = boundary;
    }

    /** Dispatches a validated tool call, enforcing the A09 call-ID contract. */
    public ToolResult executeToolCall(String callId, String toolName, String argumentsJson,
        ToolCallLog log) {
        String canonical = CanonicalJson.canonicalize(argumentsJson);
        ToolResult cached = log.lookup(callId, canonical);
        if (cached != null) {
            return cached;
        }
        ToolResult result = invoke(toolName, canonical);
        log.record(callId, canonical, result);
        return result;
    }

    private ToolResult invoke(String toolName, String canonicalArgs) {
        SimpleArgs args = SimpleArgs.parse(canonicalArgs);
        return switch (toolName) {
            case "workspace.list" -> list(args.string("directory"), args.integer("depth"));
            case "workspace.read" -> read(args.string("path"),
                args.integer("fromLine"), args.integer("toLine"));
            case "workspace.search" -> search(args.string("text"), args.string("directory"));
            default -> ToolResult.invalid("unknown tool: " + toolName);
        };
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
            return ToolResult.success(body, truncated);
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
            if (raw.length > MAX_READ_BYTES) {
                raw = java.util.Arrays.copyOf(raw, MAX_READ_BYTES);
                byteTruncated = true;
            }
            String text = new String(raw, StandardCharsets.UTF_8);
            List<String> lines = text.lines().toList();

            int from = fromLine == null ? 1 : Math.max(1, fromLine);
            int to = toLine == null ? lines.size() : Math.min(lines.size(), toLine);
            if (from > to || from > lines.size()) {
                return ToolResult.success("", false);
            }
            int end = Math.min(to, from + MAX_READ_LINES - 1);
            boolean lineTruncated = end < to;
            StringBuilder sb = new StringBuilder();
            for (int i = from - 1; i < end; i++) {
                sb.append(lines.get(i)).append('\n');
            }
            boolean truncated = byteTruncated || lineTruncated;
            if (truncated) {
                sb.append("[truncated: read limit reached (64 KiB / 1000 lines)]\n");
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
                        if (raw.length > MAX_SEARCH_BYTES) {
                            raw = java.util.Arrays.copyOf(raw, MAX_SEARCH_BYTES);
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
                ? "\n[truncated: match limit " + MAX_SEARCH_MATCHES + " reached]" : "")
                + (skippedUnreadable ? "\n[note: some entries were unreadable and not searched]" : "");
            return ToolResult.success(body, truncated || skippedUnreadable);
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
