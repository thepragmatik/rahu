package rahu.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.core.model.ChatMessage;

/** A23: deterministic template order, instruction files as data, hashes recorded. */
class PromptAssemblerTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("Order: harness -> operator instructions -> history -> current request")
    void deterministicOrder() throws Exception {
        Path instr = tmp.resolve("conventions.md");
        Files.writeString(instr, "Always cite file paths.");
        var assembler = new PromptAssembler();

        var session = new SessionState("s", 20, new rahu.core.MoneyAmount(
            new java.math.BigDecimal("3.00"), rahu.core.CurrencyUnit.USD));
        SessionState.RunHandle t = session.beginTurn();
        t.recordUser(ChatMessage.user("Why the boundary test?"));
        t.recordAssistant(ChatMessage.assistant("Core must stay JDK-only."));
        t.complete();

        ContextPlan plan = assembler.assemble(
            List.of(instr),
            session.history(),
            ChatMessage.user("Explain the boundary test again."),
            8000);

        List<ChatMessage> prompt = plan.messages();
        assertEquals(ChatMessage.Role.SYSTEM, prompt.get(0).role(), "harness role first");
        assertTrue(prompt.get(0).content().contains("Rahu"));
        assertTrue(prompt.stream().anyMatch(m -> m.role() == ChatMessage.Role.USER
            && m.content().equals("Why the boundary test?")), "history preserved in order");
        assertEquals("Explain the boundary test again.",
            prompt.get(prompt.size() - 1).content(), "current request last");
    }

    @Test
    @DisplayName("Instruction files are data-only user-role context with recorded hashes")
    void instructionFilesAreData() throws Exception {
        Path instr = tmp.resolve("rules.md");
        Files.writeString(instr, "data only");
        var plan = new PromptAssembler().assemble(List.of(instr),
            List.of(), ChatMessage.user("q"), 8000);

        assertTrue(plan.messages().stream().noneMatch(m -> m.role() == ChatMessage.Role.SYSTEM
            && m.content().contains("data only")),
            "operator files must never enter a privileged system role");
        assertTrue(plan.instructionHashes().size() == 1, "hash recorded");
        assertTrue(plan.instructionHashes().get(0).startsWith("rules.md:"));
    }

    @Test
    @DisplayName("Oversized instruction files fail with a typed error, never silent truncation")
    void oversizedInstructionRejected() throws Exception {
        Path big = tmp.resolve("big.md");
        Files.writeString(big, "x".repeat(17 * 1024)); // over the 16 KiB per-file bound
        var ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new PromptAssembler().assemble(List.of(big), List.of(),
                ChatMessage.user("q"), 8000));
        assertTrue(ex.getMessage().contains("16 KiB"));
    }

    @Test
    @DisplayName("Prompt records template version and token estimate availability")
    void planMetadata() {
        var plan = new PromptAssembler().assemble(List.of(), List.of(),
            ChatMessage.user("q"), 8000);
        assertEquals(PromptAssembler.TEMPLATE_VERSION, plan.templateVersion());
        assertTrue(plan.estimatedTokens() > 0, "conservative estimate present");
        assertTrue(plan.estimatedTokens() < 8000, "estimate respects allowance bound");
    }

    @Test
    @DisplayName("An over-budget prompt reports an estimate ABOVE its allowance")
    void overBudgetEstimateIsNotClampedToTheAllowance() {
        // AUDIT-2026-10-03-r. The estimate used to be Math.min(tokens, allowance),
        // so every overflowing input reported exactly the allowance. That made a
        // context three times over budget indistinguishable from a comfortable fit —
        // same estimate, same pressure, same operator line, same trace.
        String huge = "x".repeat(24 * 1024);
        var plan = new PromptAssembler().assemble(List.of(), List.of(),
            ChatMessage.user(huge), 800);

        assertTrue(plan.estimatedTokens() > plan.contextAllowanceTokens(),
            "the estimate must exceed the allowance for an over-budget prompt; it "
                + "reported " + plan.estimatedTokens() + " against an allowance of "
                + plan.contextAllowanceTokens() + ", i.e. the measurement is clamped "
                + "and a real overrun reads as a full context");
        assertTrue(plan.pressure() > 1.0,
            "pressure must exceed 1.0 when the prompt is over budget; it reported "
                + plan.pressure());
        assertEquals(1.0, plan.boundedPressure(),
            "the port-facing value still saturates at 1.0, because "
                + "DecisionEngine.State requires contextPressure in [0,1]");
    }

    @Test
    @DisplayName("The same messages estimate identically under different allowances")
    void estimateDoesNotDependOnTheAllowance() {
        // The defect in one assertion: a MEASUREMENT must not change because an
        // unrelated configuration value changed. Two allowances, one number.
        var messages = List.of(ChatMessage.user("estimate me the same way"));
        var tight = new PromptAssembler().assemble(List.of(), List.of(),
            messages.get(0), 200);
        var loose = new PromptAssembler().assemble(List.of(), List.of(),
            messages.get(0), 100_000);

        assertEquals(tight.estimatedTokens(), loose.estimatedTokens(),
            "the token estimate is a measurement of the messages; it must not depend "
                + "on the allowance (" + tight.estimatedTokens() + " vs "
                + loose.estimatedTokens() + ")");
        assertTrue(loose.pressure() < 1.0,
            "the same prompt comfortably fits a 100k allowance");
        assertTrue(tight.pressure() > loose.pressure(),
            "a tighter allowance must report higher pressure for the same prompt");
    }

    @Test
    @DisplayName("A non-positive allowance is refused, not divided by")
    void nonPositiveAllowanceRefused() {
        // pressure() divides by contextAllowanceTokens. Before this guard the
        // divide produced Infinity or NaN, and Infinity compared >= 0.80 - so a
        // misconfigured zero allowance would trip the compaction trigger with a
        // pressure no operator could interpret.
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new PromptAssembler().assemble(List.of(), List.of(),
                ChatMessage.user("q"), 0));
        assertTrue(ex.getMessage().contains("positive"), "message names the bound");
    }

    @Test
    @DisplayName("ContextPlan itself refuses a non-positive allowance")
    void contextPlanRefusesNonPositiveAllowanceDirectly() {
        // assemble() has its own guard, so the test above proves nothing about the
        // record's invariant — removing the ContextPlan guard left the suite green,
        // which is exactly how a dead guard stays in the code looking load-bearing.
        // ContextPlan is a PUBLIC record, so a caller can build one directly, and
        // pressure() divides by this field.
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new ContextPlan(List.of(), List.of(), 1, 10, 0));
        assertTrue(ex.getMessage().contains("positive"), "message names the bound");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new ContextPlan(List.of(), List.of(), 1, 10, -5));
    }
}
