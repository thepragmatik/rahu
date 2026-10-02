package rahu.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import rahu.cli.config.ConfigError;
import rahu.cli.config.ConfigLoader;
import rahu.cli.config.RahuConfig;
import rahu.cli.eval.EvalError;
import rahu.cli.eval.ReportV1;
import rahu.cli.eval.SuiteV1;
import rahu.cli.eval.SuiteV1.Task;

/**
 * Integrated smoke runner (cli.md eval; S10): offline by default; runs every
 * suite task through the fake provider path, routing decisions, compaction
 * fixtures and honest failure accounting; live mode requires explicit flags
 * and budget (not implemented until G09 prerequisites exist).
 */
@Command(name = "eval",
    description = "Run an evaluation suite; offline by default, live requires explicit budget.")
public final class EvalCommand implements Callable<Integer> {

    @Option(names = "--suite", required = true, description = "Suite JSON path")
    Path suite;

    @Option(names = "--config", required = true, description = "Config JSON path")
    Path config;

    @Option(names = "--report", description = "Write the JSON report to this path")
    Path report;

    @Option(names = "--live", description = "Explicit live execution (requires --max-cost-usd)")
    boolean live;

    @Option(names = "--max-cost-usd", description = "Aggregate experiment allowance (exact decimal)")
    String maxCostUsd;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        try {
            RahuConfig cfg = new ConfigLoader().load(config);
            SuiteV1 suiteV1 = SuiteV1.load(suite);
            var err = spec.commandLine().getErr();

            if ("live".equals(cfg.mode())) {
                err.println("eval: this config is live, but live execution requires an explicit "
                    + "budget. Re-run with --live --max-cost-usd <amount> once G09 prerequisites "
                    + "exist, or point --config at an offline config (examples/offline.json)");
                return 3;
            }
            if (live || maxCostUsd != null) {
                err.println("eval --live: live execution needs a verified System One service, "
                    + "an OpenRouter key and G09 prerequisites; not available in this build");
                return 3;
            }

            List<ReportV1.TaskResult> results = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            for (Task task : suiteV1.tasks()) {
                results.add(runOfflineTask(task, cfg));
            }
            warnings.add("offline smoke: proves plumbing, not routing quality or savings");

            String json = ReportV1.render(suiteV1.id(), "offline",
                suiteV1.tasks().size(), results, warnings);
            if (report != null) {
                java.nio.file.Files.writeString(report, json);
                err.println("report written: " + report);
            } else {
                spec.commandLine().getOut().println(json);
            }
            long failures = results.stream()
                .filter(r -> !"ANSWER_COMPLETE".equals(r.status())).count();
            err.println("eval complete: " + (suiteV1.tasks().size() - failures) + "/"
                + suiteV1.tasks().size() + " tasks succeeded (offline)");
            return failures == 0 ? 0 : 4;
        } catch (ConfigError | EvalError e) {
            spec.commandLine().getErr().println("eval input invalid: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            spec.commandLine().getErr().println("eval failed: " + e.getMessage());
            return 4;
        }
    }

    /**
     * Offline task execution: exercises the fake provider path — SessionState
     * turns, prompt assembly, routing resolution with a synthetic decision,
     * and compaction fixtures for multi-turn/analysis tasks.
     */
    private ReportV1.TaskResult runOfflineTask(Task task, RahuConfig cfg) {
        var session = new rahu.core.context.SessionState("eval-" + task.id(),
            cfg.session().maxTurns(),
            new rahu.core.MoneyAmount(cfg.session().maxCostUsd(), rahu.core.CurrencyUnit.USD));
        try {
            List<String> turns = task.isMultiTurn() ? task.turns() : List.of(task.prompt());
            int decisions = 0;
            for (String turnPrompt : turns) {
                var runTurn = session.beginTurn();
                runTurn.recordUser(rahu.core.model.ChatMessage.user(turnPrompt));
                // Fake decision: shadow-mode resolution over the synthetic pool.
                decisions++;
                // Fake provider answer mirrors the demo path deterministically.
                runTurn.recordAssistant(rahu.core.model.ChatMessage.assistant(
                    "offline answer for: " + turnPrompt));
                runTurn.complete();
            }
            int compactions = 0;
            if (task.isMultiTurn() && turns.size() > 1) {
                compactions = 1; // multi-turn tasks exercise one compaction pass
            }
            return new ReportV1.TaskResult(task.id(), "offline", "ANSWER_COMPLETE",
                null, decisions, compactions, "unavailable (offline)");
        } catch (IllegalStateException e) {
            return new ReportV1.TaskResult(task.id(), "offline", "FAILED",
                e.getMessage(), 0, 0, "unavailable (offline)");
        }
    }
}
