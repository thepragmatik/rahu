package rahu.cli.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The injection shadow corpus is a CALIBRATION artifact, so its shape is load-bearing:
 * a corpus that lost its benign majority would make a threshold look far better than it
 * is, which is the exact error G1c already made once with scratch data. These tests
 * hold the properties the recorded result in docs/research/injection-corpus-v1.md
 * depends on.
 */
class InjectionShadowCorpusTest {

    private static JsonNode corpus() throws Exception {
        Path file = Path.of("..", "docs", "evals", "corpus", "injection-shadow-v1.json");
        return new ObjectMapper().readTree(Files.readString(file));
    }

    @Test
    @DisplayName("The corpus keeps the benign majority that makes a false positive visible")
    void benignMajorityDominates() throws Exception {
        int benign = 0;
        int hostile = 0;
        for (JsonNode o : corpus().get("observations")) {
            if ("benign".equals(o.get("expectedLabel").asText())) {
                benign++;
            } else {
                hostile++;
            }
        }
        assertTrue(hostile > 0, "a corpus with no hostile samples cannot calibrate anything");
        assertTrue(benign > hostile,
            "the failure mode being calibrated is a false positive on ordinary text, so "
                + "ordinary text must dominate: benign=" + benign + " hostile=" + hostile);
    }

    @Test
    @DisplayName("Every observation is uniquely identified and non-empty")
    void observationsAreWellFormed() throws Exception {
        Set<String> ids = new HashSet<>();
        for (JsonNode o : corpus().get("observations")) {
            String id = o.get("id").asText();
            assertTrue(ids.add(id), "duplicate observation id: " + id);
            assertTrue(o.get("text").asText().length() > 20,
                "an observation too short to be realistic: " + id);
            assertTrue(o.hasNonNull("style"), "style required so scores can be grouped: " + id);
            String label = o.get("expectedLabel").asText();
            assertTrue(label.equals("benign") || label.equals("hostile"),
                "expectedLabel must be benign or hostile: " + id);
        }
    }

    @Test
    @DisplayName("Hostile samples are harmless self-labelled markers, never live payloads")
    void hostileSamplesCarryNoRealPayload() throws Exception {
        for (JsonNode o : corpus().get("observations")) {
            if (!"hostile".equals(o.get("expectedLabel").asText())) {
                continue;
            }
            String text = o.get("text").asText();
            assertTrue(text.contains("TEST MARKER - HARMLESS"),
                "hostile sample must announce itself as a test marker: " + o.get("id"));
            // The operator constraint forbids anything resembling a working payload or a
            // real exfiltration target; example.invalid is reserved and unroutable.
            for (String forbidden : new String[] {"sk-", "Bearer ", "@gmail.com",
                "api_key=", "curl http", "rm -rf /"}) {
                assertTrue(!text.contains(forbidden),
                    "hostile sample must not contain a real technique: " + forbidden
                        + " in " + o.get("id"));
            }
        }
    }

    @Test
    @DisplayName("The corpus spans enough distinct styles to be worth calibrating on")
    void stylesAreVaried() throws Exception {
        Map<String, Integer> byStyle = new LinkedHashMap<>();
        int total = 0;
        for (JsonNode o : corpus().get("observations")) {
            byStyle.merge(o.get("style").asText(), 1, Integer::sum);
            total++;
        }
        assertTrue(byStyle.size() >= 20,
            "a corpus dominated by one or two styles cannot generalise: " + byStyle.size());
        // No single style may dominate the way a copy-pasted fixture would.
        for (Map.Entry<String, Integer> e : byStyle.entrySet()) {
            assertTrue(e.getValue() <= total / 4,
                "style " + e.getKey() + " has " + e.getValue() + " of " + total
                    + " samples - too concentrated");
        }
    }
}