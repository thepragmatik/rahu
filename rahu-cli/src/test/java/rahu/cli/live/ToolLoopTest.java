package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.core.tools.PathBoundary;
import rahu.core.tools.ToolRegistry;

/** The live loop must expose exactly the shipped read-only workspace tools. */
class ToolLoopTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("Registry bound to a workspace exposes list/read/search only")
    void exposesThreeReadOnlyTools() {
        ToolRegistry registry = ToolRegistry.withWorkspace(new PathBoundary(root));
        var names = registry.all().stream().map(t -> t.name()).sorted().toList();
        assertEquals(java.util.List.of("workspace.list", "workspace.read", "workspace.search"),
            names);
    }

    @Test
    @DisplayName("Descriptors map onto the generation-facing tool schema")
    void descriptorsMap() {
        ToolRegistry registry = ToolRegistry.withWorkspace(new PathBoundary(root));
        var descriptors = ToolLoop.descriptors(registry);
        assertEquals(3, descriptors.size());
        assertTrue(descriptors.get(0).jsonSchema().contains("properties"));
    }
}
