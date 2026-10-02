package rahu.openrouter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.core.CurrencyUnit;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;

/**
 * Catalog evidence (openrouter.md): one /api/v1/models fetch writes a
 * versioned evidence file; the loader maps entries onto ModelProfile with a
 * bounded freshness rule. No prompt or key material is ever persisted.
 */
class ModelProfileCatalogTest {

    private static final String CATALOG_BODY = """
        {
          "data": [
            {
              "id": "mistralai/mistral-nemo",
              "context_length": 131072,
              "top_provider": {"max_completion_tokens": 4096},
              "pricing": {"prompt": "0.000003", "completion": "0.000006"},
              "supported_parameters": ["tools", "reasoning", "max_tokens"],
              "architecture": {"modality": "text"}
            }
          ]
        }
        """;

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> catalogBody = new AtomicReference<>(CATALOG_BODY);
    private final AtomicReference<Integer> hits = new AtomicReference<>(0);
    private final AtomicReference<String> seenPath = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/models", exchange -> {
            hits.set(hits.get() + 1);
            seenPath.set(exchange.getRequestURI().getPath());
            byte[] out = catalogBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    @Test
    @DisplayName("One fetch writes a versioned evidence file with bounded fields")
    void fetchWritesVersionedFile(@TempDir Path dir) throws Exception {
        var catalog = new ModelProfileCatalog(baseUrl(), dir.resolve("model-profiles.json"));

        catalog.refreshIfNeeded(java.time.Duration.ofHours(24), Instant.now());
        assertEquals(1, hits.get());

        Path file = dir.resolve("model-profiles.json");
        assertTrue(Files.exists(file));
        String text = Files.readString(file);
        assertTrue(text.contains("\"schemaVersion\""), "file is versioned");
        assertTrue(text.contains("mistralai/mistral-nemo"));
        assertFalse(text.toUpperCase().contains("AUTHORIZATION"),
            "no request headers are persisted");
    }

    @Test
    @DisplayName("A fresh file is reused; the TTL drives exactly when a refetch happens")
    void ttlGatesRefetch(@TempDir Path dir) throws Exception {
        var catalog = new ModelProfileCatalog(baseUrl(), dir.resolve("model-profiles.json"));
        var ttl = java.time.Duration.ofHours(24);
        Instant now = Instant.now();

        catalog.refreshIfNeeded(ttl, now);
        catalog.refreshIfNeeded(ttl, now.plusSeconds(60));
        assertEquals(1, hits.get(), "fresh evidence is not refetched");

        catalog.refreshIfNeeded(ttl, now.plus(ttl).plusSeconds(1));
        assertEquals(2, hits.get(), "expired evidence is refetched");
    }

    @Test
    @DisplayName("The loader maps catalog entries onto ModelProfile with prices and freshness")
    void loadsProfiles(@TempDir Path dir) throws Exception {
        var catalog = new ModelProfileCatalog(baseUrl(), dir.resolve("model-profiles.json"));
        catalog.refreshIfNeeded(java.time.Duration.ofHours(24), Instant.now());

        ModelProfile profile = catalog.profile(new ModelRef("mistralai/mistral-nemo"));
        org.junit.jupiter.api.Assertions.assertNotNull(profile);
        assertEquals(131072, profile.contextTokens());
        assertEquals(0.000003,
            profile.inputPrice().get().amount().doubleValue(), 1e-12);
        assertEquals(CurrencyUnit.USD, profile.inputPrice().get().currency());
        assertTrue(profile.toolSupport(), "tools in supported_parameters");
        assertTrue(profile.evidenceFresh(), "fresh within the TTL");
        assertTrue(profile.supportedEfforts().length > 0,
            "reasoning parameters map to supported efforts");
    }

    @Test
    @DisplayName("Age is taken from the recorded fetch time; stale rules bound its use")
    void staleBeyondMaximumRefused(@TempDir Path dir) throws Exception {
        var catalog = new ModelProfileCatalog(baseUrl(), dir.resolve("model-profiles.json"));
        Instant fetched = Instant.parse("2026-10-01T09:00:00Z");
        // Write the file, then re-stamp its recorded fetch time via structured
        // JSON (never regex surgery) to simulate age.
        catalog.refreshIfNeeded(java.time.Duration.ofHours(24), Instant.now());
        Path file = dir.resolve("model-profiles.json");
        var root = MAPPER.readTree(Files.readString(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root)
            .put("fetchedAt", fetched.toString());
        Files.writeString(file, MAPPER.writeValueAsString(root));

        var ttl = java.time.Duration.ofHours(24);
        var maxStale = java.time.Duration.ofHours(48);
        assertTrue(catalog.profileFresh(new ModelRef("mistralai/mistral-nemo"),
            maxStale, fetched.plus(java.time.Duration.ofHours(47))),
            "inside maximum stale age (operator permits stale)");
        assertFalse(catalog.profileFresh(new ModelRef("mistralai/mistral-nemo"),
            maxStale, fetched.plus(java.time.Duration.ofHours(49))),
            "beyond maximum stale age refuses");
    }

    @Test
    @DisplayName("Unknown model has no profile; nothing is invented")
    void unknownModelAbsent(@TempDir Path dir) throws Exception {
        var catalog = new ModelProfileCatalog(baseUrl(), dir.resolve("model-profiles.json"));
        catalog.refreshIfNeeded(java.time.Duration.ofHours(24), Instant.now());

        org.junit.jupiter.api.Assertions.assertNull(
            catalog.profile(new ModelRef("nope/missing")));
    }
}
