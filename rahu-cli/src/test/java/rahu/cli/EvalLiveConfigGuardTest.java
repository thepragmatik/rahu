package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Honest eval reporting (S12a). A live-mode config must never silently run the
 * offline fake path and label itself live — the report must describe what
 * actually executed.
 */
class EvalLiveConfigGuardTest {

    @TempDir
    Path tmp;

    /** Surefire runs from the module dir; the repo root is its parent. */
    private static Path repoFile(String relative) {
        Path moduleDir = Path.of(System.getProperty("user.dir"));
        Path root = moduleDir.resolve("docs").toFile().exists() ? moduleDir : moduleDir.getParent();
        return root.resolve(relative);
    }

    private static String evalErr(Path config) {
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(new StringWriter(), true));
        cmd.setErr(new PrintWriter(err, true));
        int code = cmd.execute("eval",
            "--suite", repoFile("docs/evals/suites/smoke-v1.json").toString(),
            "--config", config.toString());
        return code + "|" + err;
    }

    @Test
    void liveConfigRefusesOfflineEval() throws Exception {
        String offline = Files.readString(repoFile("examples/offline.json"));
        Path live = tmp.resolve("live.json");
        Files.writeString(live, offline.replace("\"mode\": \"offline\"", "\"mode\": \"live\""));

        String result = evalErr(live);
        assertTrue(result.startsWith("3|"),
            "a live config without --live must refuse, not fake an offline run: " + result);
        assertTrue(result.contains("requires an explicit budget"), result);
    }

    @Test
    void offlineReportLabelsTheModeThatRan() throws Exception {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(new StringWriter(), true));
        int code = cmd.execute("eval",
            "--suite", repoFile("docs/evals/suites/smoke-v1.json").toString(),
            "--config", repoFile("examples/offline.json").toString());

        assertEquals(0, code);
        assertTrue(out.toString().contains("\"mode\":\"offline\""),
            "the report must name the mode that actually executed: " + out);
        assertTrue(!out.toString().contains("\"mode\":\"live\""),
            "an offline run must never claim live mode");
    }
}
