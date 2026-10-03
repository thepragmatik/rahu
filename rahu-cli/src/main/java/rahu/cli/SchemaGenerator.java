package rahu.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates docs/generated/config.schema.json (artifacts.md): the v1 field
 * definitions machine-read from the same table the loader validates against.
 * Kept in sync by a unit test that round-trips a valid config through both.
 */
public final class SchemaGenerator {

    private SchemaGenerator() {
    }

    public static String configSchemaJson() {
        return closedToTheLoader(TOP_LEVEL_SCHEMA);
    }

    /**
     * Refuses any key the loader does not honour, at the root and in every section.
     *
     * <p>The literal above sets {@code "additionalProperties": true} at the root and
     * omits it in the sections, while {@link rahu.cli.config.ConfigLoader} rejects every
     * key it does not know. I confirmed the consequence before changing it: with an
     * otherwise-valid config, {@code decision.timeoutMilis},
     * {@code routing.confidencFloor}, {@code tools.resultByte},
     * {@code trace.captureTypo} and {@code privacy.inputClass} were all ACCEPTED by
     * the committed schema. The schema is what an editor consults, so the step meant to
     * catch the typo vouched for it first.
     *
     * <p>Doing this in code rather than by hand in the literal means a future section
     * cannot be added open by accident, which is how this drift arrived. Sections that
     * are genuinely maps keep their schema-valued {@code additionalProperties}: the
     * root, {@code pools}, the pool entries, and the {@code models} array items. Those
     * are the objects with no fixed key set, so closing them would reject every value.
     */
    private static String closedToTheLoader(String schemaJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(schemaJson);
            root.put("additionalProperties", false);
            closeFixedKeyObjects(root.get("properties"));
            applyOperationalDefaults(root.get("properties"));
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("config schema literal is not valid JSON", e);
        }
    }

    /**
     * Writes each operational default into the schema.
     *
     * <p>AUDIT-2026-10-03-af. The schema carried no {@code default} at all, so a field the
     * loader treats as optional was, to anyone reading the schema, a field of unknown value.
     * The defaults live in {@link rahu.cli.config.OperationalDefaults} precisely so they can
     * be read from one place here instead of being retyped into a JSON literal - a literal
     * copy would be exactly the drift this audit is about.
     */
    private static void applyOperationalDefaults(
        com.fasterxml.jackson.databind.JsonNode properties) {
        var defaults = new java.util.LinkedHashMap<String, Object>();
        defaults.put("/context/maxPromptTokens",
            rahu.cli.config.OperationalDefaults.CONTEXT_ALLOWANCE_TOKENS);
        defaults.put("/routing/maximumCandidates",
            rahu.cli.config.OperationalDefaults.MAX_CANDIDATES);
        defaults.put("/routing/confidenceFloor",
            rahu.cli.config.OperationalDefaults.CONFIDENCE_FLOOR);
        defaults.put("/agent/maxCompletionTokens",
            rahu.cli.config.OperationalDefaults.MAX_COMPLETION_TOKENS);
        defaults.put("/agent/maxCostUsd",
            rahu.cli.config.OperationalDefaults.MAX_COST_USD);
        defaults.put("/tools/resultBytes",
            rahu.cli.config.OperationalDefaults.TOOL_RESULT_BYTES);
        defaults.put("/search/maxCandidates",
            rahu.cli.config.OperationalDefaults.RERANK_CANDIDATES);

        for (var entry : defaults.entrySet()) {
            String[] steps = entry.getKey().substring(1).split("/");
            var node = properties;
            for (int i = 0; i < steps.length - 1; i++) {
                node = node.path(steps[i]).path("properties");
            }
            var target = node.path(steps[steps.length - 1]);
            if (target.isMissingNode()) {
                throw new IllegalStateException(
                    "no schema property at " + entry.getKey() + "; OperationalDefaults and the "
                        + "schema literal have drifted apart");
            }
            // put() has no Object overload, and the values are deliberately mixed
            // (Integer, Double, BigDecimal), so each is converted to its JSON node type
            // rather than stringified - a stringified 65536 would be rejected by an editor.
            Object v = entry.getValue();
            com.fasterxml.jackson.databind.JsonNode value = v instanceof Integer i
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.numberNode(i)
                : v instanceof Double d
                    ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.numberNode(d)
                    : v instanceof java.math.BigDecimal b
                        ? com.fasterxml.jackson.databind.node.JsonNodeFactory
                            .instance.numberNode(b)
                        : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                            .textNode(String.valueOf(v));
            ((com.fasterxml.jackson.databind.node.ObjectNode) target).set("default", value);
        }
    }

    /** Sets {@code additionalProperties:false} on each child that has a fixed key set. */
    private static void closeFixedKeyObjects(
        com.fasterxml.jackson.databind.JsonNode properties) {
        for (var field : properties.properties()) {
            var node = field.getValue();
            if ("array".equals(node.path("type").asText())) {
                // An array's element schema is itself a fixed-key object (models[]).
                closeArrayItems(node);
                continue;
            }
            if (!"object".equals(node.path("type").asText())) {
                continue;
            }
            if (node.has("additionalProperties")) {
                // A map: its additionalProperties IS its schema, and it describes what
                // each VALUE must look like. Descend through it, because the value schema
                // can itself be a fixed-key object (a pool entry) or an array of one
                // (a pool's models).
                var value = node.get("additionalProperties");
                if ("object".equals(value.path("type").asText())) {
                    if (value.has("properties")) {
                        closeFixedKeyObjects(value.get("properties"));
                    }
                    closeArrayItems(value);
                }
                continue;
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                .put("additionalProperties", false);
            var nested = node.get("properties");
            if (nested != null) {
                closeFixedKeyObjects(nested);
            }
            closeArrayItems(node);
        }
    }

    /** Closes the object schema inside {@code array}, if it has one. */
    private static void closeArrayItems(com.fasterxml.jackson.databind.JsonNode array) {
        var items = array.get("items");
        if (items != null && "object".equals(items.path("type").asText())
            && !items.has("additionalProperties")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) items)
                .put("additionalProperties", false);
        }
    }

    private static final String TOP_LEVEL_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "Rahu configuration v1",
              "type": "object",
              "required": ["schemaVersion", "mode", "decision", "generation", "routing",
                "pools", "session", "tools", "trace", "privacy"],
              "properties": {
                "schemaVersion": {"const": 1},
                "mode": {"enum": ["offline", "live"]},
                "decision": {
                  "type": "object",
                  "properties": {
                    "adapter": {"type": "string"},
                    "baseUrl": {"type": "string"},
                    "compatibilityProfile": {"type": "string"},
                    "model": {"type": "string"},
                    "apiKeyEnv": {"type": "string"},
                    "timeoutMillis": {"type": "integer", "minimum": 1},
                    "costMode": {"enum": ["local-unbilled", "configured-tariff"]}
                  }
                },
                "generation": {
                  "type": "object",
                  "properties": {
                    "adapter": {"type": "string"},
                    "baseUrl": {"type": "string"},
                    "apiKeyEnv": {"type": "string"},
                    "requireParameters": {"type": "boolean"}
                  }
                },
                "routing": {
                  "type": "object",
                  "properties": {
                    "mode": {"enum": ["shadow", "active"]},
                    "pool": {"type": "string"},
                    "baseline": {"type": "string", "pattern": "^[a-z0-9-]+@(default|none|minimal|low|medium|high|xhigh|max)$"},
                    "fallback": {"type": "string", "pattern": "^[a-z0-9-]+@(default|none|minimal|low|medium|high|xhigh|max)$"},
                    "confidenceField": {"type": "string"},
                    "confidenceFloor": {"type": "number", "minimum": 0, "maximum": 1},
                    "maximumCandidates": {"type": "integer", "minimum": 1, "maximum": 32}
                  }
                },
                "pools": {
                  "type": "object",
                  "additionalProperties": {
                    "type": "object",
                    "properties": {
                      "models": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "properties": {
                            "alias": {"type": "string"},
                            "id": {"type": "string"},
                            "reasoning": {"type": "array", "items": {"enum":
                              ["none", "minimal", "low", "medium", "high", "xhigh", "max", "default"]}},
                            "description": {"type": "string"}
                          },
                          "required": ["alias", "id", "reasoning"]
                        }
                      }
                    }
                  }
                },
                "session": {
                  "type": "object",
                  "properties": {
                    "mode": {"const": "in-process"},
                    "maxTurns": {"type": "integer", "minimum": 1},
                    "maxCostUsd": {"type": "string", "pattern": "^\\\\d+(\\\\.\\\\d{1,4})?$"}
                  }
                },
                "orchestration": {"type": "object", "properties": {"mode": {"const": "single"}}},
                "tools": {
                  "type": "object",
                  "properties": {
                    "root": {"type": "string"},
                    "enabled": {"type": "array", "items": {"type": "string"}},
                    "maxCallsPerStep": {"type": "integer", "minimum": 1},
                    "resultBytes": {"type": "integer", "minimum": 1}
                  }
                },
                "trace": {
                  "type": "object",
                  "properties": {
                    "directory": {"type": "string"},
                    "capture": {"enum": ["metadata", "payloads"]},
                    "onFailure": {"const": "stop"}
                  }
                },
                "privacy": {
                  "type": "object",
                  "properties": {
                    "mode": {"const": "strict"},
                    "onUnknown": {"const": "block"},
                    "inputClassification": {"enum": ["unknown", "approved-nonsensitive"]},
                    "sourcePolicyFile": {"type": "string"}
                  }
                },
                "context": {
                  "type": "object",
                  "properties": {
                    "instructionFiles": {"type": "array", "items": {"type": "string"}},
                    "maxPromptTokens": {"type": "integer", "minimum": 1},
                    "routerStateBytes": {"type": "integer", "minimum": 1}
                  }
                },
                "agent": {
                  "type": "object",
                  "properties": {
                    "maxGenerationAttempts": {"type": "integer", "minimum": 1},
                    "deadlineSeconds": {"type": "integer", "minimum": 1},
                    "maxCostUsd": {"type": "string", "pattern": "^\\\\d+(\\\\.\\\\d{1,4})?$"},
                    "maxCompletionTokens": {"type": "integer", "minimum": 1},
                    "maxCompactions": {"type": "integer", "minimum": 0}
                  }
                },
                "catalog": {
                  "type": "object",
                  "properties": {
                    "cacheTtlSeconds": {"type": "integer", "minimum": 1},
                    "allowStale": {"type": "boolean"},
                    "maximumStaleSeconds": {"type": "integer", "minimum": 1}
                  }
                },
                "summarisation": {
                  "type": "object",
                  "description": "Optional separate pool for compaction summaries. Absent means summarisation shares the routing pool.",
                  "properties": {
                    "pool": {"type": "string"},
                    "baseline": {"type": "string"},
                    "fallback": {"type": "string"}
                  }
                },
                "search": {
                  "type": "object",
                  "description": "Relevance rerank for search observations. Absent means off. Shadow scores and reports the proposed order without applying it; enforce reorders. Each search costs one decision call once enabled.",
                  "properties": {
                    "mode": {"type": "string", "enum": ["off", "shadow", "enforce"]},
                    "maxCandidates": {"type": "integer", "minimum": 1, "maximum": 1000}
                  }
                },
                "injection": {
                  "type": "object",
                  "description": "Injection-risk overlay on tool observations. Absent means off. Do not set enforce until the shadow threshold is calibrated: the InjecAgent 0.10 does not transfer to real observations.",
                  "properties": {
                    "mode": {"type": "string", "enum": ["off", "shadow", "enforce"]},
                    "threshold": {"type": "number", "minimum": 0, "maximum": 1}
                  }
                }
              },
              "additionalProperties": true
            }
            """;

    /** Writes docs/generated/ artifacts from the repo root. */
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path generated = root.resolve("docs/generated");
        Files.createDirectories(generated);
        Files.writeString(generated.resolve("config.schema.json"),
            configSchemaJson(), StandardCharsets.UTF_8);
        System.out.println("wrote " + generated.resolve("config.schema.json"));
    }
}
