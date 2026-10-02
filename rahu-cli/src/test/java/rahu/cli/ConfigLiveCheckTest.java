package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * F2: {@code rahu config validate --live-check} resolves catalog profile evidence
 * for every configured pool model.
 *
 * <p>Every case supplies an evidence file, so no test may reach the network: the
 * stale case additionally pins {@code cacheTtlSeconds} far above the evidence age,
 * because the TTL gates REFETCH while {@code maximumStaleSeconds} gates ADMISSION.
 */
class ConfigLiveCheckTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    /** Surefire runs from the module dir; the repo root is its parent. */
    private static Path repoFile(String relative) {
        Path moduleDir = Path.of(System.getProperty("user.dir"));
        Path root = moduleDir.resolve("docs").toFile().exists()
            ? moduleDir : moduleDir.getParent();
        return root.resolve(relative);
    }

    private Path config(Long ttlSeconds, Boolean allowStale, Long maxStaleSeconds)
        throws Exception {

        ObjectNode config = (ObjectNode) MAPPER.readTree(
            repoFile("examples/offline.json").toFile());
        ObjectNode catalog = MAPPER.createObjectNode();
        catalog.put("cacheTtlSeconds", ttlSeconds == null ? 86400 : ttlSeconds);
        if (allowStale != null) {
            catalog.put("allowStale", allowStale);
        }
        catalog.put("maximumStaleSeconds", maxStaleSeconds == null ? 172800 : maxStaleSeconds);
        config.set("catalog", catalog);
        Path path = tmp.resolve("config-" + ttlSeconds + "-" + allowStale + "-"
            + maxStaleSeconds + ".json");
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config));
        return path;
    }

    /** Writes an evidence document in the catalog's on-disk format. */
    private Path evidence(Instant fetchedAt, String... modelIds) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("fetchedAt", fetchedAt.toString());
        ArrayNode models = root.putArray("models");
        for (String id : modelIds) {
            ObjectNode model = models.addObject();
            model.put("id", id);
            model.put("context_length", 131072);
            model.putObject("top_provider").put("max_completion_tokens", 4096);
            ObjectNode pricing = model.putObject("pricing");
            pricing.put("prompt", "0.000000019");
            pricing.put("completion", "0.00000003");
            model.putArray("supported_parameters").add("tools");
        }
        Path path = tmp.resolve("evidence-" + modelIds.length + "-"
            + fetchedAt.toEpochMilli() + ".json");
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        return path;
    }

    private String[] run(Path config, Path evidence) {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(new StringWriter(), true));
        int code = cmd.execute("config", "validate",
            "--config", config.toString(),
            "--live-check",
            "--evidence", evidence.toString());
        return new String[] {String.valueOf(code), out.toString()};
    }

    @Test
    void liveCheckResolvesEveryConfiguredPoolModel() throws Exception {
        Path config = config(null, null, null);
        Path evidence = evidence(Instant.now(), "demo-fast", "demo-quality");

        String[] result = run(config, evidence);

        assertEquals("0", result[0], () -> "expected admission, got: " + result[1]);
        assertTrue(result[1].contains(
            "fast: ctx=131072 in=$0.019/M out=$0.030/M tools=true fresh=true"), result[1]);
        assertTrue(result[1].contains(
            "quality: ctx=131072 in=$0.019/M out=$0.030/M tools=true fresh=true"), result[1]);
    }

    @Test
    void poolModelWithoutEvidenceRefusesWithTheInvalidConfigCode() throws Exception {
        Path config = config(null, null, null);
        Path evidence = evidence(Instant.now(), "demo-fast");

        String[] result = run(config, evidence);

        assertEquals("2", result[0],
            () -> "an unresolvable pool model is not an admissible config: " + result[1]);
        assertTrue(result[1].contains("quality: profile=missing"), result[1]);
    }

    @Test
    void staleEvidenceRefusesButTheOperatorPermissionAdmits() throws Exception {
        Instant old = Instant.now().minus(Duration.ofDays(10));
        Path evidence = evidence(old, "demo-fast", "demo-quality");
        // TTL far above the evidence age: this is an admission question, not a refetch.
        Path refused = config(8640000L, false, 60L);

        String[] strict = run(refused, evidence);
        assertEquals("2", strict[0], () -> "stale evidence must refuse: " + strict[1]);
        assertTrue(strict[1].contains("tools=true fresh=false"), strict[1]);

        Path allowed = config(8640000L, true, 60L);
        String[] permissive = run(allowed, evidence);
        assertEquals("0", permissive[0],
            () -> "allowStale is the operator's documented permission: " + permissive[1]);
    }

    @Test
    void withoutTheFlagValidationStaysOfflineAndUnchanged() throws Exception {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(new StringWriter(), true));

        int code = cmd.execute("config", "validate",
            "--config", repoFile("examples/offline.json").toString());

        assertEquals(0, code);
        assertTrue(out.toString().contains("config valid:"), out.toString());
        assertTrue(!out.toString().contains("fresh="),
            "no profile line may appear without --live-check: " + out);
    }
}