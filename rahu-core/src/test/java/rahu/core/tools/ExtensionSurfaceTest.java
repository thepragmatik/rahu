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
 * AUDIT-2026-10-03-w, A26. This class was originally a defect report: it asserted
 * that the extension surface was INERT and that {@link Tool} had no effect class, and
 * its two central tests went red the moment
 * {@link EffectClassRegistrationTest}'s production change landed.
 *
 * <p>They are kept, rewritten, because the properties they discovered are worth
 * re-checking in the other direction. Two tests that go red on your own fix are
 * evidence the fix changed real behaviour; deleting them leaves no trace that the
 * surface was ever broken, and nothing would stop it going inert again.
 *
 * <p>What changed is only which way each assertion points:
 *
 * <ul>
 *   <li>{@code executorSurfaceIsInert} required the hardcoded dispatch switch to remain
 *       untouched, so that a future change wiring the registry in would be forced to
 *       think about effect classes. It fired. It is now the guard on the OTHER half: the
 *       registry must stay consulted, so the surface cannot silently go inert again.
 *   <li>{@code toolHasNoEffectClassToCheck} asserted that no {@code effect} field
 *       existed. It fired. It now asserts the field exists AND that the registry refuses
 *       to admit a non-read-only tool — the property the field was added to serve.
 * </ul>
 */
class ExtensionSurfaceTest {

    private static Tool readOnly(String name) {
        return new Tool(name, "0.1.0",
            new ToolDescriptor(name, "A third-party read-only tool.", "{}"),
            (args, boundary) -> ToolResult.success("metrics", false));
    }

    @Test
    @DisplayName("A composed third-party tool is registered, advertised and findable")
    void thirdPartyToolComposesCleanly() {
        var ran = new AtomicBoolean();
        var custom = new Tool("acme.metrics", "0.1.0",
            new ToolDescriptor("acme.metrics", "A third-party tool.", "{}"),
            (args, boundary) -> {
                ran.set(true);
                return ToolResult.success("metrics", false);
            });

        var registry = ToolRegistry.of(custom);

        assertTrue(registry.find("acme.metrics").isPresent(),
            "a third-party tool must compose into the registry; A26 requires the "
                + "registration path to accept it");
        assertEquals("acme.metrics", registry.find("acme.metrics").get().name());
        assertEquals(1, registry.all().size(), "the composed tool must be advertised");
        assertEquals(EffectClass.READ_ONLY, registry.find("acme.metrics").get().effect(),
            "a tool that declares no effect is read-only, so silence cannot imply danger");

        // Executing it directly works: the executor and its descriptor are fine.
        var result = custom.execute("{}", new PathBoundary(Path.of("/tmp")));
        assertEquals(ToolResult.Status.SUCCESS, result.status(),
            "executing through Tool.execute must return the executor's own result type");
        assertTrue(ran.get(), "the executor must run when invoked through Tool.execute");
    }

    @Test
    @DisplayName("A registered tool is advertised to the provider, so dispatch must honour it")
    void registeredToolIsAdvertisedToTheProvider() {
        // This is what the provider is told exists. The defect was that descriptors came
        // from the registry while execution was dispatched elsewhere, so the model was
        // told a tool was available and then given "unknown tool" for it.
        var descriptor = ToolRegistry.of(readOnly("acme.metrics")).all().get(0).descriptor();
        assertEquals("acme.metrics", descriptor.name());
        assertTrue(descriptor.jsonSchema().contains("object") || descriptor.jsonSchema().equals("{}"),
            "sanity: the descriptor carries a schema");
    }

    @Test
    @DisplayName("Dispatch consults the registry, so the surface cannot go inert again")
    void dispatchConsultsTheRegistry() throws Exception {
        // Was: "the hardcoded switch is still the only dispatch" (F=1 once the registry was
        // wired in). Now the inverse: the fallback must remain, so an edit that collapses
        // dispatch back onto the switch alone is caught.
        //
        // Reading the source as well as exercising it is deliberate. A behaviour test
        // can pass because a tool happens to be registered; this fails if the FALLBACK
        // disappears, which is the specific regression that made A26 a lie.
        var source = readSource("WorkspaceTools.java");
        assertTrue(source.contains("registry.find(toolName)"),
            "WorkspaceTools.invoke is expected to fall back to the registry for any name "
                + "outside the built-in switch; if this is gone, a registered third-party "
                + "tool is advertised to the model and then refused at dispatch -- the "
                + "AUDIT-2026-10-03-w defect, restored");
        assertTrue(source.contains("registered.get().execute(canonicalArgs, boundary)"),
            "the registered tool's executor must be the thing that actually runs");

        // And it must stay BEHIND the built-ins, so the shipped tools are unaffected.
        int switchAt = source.indexOf("case \"workspace.list\"");
        int fallbackAt = source.indexOf("registry.find(toolName)");
        assertTrue(switchAt > 0 && fallbackAt > switchAt,
            "the registry fallback must come after the built-in switch so the three "
                + "workspace tools keep their exact behaviour");
        assertEquals(3, countSwitchCases(source),
            "the built-in switch must still cover exactly the three workspace tools");

        // Behavioural proof that both halves of the round trip now hold.
        var boundary = new PathBoundary(Path.of(System.getProperty("java.io.tmpdir")));
        var ran = new AtomicBoolean();
        var tool = new Tool("acme.metrics", "0.1.0",
            new ToolDescriptor("acme.metrics", "d", "{}"),
            (args, b) -> {
                ran.set(true);
                return ToolResult.success("42", false);
            });
        var result = new WorkspaceTools(boundary)
            .executeToolCall("c1", "acme.metrics", "{}", new ToolCallLog(),
                ToolRegistry.of(tool));
        assertEquals(ToolResult.Status.SUCCESS, result.status(),
            "a registered READ_ONLY tool must now actually execute through the loop");
        assertTrue(result.content().contains("42"), "its content must reach the model");
        assertTrue(ran.get(), "the executor must actually have been invoked");
    }

    @Test
    @DisplayName("Tool declares an effect class, and the registry refuses anything else")
    void toolHasAnEffectClassTheRegistryEnforces() {
        // Was: "Tool declares no effect field at all" (F=1 once the field was added).
        // Now the property that field exists to serve. EffectClassRegistrationTest owns
        // the exhaustive per-effect matrix; this pins the contract at the type boundary,
        // so dropping the field cannot leave the registry comparing against nothing.
        var hasEffect = java.util.Arrays.stream(Tool.class.getDeclaredFields())
            .anyMatch(f -> f.getName().toLowerCase(java.util.Locale.ROOT).contains("effect"));
        assertTrue(hasEffect,
            "Tool must declare its effect class; without it ToolRegistry.of has nothing to "
                + "refuse an effectful tool on, which is the AUDIT-2026-10-03-w defect");

        // The gate is real, not decorative: a DESTRUCTIVE tool is refused outright.
        assertThrows(IllegalArgumentException.class, () -> ToolRegistry.of(
            new Tool("acme.delete", "0.1.0",
                new ToolDescriptor("acme.delete", "d", "{}"),
                (args, b) -> ToolResult.success("deleted", false),
                EffectClass.DESTRUCTIVE)));

        // The one registration rule that already existed is still enforced.
        assertThrows(IllegalArgumentException.class, () -> new Tool("mismatch", "0.1.0",
                new ToolDescriptor("other.name", "d", "{}"),
                (args, boundary) -> ToolResult.success("", false)),
            "name/descriptor agreement must still be enforced");
    }

    /** Reads a main source file relative to this test's package root. */
    private static String readSource(String file) throws Exception {
        var path = Path.of("src/main/java/rahu/core/tools", file);
        return java.nio.file.Files.exists(path)
            ? java.nio.file.Files.readString(path)
            : java.nio.file.Files.readString(
                Path.of("..", "rahu-core", "src", "main", "java", "rahu", "core", "tools", file));
    }

    private static int countSwitchCases(String source) {
        int at = source.indexOf("private ToolResult invoke(");
        if (at < 0) {
            return -1;
        }
        String body = source.substring(at, Math.min(source.length(), at + 900));
        int n = 0;
        for (String line : body.split("\n")) {
            if (line.trim().startsWith("case \"")) {
                n++;
            }
        }
        return n;
    }
}