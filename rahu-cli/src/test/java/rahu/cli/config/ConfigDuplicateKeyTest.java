package rahu.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AUDIT-2026-10-03-ac: {@code artifacts.md} promises the config loader rejects
 * duplicate keys. It does - at every nesting level - but nothing tested it.
 *
 * <p>That is the worst shape for a strictness feature: it looks like dead code that a
 * well-meaning "simplification" could delete, and its absence is invisible until a config
 * with a repeated key is silently read as the last value. Jackson accepts duplicates by
 * default, so a regression here would not throw - it would quietly pick a value the
 * operator never intended.
 *
 * <p>Each case asserts the message names the offending field, because "duplicate key
 * somewhere" sends the operator hunting through the whole file.
 */
class ConfigDuplicateKeyTest {

    @TempDir
    Path tmp;

    private static final String VALID = """
        {
          "schemaVersion": 1,
          "mode": "offline",
          "decision": {"adapter": "fake", "model": "demo-decision"},
          "generation": {"adapter": "fake"},
          "routing": {"mode": "shadow", "pool": "demo", "baseline": "fast@low",
                      "fallback": "fast@low"},
          "pools": {"demo": {"models": [
            {"alias": "fast", "id": "demo-fast", "reasoning": ["low"]}]}},
          "session": {"mode": "in-process"},
          "tools": {"root": "."},
          "trace": {"directory": ".rahu/runs"},
          "privacy": {"mode": "strict"}
        }
        """;

    private Path write(String content) {
        Path p = tmp.resolve("config.json");
        try {
            Files.writeString(p, content);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        return p;
    }

    private void assertRejected(String label, String json, String offendingField) {
        ConfigError e = assertThrows(ConfigError.class,
            () -> new ConfigLoader().load(write(json)), label);
        assertTrue(e.getMessage().contains("duplicate"), label + ": " + e.getMessage());
        assertTrue(e.getMessage().contains(offendingField),
            label + " must name the offending field so the operator need not hunt: "
                + e.getMessage());
    }

    @Test
    @DisplayName("the unmodified fixture loads, so the cases below reject for the stated reason")
    void sanityTheFixtureIsValid() {
        assertEquals(1, new ConfigLoader().load(write(VALID)).schemaVersion());
    }

    @Test
    @DisplayName("a repeated top-level key is refused, naming the key")
    void duplicateTopLevelKeyIsRefused() {
        assertRejected("duplicate schemaVersion", """
            {"schemaVersion": 1, "schemaVersion": 2, "mode": "offline"}
            """, "schemaVersion");
    }

    @Test
    @DisplayName("a repeated nested key is refused, naming the key")
    void duplicateNestedKeyIsRefused() {
        assertRejected("duplicate decision.adapter", VALID.replace(
            "{\"adapter\": \"fake\", \"model\": \"demo-decision\"}",
            "{\"adapter\": \"fake\", \"adapter\": \"fake\", \"model\": \"demo-decision\"}"),
            "adapter");
    }

    @Test
    @DisplayName("a repeated key inside a pool model is refused, naming the key")
    void duplicateDeeplyNestedKeyIsRefused() {
        assertRejected("duplicate pool model id", VALID.replace(
            "{\"alias\": \"fast\", \"id\": \"demo-fast\", \"reasoning\": [\"low\"]}",
            "{\"alias\": \"fast\", \"id\": \"demo-fast\", \"reasoning\": [\"low\"],"
                + " \"id\": \"other\"}"),
            "id");
    }

    @Test
    @DisplayName("repeating a key with an IDENTICAL value is still refused")
    void identicalDuplicateIsStillRefused() {
        // The dangerous case. Both copies read as the same thing, so nothing downstream
        // looks wrong; the file is simply ambiguous about which line is authoritative.
        assertRejected("identical duplicate schemaVersion", """
            {"schemaVersion": 1, "schemaVersion": 1, "mode": "offline"}
            """, "schemaVersion");
    }

    @Test
    @DisplayName("one loader instance stays strict across successive loads")
    void strictnessIsNotLostAfterASuccessfulLoad() {
        // The loader disables the feature on the shared factory and re-enables it on the
        // parser. If that were left disabled by a code path, the second load in the same
        // process would silently accept a duplicate - so load both through one instance.
        ConfigLoader loader = new ConfigLoader();
        loader.load(write(VALID));
        assertThrows(ConfigError.class, () -> loader.load(write("""
            {"schemaVersion": 1, "schemaVersion": 1, "mode": "offline"}
            """)), "strictness was lost after a successful load");
    }

    @Test
    @DisplayName("a duplicate is refused as a duplicate, not as an unrelated syntax error")
    void duplicateIsNotReportedAsGenericSyntaxError() {
        // parseStrict branches on the message text. If Jackson ever phrases a duplicate
        // differently, the branch falls through to "invalid JSON" and the operator is told
        // to fix the syntax - advice that cannot help, because the syntax is valid.
        ConfigError e = assertThrows(ConfigError.class,
            () -> new ConfigLoader().load(write("""
                {"schemaVersion": 1, "schemaVersion": 1, "mode": "offline"}
                """)));
        assertTrue(e.getMessage().startsWith("duplicate JSON key found"),
            "a duplicate must not be reported as a syntax error: " + e.getMessage());
    }
}