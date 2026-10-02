package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.cli.config.ConfigLoader;
import rahu.cli.config.RahuConfig;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;

/**
 * F3: the evidence a live run is allowed to route on.
 *
 * <p>Every case writes its own evidence file, so no test reaches the network: the
 * stale case pins {@code cacheTtlSeconds} far above the evidence age, because the TTL
 * governs REFETCH while {@code maximumStaleSeconds} governs ADMISSION.
 */
class ProfileEvidenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private static Path repoFile(String relative) {
        Path moduleDir = Path.of(System.getProperty("user.dir"));
        Path root = moduleDir.resolve("docs").toFile().exists()
            ? moduleDir : moduleDir.getParent();
        return root.resolve(relative);
    }

    private RahuConfig config(Long ttlSeconds, Boolean allowStale, Long staleSeconds)
        throws Exception {

        ObjectNode root = (ObjectNode) MAPPER.readTree(
            repoFile("examples/offline.json").toFile());
        ObjectNode catalog = MAPPER.createObjectNode();
        catalog.put("cacheTtlSeconds", ttlSeconds == null ? 86400 : ttlSeconds);
        if (allowStale != null) {
            catalog.put("allowStale", allowStale);
        }
        catalog.put("maximumStaleSeconds", staleSeconds == null ? 172800 : staleSeconds);
        root.set("catalog", catalog);
        Path path = tmp.resolve("cfg-" + ttlSeconds + "-" + allowStale + "-"
            + staleSeconds + ".json");
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        return new ConfigLoader().load(path);
    }

    private Path evidence(Instant fetchedAt, String... ids) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("fetchedAt", fetchedAt.toString());
        ArrayNode models = root.putArray("models");
        for (String id : ids) {
            ObjectNode model = models.addObject();
            model.put("id", id);
            model.put("context_length", 131072);
            model.putObject("top_provider").put("max_completion_tokens", 4096);
            ObjectNode pricing = model.putObject("pricing");
            pricing.put("prompt", "0.000000019");
            pricing.put("completion", "0.00000003");
            ArrayNode params = model.putArray("supported_parameters");
            params.add("tools");
            if (!"demo-quality".equals(id)) {
                params.add("reasoning");
            }
        }
        Path path = tmp.resolve("ev-" + ids.length + "-" + fetchedAt.toEpochMilli() + ".json");
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        return path;
    }

    @Test
    void freshEvidenceYieldsAProfilePerPoolModel() throws Exception {
        RahuConfig cfg = config(null, null, null);
        Path evidence = evidence(Instant.now(), "demo-fast", "demo-quality");

        var profiles = ProfileEvidence.load(cfg, evidence);

        assertEquals(2, profiles.size());
        ModelProfile fast = profiles.get(new ModelRef("demo-fast"));
        assertNotNull(fast);
        assertTrue(fast.toolSupport());
        assertEquals(131072, fast.contextTokens());
        assertTrue(profiles.get(new ModelRef("demo-quality")).toolSupport());
    }

    @Test
    void aModelAbsentFromTheCatalogHasNoProfile() throws Exception {
        RahuConfig cfg = config(null, null, null);
        Path evidence = evidence(Instant.now(), "demo-fast");

        var profiles = ProfileEvidence.load(cfg, evidence);

        assertNull(profiles.get(new ModelRef("demo-quality")),
            "absent evidence must read as absent, never as a permissive default");
    }

    @Test
    void staleEvidenceIsAdmittedOnlyWhenTheOperatorAllowsIt() throws Exception {
        Instant old = Instant.now().minus(Duration.ofDays(10));
        Path evidence = evidence(old, "demo-fast", "demo-quality");

        var refused = ProfileEvidence.load(config(8640000L, false, 60L), evidence);
        assertNull(refused.get(new ModelRef("demo-fast")),
            "stale evidence must not buy paid admission");

        var allowed = ProfileEvidence.load(config(8640000L, true, 60L), evidence);
        assertNotNull(allowed.get(new ModelRef("demo-fast")),
            "allowStale is the operator's documented permission");
    }
}