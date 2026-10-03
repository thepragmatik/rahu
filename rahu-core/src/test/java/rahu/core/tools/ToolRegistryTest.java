package rahu.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S07 registry/descriptor contracts (extensibility.md, tools.md): frozen
 * registries, schema validation, duplicate/effectful registration rejected.
 */
class ToolRegistryTest {

    @Test
    @DisplayName("Built-in tools expose name, version, and a JSON schema")
    void builtinsExposeDescriptors() {
        var registry = ToolRegistry.builtins();
        assertEquals(3, registry.all().size());
        for (Tool t : registry.all()) {
            assertTrue(t.descriptor().jsonSchema().startsWith("{"),
                t.name() + " must carry a JSON schema");
            assertEquals("1.0.0", t.version());
        }
        assertTrue(registry.find("workspace.list").isPresent());
        assertTrue(registry.find("workspace.read").isPresent());
        assertTrue(registry.find("workspace.search").isPresent());
        assertTrue(registry.find("shell.run").isEmpty(), "no effectful tool exists");
    }

    @Test
    @DisplayName("Registration freezes at composition: no add/remove after freeze")
    void frozenAfterComposition() {
        ToolRegistry registry = ToolRegistry.builtins();
        assertThrows(UnsupportedOperationException.class, () -> registry.all().add(null));
    }

    @Test
    @DisplayName("Duplicate registration is rejected at construction; effect classes gate admission separately")
    void rejectsDuplicate() {
        var dup = new Tool("workspace.read", "1.0.0",
            new ToolDescriptor("workspace.read", "d", "{}"),
            (args, boundary) -> ToolResult.success("", false));
        assertThrows(IllegalArgumentException.class,
            () -> ToolRegistry.of(dup, dup));
        // Effect-class admission is enforced by ToolRegistry.of (step 3): a Tool
        // declares its EffectClass and the registry refuses anything but READ_ONLY,
        // so an effectful tool has no registration path at all. Covered by
        // EffectClassRegistrationTest and ExtensionSurfaceTest. The registry's only
        // remaining job here is that the names it ships are read-only ones.
    }

    @Test
    @DisplayName("A24: injected instruction text inside tool output stays data")
    void injectedInstructionStaysData() throws Exception {
        var boundary = new ToolRegistryTestFactory().boundaryWithInjectedFile();
        boundary.root().toFile().deleteOnExit();
        var tools = new WorkspaceTools(boundary);
        ToolResult result = tools.search("IGNORE PREVIOUS INSTRUCTIONS", null);
        // The tool returns the file content as DATA — the runtime's authority
        // layer (AdmissionPipeline) is what makes it inert; the tool result
        // carries no grant and cannot widen the pool.
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertTrue(result.content().contains("IGNORE PREVIOUS INSTRUCTIONS"));
        // and the registry offers no tool that could act on it:
        assertTrue(ToolRegistry.builtins().find("shell.run").isEmpty());
    }
}
