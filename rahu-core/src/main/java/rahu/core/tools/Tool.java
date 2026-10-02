package rahu.core.tools;

import java.util.Objects;

/**
 * A registered read-only tool (extensibility.md): descriptor + executor bound
 * at composition. Executors receive the canonical arguments JSON and the
 * workspace boundary; they never bypass the AdmissionPipeline.
 */
public final class Tool {

    @FunctionalInterface
    public interface Executor {

        ToolResult execute(String canonicalArgsJson, PathBoundary boundary);
    }

    private final String name;
    private final String version;
    private final ToolDescriptor descriptor;
    private final Executor executor;

    public Tool(String name, String version, ToolDescriptor descriptor, Executor executor) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(executor, "executor");
        if (!name.equals(descriptor.name())) {
            throw new IllegalArgumentException("tool name must match descriptor name");
        }
        this.name = name;
        this.version = version;
        this.descriptor = descriptor;
        this.executor = executor;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public ToolDescriptor descriptor() {
        return descriptor;
    }

    public ToolResult execute(String canonicalArgsJson, PathBoundary boundary) {
        return executor.execute(canonicalArgsJson, boundary);
    }
}
