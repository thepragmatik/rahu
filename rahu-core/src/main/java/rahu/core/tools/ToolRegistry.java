package rahu.core.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Frozen tool registry (extensibility.md): composed once at session start,
 * immutable afterwards, duplicate names rejected, read-only effects only.
 * Registries expose descriptors; the AdmissionPipeline owns authority. The
 * workspace boundary binds at composition via {@link #withWorkspace}.
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools;

    private ToolRegistry(Map<String, Tool> tools) {
        this.tools = Map.copyOf(tools);
    }

    public static ToolRegistry of(Tool... entries) {
        Map<String, Tool> map = new LinkedHashMap<>();
        for (Tool t : entries) {
            if (map.containsKey(t.name())) {
                throw new IllegalArgumentException(
                    "duplicate tool registration: " + t.name());
            }
            map.put(t.name(), t);
        }
        return new ToolRegistry(map);
    }

    /**
     * The three shipped read-only workspace tools bound to a workspace root.
     * Real JSON schemas ride the descriptors; executors route through
     * WorkspaceTools, which enforces the PathBoundary per call.
     */
    public static ToolRegistry withWorkspace(PathBoundary boundary) {
        WorkspaceTools workspace = new WorkspaceTools(boundary);
        Tool list = new Tool("workspace.list", "1.0.0",
            new ToolDescriptor("workspace.list",
                "List workspace paths under a directory, depth 0-3, sorted, bounded.",
                "{\"type\":\"object\",\"properties\":{\"directory\":{\"type\":\"string\","
                    + "\"description\":\"workspace-relative directory\"},\"depth\":"
                    + "{\"type\":\"integer\",\"minimum\":0,\"maximum\":3}},"
                    + "\"additionalProperties\":false}"),
            (args, b) -> {
                SimpleArgs a = SimpleArgs.parse(args);
                return workspace.list(a.string("directory"), a.integer("depth"));
            });
        Tool read = new Tool("workspace.read", "1.0.0",
            new ToolDescriptor("workspace.read",
                "Read a UTF-8 file's lines; optional 1-based range; bounded.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},"
                    + "\"fromLine\":{\"type\":\"integer\",\"minimum\":1},"
                    + "\"toLine\":{\"type\":\"integer\",\"minimum\":1}},"
                    + "\"required\":[\"path\"],\"additionalProperties\":false}"),
            (args, b) -> {
                SimpleArgs a = SimpleArgs.parse(args);
                return workspace.read(a.string("path"), a.integer("fromLine"),
                    a.integer("toLine"));
            });
        Tool search = new Tool("workspace.search", "1.0.0",
            new ToolDescriptor("workspace.search",
                "Literal substring search over workspace files; bounded; no regex.",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\","
                    + "\"minLength\":1},\"directory\":{\"type\":\"string\"}},"
                    + "\"required\":[\"text\"],\"additionalProperties\":false}"),
            (args, b) -> {
                SimpleArgs a = SimpleArgs.parse(args);
                return workspace.search(a.string("text"), a.string("directory"));
            });
        return ToolRegistry.of(list, read, search);
    }

    /**
     * Descriptor-only view for composition checks and tests: built-in tool
     * names/descriptors without binding a workspace root.
     */
    public static ToolRegistry builtins() {
        return withWorkspace(new PathBoundary(java.nio.file.Path.of(".").toAbsolutePath()));
    }

    /**
     * A registry narrowed to {@code permittedNames}, for an advisory tool-relevance
     * judgment (tools.md line 7: the generation model receives only the permitted
     * relevant tools).
     *
     * <p>The narrowing can only REMOVE. A name that is not registered throws rather
     * than being silently ignored, because a relevance judgment naming an unknown
     * tool is a protocol error, and silently dropping it would let a decision widen
     * the set by accident. An empty name set yields an empty registry, which the
     * caller — not this method — must treat as "keep the full set": an empty judgment
     * is indistinguishable from an unjudgeable one.
     */
    public ToolRegistry restrictedTo(Set<String> permittedNames) {
        List<Tool> kept = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (permittedNames.contains(tool.name())) {
                kept.add(tool);
            }
        }
        if (kept.size() != permittedNames.size()) {
            Set<String> known = new LinkedHashSet<>();
            tools.values().forEach(t -> known.add(t.name()));
            permittedNames.stream().filter(n -> !known.contains(n)).findFirst().ifPresent(
                unknown -> {
                    throw new IllegalArgumentException(
                        "tool-relevance judgment named an unregistered tool: " + unknown);
                });
        }
        return ToolRegistry.of(kept.toArray(new Tool[0]));
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<Tool> all() {
        return List.copyOf(tools.values());
    }
}
