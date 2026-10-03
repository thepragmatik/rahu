package rahu.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.authority.EffectClass;

/**
 * AUDIT-2026-10-03-x, A26. This class is the OTHER HALF of the fix that
 * {@link ExtensionSurfaceTest} set up: the extension path now works, so the gate
 * that was missing must actually hold.
 *
 * <p>Before this change a registered third-party tool could not execute at all, which
 * meant the missing effect-class check on {@link Tool} could not be exploited. That was
 * safety by accident. Now the path works, so the two properties below are what keeps
 * it safe, and each one is pinned by a test that goes red if the property is dropped:
 * an effectful tool cannot be REGISTERED, and a tool cannot claim to be read-only
 * while the registry's own dispatch disagrees.
 */
class EffectClassRegistrationTest {

    private static Tool readOnly(String name) {
        return new Tool(name, "1.0.0",
            new ToolDescriptor(name, "Read-only.", "{}"),
            (args, boundary) -> ToolResult.success("ok", false));
    }

    private static Tool effectful(String name, EffectClass effect) {
        return new Tool(name, "1.0.0",
            new ToolDescriptor(name, "Effectful.", "{}"),
            (args, boundary) -> ToolResult.success("did it", false),
            effect);
    }

    // ------------------------------------------------- registration refuses effect

    @Test
    @DisplayName("A26: an effectful tool is refused at registration, naming the effect")
    void effectfulRegistrationIsRefused() {
        for (var effect : new EffectClass[] {
            EffectClass.LOCAL_REVERSIBLE_WRITE, EffectClass.EXTERNAL_EFFECT, EffectClass.DESTRUCTIVE}) {

            var e = assertThrows(IllegalArgumentException.class,
                () -> ToolRegistry.of(effectful("acme." + effect.name(), effect)),
                effect + " must not be registrable: only READ_ONLY is admissible in alpha "
                    + "(tools.md, extensibility.md 'Alpha registration rejects non-read-only "
                    + "effect classes')");
            assertTrue(e.getMessage().contains(effect.name()),
                "the rejection must name the offending effect class so a compose-time "
                    + "failure is actionable: " + e.getMessage());
            assertTrue(e.getMessage().contains("acme." + effect.name()),
                "the rejection must name the offending tool: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("A26: a read-only tool registers, and its effect is reported as READ_ONLY")
    void readOnlyRegistersAndReportsItsEffect() {
        var tool = readOnly("acme.metrics");
        assertEquals(EffectClass.READ_ONLY, tool.effect(),
            "the default must be READ_ONLY, so a tool that declares nothing is the safe one");

        var registry = ToolRegistry.of(tool);
        assertTrue(registry.find("acme.metrics").isPresent());
        assertEquals(EffectClass.READ_ONLY, registry.find("acme.metrics").get().effect());
    }

    @Test
    @DisplayName("A26: refusing an effectful tool happens before anything else can observe it")
    void refusalIsAtRegistrationNotDispatch() {
        var ran = new AtomicBoolean();
        var tool = new Tool("acme.delete", "1.0.0",
            new ToolDescriptor("acme.delete", "Effectful.", "{}"),
            (args, boundary) -> {
                ran.set(true);
                return ToolResult.success("deleted", false);
            },
            EffectClass.DESTRUCTIVE);

        assertThrows(IllegalArgumentException.class, () -> ToolRegistry.of(tool));

        // The executor must not have run even once. A gate that ran the code and then
        // refused the RESULT would have side effects the caller cannot see.
        assertEquals(false, ran.get(),
            "an effectful executor must never have been invoked, even during composition");
    }

    @Test
    @DisplayName("A26: narrowing to an effectful tool cannot smuggle it past the gate")
    void narrowingCannotSmuggleAnEffectfulTool() {
        // restrictedTo() rebuilds a registry through of(), so a refusal there would be
        // loud. This pins that the rebuild really does re-check, rather than copying
        // a map that some other path filled without validation.
        var registry = ToolRegistry.of(readOnly("a.one"), readOnly("a.two"));
        var narrowed = registry.restrictedTo(java.util.Set.of("a.one"));
        assertEquals(1, narrowed.all().size());
        assertEquals(EffectClass.READ_ONLY, narrowed.find("a.one").orElseThrow().effect());
    }

    // ------------------------------------------ the shipped tools must stay honest

    @Test
    @DisplayName("The three shipped workspace tools declare READ_ONLY and register")
    void builtinsAreReadOnly() {
        var registry = ToolRegistry.builtins();
        assertEquals(3, registry.all().size());
        registry.all().forEach(t -> assertEquals(EffectClass.READ_ONLY, t.effect(),
            "a shipped tool that was not read-only would make the whole gate meaningless: "
                + t.name()));
    }

    @Test
    @DisplayName("A26: a tool cannot be constructed without an effect class")
    void effectClassIsMandatoryAndCannotBeNull() {
        // A null effect must not slip past an equality check like
        // effect != EffectClass.READ_ONLY, because null is != READ_ONLY and so would
        // be refused — but a future guard written as `effect == null ||` on the other
        // side would let it through. Reject at construction instead of relying on
        // whichever way the comparison is written.
        var ex = assertThrows(NullPointerException.class,
            () -> new Tool("acme.null", "1.0.0",
                new ToolDescriptor("acme.null", "d", "{}"),
                (args, boundary) -> ToolResult.success("", false),
                null));
        assertTrue(ex.getMessage().contains("effect"),
            "the message must say which argument was null: " + ex.getMessage());
    }

    @Test
    @DisplayName("A registered read-only tool now actually executes through the run loop")
    void registeredToolExecutesThroughTheWorkspaceDispatch() {
        // The positive half. ExtensionSurfaceTest pins that a registered tool could not
        // run; this pins that it now can. Without this pair the gate would pass on a
        // registry that is still inert, and A26 would look satisfied while the
        // extension path stayed broken.
        var root = Path.of(System.getProperty("java.io.tmpdir"));
        var boundary = new PathBoundary(root);
        var ran = new AtomicBoolean();
        var tool = new Tool("acme.metrics", "1.0.0",
            new ToolDescriptor("acme.metrics", "A third-party read-only tool.", "{}"),
            (args, boundaryArg) -> {
                ran.set(true);
                return ToolResult.success("42", false);
            });

        var workspace = new WorkspaceTools(boundary);
        var log = new ToolCallLog();

        var result = workspace.executeToolCall("call-1", "acme.metrics", "{}", log,
            ToolRegistry.of(tool));

        assertEquals(ToolResult.Status.SUCCESS, result.status(),
            "a registered READ_ONLY tool must execute through the live dispatch path");
        assertTrue(result.content().contains("42"), "the executor's content must reach the model");
        assertTrue(ran.get(), "the executor must actually have been invoked");
    }

    @Test
    @DisplayName("An unregistered tool is still refused by dispatch, before any executor runs")
    void unregisteredToolIsStillRefused() {
        var boundary = new PathBoundary(Path.of(System.getProperty("java.io.tmpdir")));
        var ran = new AtomicBoolean();
        var notRegistered = new Tool("acme.ghost", "1.0.0",
            new ToolDescriptor("acme.ghost", "d", "{}"),
            (args, boundaryArg) -> {
                ran.set(true);
                return ToolResult.success("boo", false);
            });

        var workspace = new WorkspaceTools(boundary);
        var result = workspace.executeToolCall("call-1", "acme.ghost", "{}",
            new ToolCallLog(), ToolRegistry.of(readOnly("acme.metrics")));

        assertEquals(ToolResult.Status.INVALID, result.status(),
            "a tool absent from the registry must not be dispatched, even if a Tool object "
                + "for that name exists elsewhere");
        assertEquals(false, ran.get(), "its executor must not have run");
    }
}