package rahu.cli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;

/**
 * Evaluation report v1 (artifacts.md): honest success/failure accounting with
 * availability markers; offline runs report costs as unavailable, never zero.
 */
public final class ReportV1 {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record TaskResult(String taskId, String mode, String status,
        String terminalReason, int decisionCount, int compactionCount,
        String costAvailability) {
    }

    public static String render(String suiteId, String configMode, int total,
        List<TaskResult> results, List<String> warnings) {

        long succeeded = results.stream().filter(r -> "ANSWER_COMPLETE".equals(r.status()))
            .count();
        long failed = results.stream().filter(r -> !"ANSWER_COMPLETE".equals(r.status()))
            .count();

        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("suiteId", suiteId);
        root.put("mode", configMode);
        root.put("requested", "offline");
        root.put("tasksTotal", total);
        root.put("succeeded", succeeded);
        root.put("failed", failed);
        ArrayNode arr = root.putArray("taskResults");
        for (TaskResult r : results) {
            ObjectNode t = arr.addObject();
            t.put("taskId", r.taskId());
            t.put("mode", r.mode());
            t.put("status", r.status());
            t.put("terminalReason", r.terminalReason());
            t.put("decisionCalls", r.decisionCount());
            t.put("compactions", r.compactionCount());
            t.put("cost", r.costAvailability());
        }
        ArrayNode warn = root.putArray("warnings");
        warnings.forEach(warn::add);
        root.put("costNote", "offline run: no provider charges; reported costs unavailable");
        return root.toString();
    }

    private ReportV1() {
    }
}
