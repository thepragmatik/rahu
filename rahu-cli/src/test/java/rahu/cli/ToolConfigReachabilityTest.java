package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.cli.config.ConfigLoader;
import rahu.core.tools.PathBoundary;

/**
 * Are the tool configuration keys actually reachable?
 *
 * <p>Audit finding F-4. {@code tools.enabled} and {@code tools.exclusions} were both
 * parsed into the typed config and then never read by any production path. An operator
 * who disabled a tool, or excluded a path, got the default set anyway. Both keys are
 * documented in configuration.md line 24 and both appear in the committed schema, so
 * this is the "documented capability that is unreachable" shape, twice over.
 *
 * <p>Configs are built through the real {@link ConfigLoader} from JSON rather than
 * assembled by hand, so the test cannot pass against a shape the loader never produces.
 */
class ToolConfigReachabilityTest {

    @TempDir
    Path tmp;

    @TempDir
    Path root;

    private Path writeConfig(String toolsBlock) throws Exception {
        Path p = tmp.resolve("config.json");
        Files.writeString(p, """
            {
              "schemaVersion": 1,
              "mode": "live",
              "decision": {"adapter": "openrouter-decisions", "model": "m",
                           "baseUrl": "https://example.invalid/decisions"},
              "generation": {"adapter": "openrouter", "baseUrl": "https://example.invalid/v1",
                             "apiKeyEnv": "TEST_KEY"},
              "routing": {"mode": "shadow", "pool": "demo",
                          "baseline": "fast@low", "fallback": "fast@low",
                          "confidenceField": "chosen_probability", "confidenceFloor": 0.65},
              "pools": {"demo": {"models": [
                {"alias": "fast", "id": "demo-fast", "reasoning": ["low"]}]}},
              "context": {"instructionFiles": []},
              "session": {"mode": "in-process", "maxTurns": 20, "maxCostUsd": "3.00"},
              "orchestration": {"mode": "single"},
              "tools": %s,
              "trace": {"directory": ".rahu/runs", "capture": "metadata", "onFailure": "stop"},
              "privacy": {"mode": "strict", "onUnknown": "block",
                          "inputClassification": "unknown"}
            }
            """.formatted(toolsBlock));
        return p;
    }

    @Test
    @DisplayName("tools.enabled narrows what the model is offered")
    void enabledListNarrowsTheRegistry() throws Exception {
        var cfg = new ConfigLoader().load(writeConfig(
            "{\"root\": \".\", \"enabled\": [\"workspace.read\"]}"));
        var registry = ChatCommand.workspaceRegistry(cfg, new PathBoundary(root));

        assertEquals(List.of("workspace.read"),
            registry.all().stream().map(t -> t.name()).toList(),
            "an operator who enabled only workspace.read must not have the other two "
                + "advertised to the model");
    }

    @Test
    @DisplayName("an empty tools.enabled keeps all three, so a sparse config still loads")
    void emptyEnabledKeepsEverything() throws Exception {
        var cfg = new ConfigLoader().load(writeConfig("{\"root\": \".\", \"enabled\": []}"));
        var registry = ChatCommand.workspaceRegistry(cfg, new PathBoundary(root));

        assertEquals(3, registry.all().size(),
            "an empty list must not silently disable every tool");
    }

    @Test
    @DisplayName("tools.enabled naming an unknown tool fails loudly at wiring")
    void unknownEnabledToolIsRefused() throws Exception {
        var cfg = new ConfigLoader().load(writeConfig(
            "{\"root\": \".\", \"enabled\": [\"workspace.read\", \"shell.exec\"]}"));

        assertThrows(IllegalArgumentException.class,
            () -> ChatCommand.workspaceRegistry(cfg, new PathBoundary(root)),
            "a key naming a tool that does not exist must fail loudly, or the operator "
                + "believes they restricted something they did not");
    }

    @Test
    @DisplayName("tools.exclusions reaches the path boundary, case-insensitively")
    void exclusionsReachTheBoundary() throws Exception {
        var cfg = new ConfigLoader().load(writeConfig(
            "{\"root\": \".\", \"enabled\": [], \"exclusions\": [\"SecretNotes\"]}"));
        var boundary = new PathBoundary(root, cfg.tools().exclusions());

        assertThrows(PathBoundary.BoundaryViolation.class,
            () -> boundary.resolve("SecretNotes/plan.md"),
            "an operator-excluded name must actually be excluded");
        assertThrows(PathBoundary.BoundaryViolation.class,
            () -> boundary.resolve("secretnotes/plan.md"),
            "exclusion matching must be case-insensitive");
        assertTrue(boundary.resolve("src/Main.java").toString().endsWith("Main.java"),
            "an ordinary path must still resolve");
    }

    @Test
    @DisplayName("directory exclusions match case-insensitively")
    void directoryExclusionsAreCaseInsensitive() {
        var boundary = new PathBoundary(root);

        assertThrows(PathBoundary.BoundaryViolation.class,
            () -> boundary.resolve(".GIT/config"),
            ".GIT must be excluded as surely as .git; a case-sensitive exclusion list is "
                + "a bypass on a case-preserving filesystem");
    }

    @Test
    @DisplayName("credential-like defaults still apply when exclusions are configured")
    void defaultsSurviveOperatorExclusions() throws Exception {
        var cfg = new ConfigLoader().load(writeConfig(
            "{\"root\": \".\", \"enabled\": [], \"exclusions\": [\"SecretNotes\"]}"));
        var boundary = new PathBoundary(root, cfg.tools().exclusions());

        assertThrows(PathBoundary.BoundaryViolation.class,
            () -> boundary.resolve(".env"),
            "supplying operator exclusions must not displace the credential-name defaults");
    }
}