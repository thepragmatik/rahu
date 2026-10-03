package rahu.core.tools;

import java.util.Objects;
import rahu.core.authority.EffectClass;

/**
 * A registered tool (extensibility.md): descriptor + executor bound at composition,
 * together with the {@link EffectClass} it was admitted as. Executors receive the
 * canonical arguments JSON and the workspace boundary.
 *
 * <p>A26 previously had no effect class here at all, so "effectful registrations are
 * denied" could not be enforced by the registry. The absence was masked because no
 * registered tool could execute: the run loop dispatched through its own hardcoded
 * switch in {@link WorkspaceTools} and never consulted the registry. Now that
 * registered tools DO execute, {@link ToolRegistry#of} refuses any non-read-only
 * effect, and this field is what it refuses on.
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
    private final EffectClass effect;

    /**
     * Registers a READ_ONLY tool. This is the only overload the shipped code uses, so
     * a tool that says nothing about its effects cannot be anything but read-only:
     * declaring an effect requires naming it explicitly, and only
     * {@link ToolRegistry#of} may admit a non-read-only one (and currently admits
     * none).
     */
    public Tool(String name, String version, ToolDescriptor descriptor, Executor executor) {
        this(name, version, descriptor, executor, EffectClass.READ_ONLY);
    }

    /**
     * @param effect the effect class this tool was admitted as. Null is rejected here
     *     rather than at registration, so the guard does not depend on how the later
     *     comparison happens to be written: {@code effect != READ_ONLY} already
     *     rejects null, but {@code !READ_ONLY.equals(effect)} reads as a deliberate
     *     "unspecified is fine" check and would let a null through on some future edit.
     */
    public Tool(String name, String version, ToolDescriptor descriptor, Executor executor,
        EffectClass effect) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(effect, "effect");
        if (!name.equals(descriptor.name())) {
            throw new IllegalArgumentException("tool name must match descriptor name");
        }
        this.name = name;
        this.version = version;
        this.descriptor = descriptor;
        this.executor = executor;
        this.effect = effect;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    /** The effect class this tool was admitted as. Only {@code READ_ONLY} may register. */
    public EffectClass effect() {
        return effect;
    }

    public ToolDescriptor descriptor() {
        return descriptor;
    }

    public ToolResult execute(String canonicalArgsJson, PathBoundary boundary) {
        return executor.execute(canonicalArgsJson, boundary);
    }
}
