package rahu.cli.live;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import rahu.core.decision.DecisionResult;
import rahu.core.decision.TurnProfile;
import rahu.systemone.DecisionEngine;
import rahu.systemone.DecisionQuestions;

/**
 * Maps one batched decision onto a TurnProfile (systemone.md line 9: classification
 * and relevance are independent and may share a dispatch). Code holds the control
 * flow: the profile is advisory, it never widens capability. Every failure path
 * degrades closed — unknown class, unjudgeable tools stay available.
 */
public final class ProfileDecider {

    private final DecisionEngine decision;
    private final List<String> permittedTools;

    public ProfileDecider(DecisionEngine decision, List<String> permittedTools) {
        this.decision = decision;
        this.permittedTools = List.copyOf(permittedTools);
    }

    /** One batched profile decision: task classification plus per-tool relevance. */
    public TurnProfile decide(String request, double contextPressure) {
        var state = new DecisionEngine.State("TASK_CLASSIFICATION", request, contextPressure);
        var questions = new ArrayList<DecisionEngine.Question>();
        questions.add(DecisionQuestions.classification());
        questions.addAll(DecisionQuestions.toolRelevance(permittedTools));

        Map<String, DecisionResult> answers;
        try {
            answers = decision.askAll(state, questions);
        } catch (RuntimeException e) {
            // Transport-level failure: unknown class, full permitted set, degraded.
            return TurnProfile.from(Optional.empty(), Set.of(), permittedTools,
                Optional.empty());
        }

        Optional<DecisionResult> classification =
            Optional.ofNullable(answers.get("taskClass"));
        Set<String> included = new LinkedHashSet<>();
        for (String tool : permittedTools) {
            DecisionResult judgment = answers.get("tool:" + tool);
            // A judged-irrelevant tool narrows away; an unjudgeable one stays (fail closed).
            if (!(judgment instanceof DecisionResult.ValidNoul noul) || noul.value()) {
                included.add(tool);
            }
        }
        return TurnProfile.from(classification, included, permittedTools, Optional.empty());
    }
}
