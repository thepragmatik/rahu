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
}
