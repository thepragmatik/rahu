package rahu.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.CurrencyUnit;
import rahu.core.MoneyAmount;
import rahu.core.model.ChatMessage;
import rahu.core.runtime.Ledger;
import rahu.core.runtime.RunPhase;
import rahu.core.runtime.RunStateMachine;
import rahu.core.routing.TerminalReason;

/** A22/A32: in-process session, one active turn, reset keeps liability. */
class SessionStateTest {

    private static MoneyAmount usd(String s) {
        return new MoneyAmount(new BigDecimal(s), CurrencyUnit.USD);
    }

    @Test
    @DisplayName("A22: two turns retain ordered history; turn count advances; run IDs differ")
    void twoTurnsRetainHistory() {
        var session = new SessionState("s-1", 20, usd("3.00"));

        SessionState.RunHandle t1 = session.beginTurn();
        t1.recordUser(ChatMessage.user("What owns routing?"));
        t1.recordAssistant(ChatMessage.assistant("rahu-core owns routing policy."));
        t1.complete();

        SessionState.RunHandle t2 = session.beginTurn();
        t2.recordUser(ChatMessage.user("Why?"));
        t2.recordAssistant(ChatMessage.assistant("Provider DTOs must stay out of core."));
        t2.complete();

        assertEquals(2, session.turnCount());
        List<ChatMessage> history = session.history();
        assertEquals(4, history.size());
        assertEquals("What owns routing?", history.get(0).content());
        assertEquals("Why?", history.get(2).content());
        assertTrue(session.maxTurnsReached() == false);
    }

    @Test
    @DisplayName("One active turn per session: a second begin before completion is refused")
    void oneActiveTurn() {
        var session = new SessionState("s-1", 20, usd("3.00"));
        session.beginTurn();
        assertThrows(IllegalStateException.class, session::beginTurn);
    }

    @Test
    @DisplayName("A32: reset clears content but never the ledger or turn count")
    void resetKeepsLedgerAndCount() {
        var session = new SessionState("s-1", 20, usd("3.00"));
        var ledger = session.ledger();
        var reservation = ledger.reserve(usd("1.00"), "gen-t1");
        ledger.settle(reservation, usd("1.00"), true);

        SessionState.RunHandle t = session.beginTurn();
        t.recordUser(ChatMessage.user("hello"));
        t.complete();
        session.resetConversation();

        assertEquals(0, session.history().size(), "content cleared");
        assertEquals(1, session.turnCount(), "turn count survives reset");
        assertEquals(0, session.ledger().settled().amount()
            .compareTo(new BigDecimal("1.00")), "liability survives reset");
    }

    @Test
    @DisplayName("A32: a failed turn keeps the user request and failure, never a partial answer")
    void failedTurnKeepsRequestNotPartialAnswer() {
        var session = new SessionState("s-1", 20, usd("3.00"));
        SessionState.RunHandle t = session.beginTurn();
        t.recordUser(ChatMessage.user("tell me everything"));
        t.recordAssistant(ChatMessage.assistant("partial gen"));
        t.fail(TerminalReason.PROVIDER_FAILURE);

        var failed = session.lastFailedTurn();
        assertTrue(failed.isPresent());
        assertEquals("tell me everything", failed.get().userRequest().content());
        assertEquals(TerminalReason.PROVIDER_FAILURE, failed.get().reason());
        assertTrue(session.history().stream().noneMatch(
            m -> "partial gen".equals(m.content())),
            "incomplete generated content must not enter history");
    }

    @Test
    @DisplayName("Session turn limit stops new turns with a clear reason")
    void turnLimitStops() {
        var session = new SessionState("s-1", 1, usd("3.00"));
        session.beginTurn().complete();
        assertThrows(IllegalStateException.class, session::beginTurn);
    }

    @Test
    @DisplayName("Session ledger is hierarchical over a run ledger")
    void ledgerNesting() {
        var session = new SessionState("s-1", 20, usd("3.00"));
        var run = session.ledger().childLedger(usd("1.00"));
        var r = run.reserve(usd("0.25"), "gen-1");
        run.settle(r, usd("0.25"), true);
        assertEquals(0, session.ledger().settled().amount()
            .compareTo(new BigDecimal("0.25")));
    }

    @Test
    @DisplayName("RunHandle wires a RunStateMachine that reaches terminal on complete/fail")
    void runHandleTransitions() {
        var session = new SessionState("s-1", 20, usd("3.00"));
        SessionState.RunHandle t = session.beginTurn();
        assertTrue(t.stateMachine().phase() == RunPhase.CREATED
            || t.stateMachine().phase() != RunPhase.TERMINAL);
        t.complete();
        assertTrue(t.stateMachine().isTerminal());
    }
}
