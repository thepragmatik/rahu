package rahu.core.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import rahu.core.MoneyAmount;
import rahu.core.model.ChatMessage;
import rahu.core.runtime.Ledger;
import rahu.core.runtime.RunPhase;
import rahu.core.runtime.RunStateMachine;
import rahu.core.routing.TerminalReason;

/**
 * In-process chat session (context.md): bounded history, one active turn,
 * aggregate ledger; reset clears content but never liability or counts.
 */
public final class SessionState {

    private final String sessionId;
    private final int maxTurns;
    private final Ledger ledger;
    private final List<ChatMessage> history = new ArrayList<>();
    private int turnCount;
    private RunHandle active;
    private FailedTurn lastFailure;

    public SessionState(String sessionId, int maxTurns, MoneyAmount allowance) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(allowance, "allowance");
        if (maxTurns <= 0) {
            throw new IllegalArgumentException("maxTurns must be positive");
        }
        this.sessionId = sessionId;
        this.maxTurns = maxTurns;
        this.ledger = new Ledger(allowance);
    }

    public synchronized RunHandle beginTurn() {
        if (active != null) {
            throw new IllegalStateException("one active turn per session");
        }
        if (turnCount >= maxTurns) {
            throw new IllegalStateException("session turn limit reached (" + maxTurns + ")");
        }
        active = new RunHandle(this, new RunStateMachine());
        return active;
    }

    public synchronized String sessionId() {
        return sessionId;
    }

    public synchronized int turnCount() {
        return turnCount;
    }

    public synchronized List<ChatMessage> history() {
        return List.copyOf(history);
    }

    public synchronized Ledger ledger() {
        return ledger;
    }

    public synchronized boolean maxTurnsReached() {
        return turnCount >= maxTurns;
    }

    public synchronized Optional<FailedTurn> lastFailedTurn() {
        return Optional.ofNullable(lastFailure);
    }

    /**
     * A32: clear conversational content; counts and money are untouchable.
     *
     * <p>The guard runs FIRST, and that ordering is the whole point. It used to run
     * last:
     *
     * <pre>{@code
     * history.clear();
     * lastFailure = null;
     * if (active != null && !active.completed) { throw ... }
     * }</pre>
     *
     * which cleared the conversation and the failure record and THEN refused.
     * {@code ChatCommand} catches the throw and prints "reset refused: cannot reset
     * during an active turn" -- so the operator was told the reset had not happened
     * after it had already wiped the conversation. Refusing an operation while
     * applying part of it is the reported-vs-actual shape from AUDIT-j, and this
     * instance loses data: a refused reset silently destroyed the transcript.
     *
     * <p>A refusal must therefore be total. Validate before mutating.
     */
    public synchronized void resetConversation() {
        if (active != null && !active.completed) {
            throw new IllegalStateException("cannot reset during an active turn");
        }
        history.clear();
        lastFailure = null;
    }

    private void onTurnComplete(RunHandle handle, ChatMessage user, ChatMessage assistant) {
        history.add(user);
        history.add(assistant);
        turnCount++;
        active = null;
    }

    private void onTurnFailed(RunHandle handle, ChatMessage user, ChatMessage partial,
        TerminalReason reason) {
        lastFailure = new FailedTurn(user, reason);
        turnCount++;
        active = null;
    }

    /** One turn's run: owns its state machine; feeds the session on completion. */
    public static final class RunHandle {

        private final SessionState session;
        private final RunStateMachine stateMachine = new RunStateMachine();
        private final String runId = "run-" + java.util.UUID.randomUUID();
        private ChatMessage user;
        private ChatMessage assistant;
        private boolean completed;
        private boolean ended;

        private RunHandle(SessionState session, RunStateMachine stateMachine) {
            this.session = session;
        }

        public String runId() {
            return runId;
        }

        public RunStateMachine stateMachine() {
            return stateMachine;
        }

        public void recordUser(ChatMessage message) {
            if (ended) {
                throw new IllegalStateException("turn already ended");
            }
            this.user = message;
            stateMachine.transitionTo(RunPhase.DECIDING);
        }

        public void recordAssistant(ChatMessage message) {
            if (ended) {
                throw new IllegalStateException("turn already ended");
            }
            this.assistant = message;
        }

        public void complete() {
            if (ended) {
                throw new IllegalStateException("turn already ended");
            }
            stateMachine.transitionTo(RunPhase.TERMINAL);
            ended = true;
            completed = true;
            session.onTurnComplete(this, user, assistant);
        }

        public void fail(TerminalReason reason) {
            if (ended) {
                throw new IllegalStateException("turn already ended");
            }
            stateMachine.transitionTo(RunPhase.TERMINAL);
            ended = true;
            session.onTurnFailed(this, user, assistant, reason);
        }
    }

    /** Failure record: user request + terminal reason; never partial output. */
    public record FailedTurn(ChatMessage userRequest, TerminalReason reason) {
    }
}
