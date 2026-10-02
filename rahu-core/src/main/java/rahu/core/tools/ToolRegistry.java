package rahu.core.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<Tool> all() {
        return List.copyOf(tools.values());
    }
}
