package rahu.cli.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Evaluation suite v1 (artifacts.md): strict shape, unique ids, prompt xor
 * nonempty turns, nonempty rubric or exact expectedLabel. Tasks are ground
 * truth metadata, never permission grants.
 */
public final class SuiteV1 {

    public record Task(String id, String kind, List<String> turns, String prompt,
        List<String> rubric, String expectedLabel, List<String> tools) {

        public boolean isMultiTurn() {
            return turns != null && !turns.isEmpty();
        }
    }

    private final String id;
    private final String purpose;
    private final List<Task> tasks;

    private SuiteV1(String id, String purpose, List<Task> tasks) {
        this.id = id;
        this.purpose = purpose;
        this.tasks = List.copyOf(tasks);
    }

    public static SuiteV1 load(Path file) {
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EvalError("suite unreadable: " + file);
        }
        var mapper = new ObjectMapper();
        JsonNode root;
        try {
            var parser = mapper.getFactory().createParser(raw);
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature
                .STRICT_DUPLICATE_DETECTION);
            root = mapper.readTree(parser);
            parser.close();
        } catch (IOException e) {
            throw new EvalError("suite is not valid JSON: " + e.getMessage());
        }
        if (root.path("schemaVersion").asInt(0) != 1) {
            throw new EvalError("suite schemaVersion must be 1");
        }
        var allowed = java.util.Set.of("schemaVersion", "id", "purpose", "tasks",
            "fixture", "contextMaxPromptTokens", "requiredFacts");
        var fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String name = fieldNames.next();
            if (!allowed.contains(name)) {
                throw new EvalError("unknown suite key \"" + name
                    + "\"; remove it or fix the spelling (artifacts.md suite v1)");
            }
        }
        requireText(root, "id", "$");
        requireText(root, "purpose", "$");
        JsonNode tasks = root.get("tasks");
        if (tasks == null || !tasks.isArray() || tasks.isEmpty()) {
            throw new EvalError("$.tasks must be a nonempty array");
        }
        List<Task> parsed = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < tasks.size(); i++) {
            parsed.add(parseTask(tasks.get(i), i, ids));
        }
        return new SuiteV1(root.get("id").asText(), root.get("purpose").asText(), parsed);
    }

    private static Task parseTask(JsonNode node, int index, Set<String> ids) {
        String path = "tasks[" + index + "]";
        String id = requireText(node, "id", path);
        if (!ids.add(id)) {
            throw new EvalError(path + ".id duplicate \"" + id + "\"; ids must be unique");
        }
        String kind = requireText(node, "kind", path);
        boolean hasPrompt = node.hasNonNull("prompt");
        JsonNode turns = node.get("turns");
        boolean hasTurns = turns != null && turns.isArray() && !turns.isEmpty();
        if (hasPrompt == hasTurns) {
            throw new EvalError(path + " must have exactly one of prompt or nonempty turns");
        }
        JsonNode rubric = node.get("rubric");
        JsonNode expected = node.get("expectedLabel");
        boolean hasRubric = rubric != null && rubric.isArray() && !rubric.isEmpty();
        boolean hasExpected = expected != null && !expected.asText().isBlank();
        if (hasRubric == hasExpected) {
            throw new EvalError(path + ".rubric: need a nonempty rubric array or expectedLabel");
        }
        List<String> turnList = new ArrayList<>();
        if (hasTurns) {
            turns.forEach(t -> turnList.add(t.asText()));
        }
        List<String> rubricList = new ArrayList<>();
        if (hasRubric) {
            rubric.forEach(r -> rubricList.add(r.asText()));
        }
        List<String> toolList = new ArrayList<>();
        if (node.hasNonNull("tools")) {
            node.get("tools").forEach(t -> toolList.add(t.asText()));
        }
        return new Task(id, kind, turnList,
            hasPrompt ? node.get("prompt").asText() : null,
            rubricList, hasExpected ? expected.asText() : null, toolList);
    }

    private static String requireText(JsonNode node, String field, String path) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new EvalError(path + "." + field + " is required");
        }
        return v.asText();
    }

    public String id() {
        return id;
    }

    public String purpose() {
        return purpose;
    }

    public List<Task> tasks() {
        return tasks;
    }
}
