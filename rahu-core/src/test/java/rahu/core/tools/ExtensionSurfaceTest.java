package rahu.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AUDIT-2026-10-03-w, A26. These tests do not assert that the extension surface
 * WORKS. They assert what it currently DOES, because the difference is the finding:
 * a third-party tool can be composed into the registry, be advertised to the model,
 * pass every gate, and then never run.
 */
class ExtensionSurfaceTest {

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

        // Executing it directly works: the executor and its descriptor are fine.
        var result = custom.execute("{}", new PathBoundary(java.nio.file.Path.of("/tmp")));
        assertEquals(ToolResult.Status.SUCCESS, result.status(),
            "executing through Tool.execute must return the executor's own result type");
        assertTrue(ran.get(), "the executor must run when invoked through Tool.execute");
    }

    @Test
    @DisplayName("A registered tool is advertised to the provider, so an inert tool looks live")
    void registeredToolIsAdvertisedToTheProvider() {
        var custom = new Tool("acme.metrics", "0.1.0",
            new ToolDescriptor("acme.metrics", "A third-party tool.", "{}"),
            (args, boundary) -> ToolResult.success("metrics", false));

        // This is what the provider is told exists. If this is populated from the
        // registry but execution is dispatched elsewhere, the model is told a tool
        // is available and then gets "unknown tool" for it.
        var descriptor = ToolRegistry.of(custom).all().get(0).descriptor();
        assertEquals("acme.metrics", descriptor.name());
        assertTrue(descriptor.jsonSchema().contains("object") || descriptor.jsonSchema().equals("{}"),
            "sanity: the descriptor carries a schema");
    }

    @Test
    @DisplayName("The tool executor type is unreachable from production, so effect class cannot gate it")
    void executorSurfaceIsInert() throws Exception {
        // The finding, stated as an executable assertion so it cannot quietly become
        // false without a test going red.
        //
        // WorkspaceTools.invoke (the ONLY tool dispatch in production, reached from
        // ToolLoop.observationOf) resolves names with a hardcoded switch over three
        // literals and returns invalid("unknown tool: …") for anything else. It never
        // consults the registry, so a Tool's Executor is never invoked by the run
        // loop -- not by policy, but because no production code path calls
        // Tool.execute at all.
        //
        // Two consequences follow, and they point in opposite directions:
        //   1. SAFE: no third-party Tool can be executed, so the missing effect-class
        //      check on Tool cannot be exploited yet.
        //   2. BROKEN: A26 and extensibility.md promise a working extension path.
        //      A registered third-party tool is advertised to the provider and then
        //      fails at dispatch, so the extension surface does not work.
        //
        // This test pins (1) so that whoever wires Tool.execute into the loop also
        // adds the effect-class rejection, which is currently absent entirely: Tool
        // carries no EffectClass field, and EffectClass enforcement lives only on
        // ProposedOperation, which no production code constructs.
        var invoke = WorkspaceTools.class.getDeclaredMethod("invoke", String.class, String.class);
        var source = readSource("WorkspaceTools.java");
        assertTrue(source.contains("default -> ToolResult.invalid(\"unknown tool: \""),
            "WorkspaceTools.invoke is expected to reject any name outside its hardcoded "
                + "switch; if this now consults the registry, the extension path has been "
                + "wired and EffectClass rejection on Tool MUST be added in the same change "
                + "(A26 / extensibility.md 'Alpha registration rejects non-read-only effect "
                + "classes'), or this test and this comment must be replaced");
        assertEquals(3, countSwitchCases(source),
            "the dispatch switch is expected to cover exactly the three workspace tools");
        assertTrue(invoke.getReturnType() == ToolResult.class);
    }

    @Test
    @DisplayName("Tool carries no effect class, so registration cannot reject an effectful tool")
    void toolHasNoEffectClassToCheck() throws Exception {
        // A26 requires "effectful registrations" to be DENIED. There is nothing on Tool
        // to deny them by: no EffectClass field, no effect setter, no constructor
        // argument. AdmissionPipeline does enforce EffectClass, but only for a
        // ProposedOperation, and no production code constructs one -- the entire
        // pipeline is exercised only by tests.
        for (var field : Tool.class.getDeclaredFields()) {
            assertTrue(!field.getName().toLowerCase(java.util.Locale.ROOT).contains("effect"),
                "Tool unexpectedly declares an '" + field.getName() + "' field; if an effect "
                    + "class is now part of the registration contract, ToolRegistry.of must "
                    + "reject non-READ_ONLY and ToolRegistryTest must be extended");
        }
        assertThrows(IllegalArgumentException.class, () -> new Tool("mismatch", "1.0.0",
                new ToolDescriptor("other.name", "d", "{}"),
                (args, boundary) -> ToolResult.success("", false)),
            "the one registration rule that IS enforced is name/descriptor agreement");
    }

    /** Reads a main source file relative to this test's package root. */
    private static String readSource(String file) throws Exception {
        var path = java.nio.file.Path.of("src/main/java/rahu/core/tools", file);
        return java.nio.file.Files.exists(path)
            ? java.nio.file.Files.readString(path)
            : java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "rahu-core", "src", "main", "java", "rahu", "core",
                    "tools", file));
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