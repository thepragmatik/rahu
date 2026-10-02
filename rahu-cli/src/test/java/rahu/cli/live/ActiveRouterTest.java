package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.cli.config.ConfigError;
import rahu.cli.config.ConfigLoader;
import rahu.cli.config.RahuConfig;
import rahu.core.CurrencyUnit;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;
import rahu.core.MoneyAmount;
import rahu.core.ReasoningPolicy;
import rahu.core.routing.RouteResolution;
import rahu.core.routing.RoutingMode;

/**
 * F3: the router that turns the configured pool into executable candidates and
 * hands the decision plane a label set it can actually cover.
 *
 * <p>RouteResolver's own semantics are covered in rahu-core. What only this class
 * can break is the JOIN: if the labels offered to the decision differ from the
 * candidate ids the resolver checks, every live decision silently degrades to the
 * baseline and routing looks healthy while doing nothing. That is asserted here.
 */
class ActiveRouterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private static Path repoFile(String relative) {
        Path moduleDir = Path.of(System.getProperty("user.dir"));
        Path root = moduleDir.resolve("docs").toFile().exists()
            ? moduleDir : moduleDir.getParent();
        return root.resolve(relative);
    }

    /**
     * The demo pool, optionally narrowed by the candidate cap.
     *
     * <p>The fallback defaults to the baseline so a test may inspect EXCLUSIONS
     * (a tool-less model) without the startup guard refusing the route first;
     * {@link #configWithDistinctFallback()} keeps the real quality fallback for the
     * guard's own test.
     */
    private RahuConfig config(int maximumCandidates) throws Exception {
        return config(maximumCandidates, "fast@low");
    }

    private RahuConfig configWithDistinctFallback() throws Exception {
        return config(32, "quality@medium");
    }

    private RahuConfig config(int maximumCandidates, String fallback) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(
            repoFile("examples/offline.json").toFile());
        ObjectNode routing = (ObjectNode) root.get("routing");
        routing.put("mode", "active");
        routing.put("fallback", fallback);
        routing.put("maximumCandidates", maximumCandidates);
        Path path = tmp.resolve("config-" + maximumCandidates + "-" + fallback + ".json");
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(root));
        return new ConfigLoader().load(path);
    }

    private static ModelProfile profile(String id, boolean tools, Integer context) {
        return new ModelProfile(new ModelRef(id), context, 4096,
            new MoneyAmount(new BigDecimal("0.000000019"), CurrencyUnit.USD),
            new MoneyAmount(new BigDecimal("0.00000003"), CurrencyUnit.USD),
            ReasoningPolicy.Effort.values(), false,
            Instant.parse("2026-10-02T00:00:00Z"), true, tools);
    }

    private static Map<ModelRef, ModelProfile> profiles(boolean fastTools,
        boolean qualityTools) {

        return Map.of(
            new ModelRef("demo-fast"), profile("demo-fast", fastTools, 8192),
            new ModelRef("demo-quality"), profile("demo-quality", qualityTools, 32768));
    }

    @Test
    void criteriaLabelsAreExactlyTheExecutableCandidateIds() throws Exception {
        ActiveRouter router = new ActiveRouter(config(32), profiles(true, false));

        assertEquals(
            router.candidates().candidates().stream().map(c -> c.id()).toList(),
            List.copyOf(router.criteria().keySet()),
            "a label the resolver does not know degrades every decision to the baseline");
    }

    @Test
    void toolLessModelsAreExcludedWhenToolsAreExposed() throws Exception {
        ActiveRouter router = new ActiveRouter(config(32), profiles(true, false));

        List<String> ids = router.candidates().candidates().stream()
            .map(c -> c.id()).toList();
        assertTrue(ids.contains("fast@low"), () -> "expected fast candidates, got " + ids);
        assertFalse(ids.contains("quality@default"),
            () -> "a model without tool support must not be offered for a tool turn: " + ids);
        assertFalse(router.candidates().exclusions().isEmpty(),
            "an exclusion must be recorded, not silently dropped");
    }

    @Test
    void aliasOfACandidateIdIsThePoolAlias() throws Exception {
        ActiveRouter router = new ActiveRouter(config(32), profiles(true, false));

        assertEquals("fast", router.aliasOf("fast@medium"));
        assertThrows(IllegalArgumentException.class, () -> router.aliasOf("no-policy"));
    }

    @Test
    void theCandidateCapKeepsTheBaselineExecutable() throws Exception {
        // fast@low, fast@medium are both admissible; the cap of one must not
        // truncate the configured baseline out of the executable set.
        RahuConfig cfg = config(1);
        ActiveRouter router = new ActiveRouter(cfg, profiles(true, false));

        List<String> ids = router.candidates().candidates().stream()
            .map(c -> c.id()).toList();
        assertEquals(List.of(cfg.routing().baseline()), ids);
        assertTrue(router.criteria().containsKey(cfg.routing().baseline()));
    }

    @Test
    void theLoaderRefusesAPoolThatDoesNotExist() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(
            repoFile("examples/offline.json").toFile());
        ((ObjectNode) root.get("routing")).put("pool", "absent");
        Path path = tmp.resolve("absent-pool.json");
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(root));

        ConfigError failure = assertThrows(ConfigError.class,
            () -> new ConfigLoader().load(path));
        assertTrue(failure.getMessage().contains("absent"), failure.getMessage());
    }

    @Test
    void anEvidenceExcludedRouteEndpointRefusesAtStartup() throws Exception {
        // quality is configured as the fallback but has no tool support, so no
        // degraded decision could ever reach it. That must be named at startup,
        // not surface later as a per-turn NO_FEASIBLE_ROUTE terminal.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new ActiveRouter(configWithDistinctFallback(), profiles(true, false)));

        assertTrue(failure.getMessage().contains("routing.fallback"), failure.getMessage());
        assertTrue(failure.getMessage().contains("quality@medium"), failure.getMessage());
        assertTrue(failure.getMessage().contains("no-tool-support"), failure.getMessage());
    }

    @Test
    void describeNamesTheExecutedCandidateAndTheMode() throws Exception {
        ActiveRouter router = new ActiveRouter(config(32), profiles(true, true));
        RouteResolution resolution = router.resolve(Optional.empty());

        String line = ActiveRouter.describe(resolution);
        assertTrue(line.startsWith("route: "), line);
        assertTrue(line.contains("executed=fast@low"),
            () -> "with no decision the baseline executes: " + line);
        assertTrue(line.contains("mode=" + RoutingMode.ACTIVE), line);
    }
}