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
        return """
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
    }

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
