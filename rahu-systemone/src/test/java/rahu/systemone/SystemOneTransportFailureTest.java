package rahu.systemone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.decision.DecisionResult;

/**
 * F-8: transport faults collapsed to a single {@code TIMEOUT}.
 *
 * <p>{@code IOException} covered two outcomes that are not the same fact: a connection
 * that was <em>refused</em> (the service demonstrably never saw the request) and a
 * <em>read timeout</em> (the service may have processed it and billed for it). Calling
 * both "TIMEOUT" destroys the only distinction a retry needs, and systemone.md:48
 * requires it: "Uncertain transport outcomes remain traceable even if the decision
 * service is nominally side-effect-free because billing may have occurred."
 *
 * <p>Note the decision port has NO ledger or cost accounting at all (the reservation in
 * {@code LiveTurnDriver} covers generation only), so nothing downstream re-derives this
 * distinction. The boundary is the only place it can exist.
 */
class SystemOneTransportFailureTest {

    private static final DecisionEngine.State STATE =
        new DecisionEngine.State("repository-analysis", "request", 0.85);

    private static List<DecisionEngine.Question> question() {
        return List.of(new DecisionEngine.ChoiceQuestion("route",
            java.util.Map.of("fast@low", "Prefer the cheap candidate")));
    }

    private static SystemOneHttpAdapter adapterTo(String endpoint, int timeoutMillis) {
        return new SystemOneHttpAdapter(endpoint, "demo-decision", "jev-compatible-v1",
            timeoutMillis, () -> Optional.empty());
    }

    /**
     * Narrows to the typed failure.
     *
     * <p>Not an {@code assertTrue(x instanceof T t)} pattern variable: that binding does
     * not survive the assertion, so the next line cannot see it.
     */
    private static DecisionResult.Failure failureOf(DecisionResult result) {
        assertTrue(result instanceof DecisionResult.Failure,
            "expected a typed Failure but got " + result.getClass().getSimpleName());
        return (DecisionResult.Failure) result;
    }

    /** A port with nothing listening: the TCP handshake is refused. */
    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    @DisplayName("A refused connection is definite: the service never saw the request")
    void refusedConnectionIsDefinite() throws Exception {
        SystemOneHttpAdapter adapter = adapterTo(
            "http://127.0.0.1:" + closedPort() + "/v1/systemone", 5000);

        DecisionResult result = adapter.ask(STATE, question());

        var f = failureOf(result);
        assertEquals(DecisionResult.FailureKind.UNREACHABLE, f.kind(),
            "connect refusal is DEFINITE (never dispatched), not a timeout and not"
                + " ambiguous: a retry here cannot duplicate a billed call");
    }

    @Test
    @DisplayName("A read timeout is AMBIGUOUS: the service may have processed it")
    void readTimeoutIsAmbiguous() throws Exception {
        // A server that accepts the connection and then never answers. The socket is
        // open, so this is a read timeout rather than a refusal - the request WAS
        // delivered, which is exactly the case a retry must not duplicate blindly.
        HttpServer silent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        silent.createContext("/v1/systemone", exchange -> {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        silent.start();
        try {
            SystemOneHttpAdapter adapter = adapterTo(
                "http://127.0.0.1:" + silent.getAddress().getPort() + "/v1/systemone", 400);

            DecisionResult result = adapter.ask(STATE, question());

            var f = failureOf(result);
            assertEquals(DecisionResult.FailureKind.AMBIGUOUS, f.kind(),
                "a read timeout MAY have been processed and billed; systemone.md:48"
                    + " requires this stay distinguishable from a refusal");
        } finally {
            silent.stop(0);
        }
    }

    @Test
    @DisplayName("The two transport outcomes are told apart, not merged")
    void theTwoOutcomesDiffer() throws Exception {
        int port = closedPort();
        var definite = adapterTo("http://127.0.0.1:" + port + "/v1/systemone", 5000)
            .ask(STATE, question());
        HttpServer silent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        silent.createContext("/v1/systemone", exchange -> {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        silent.start();
        DecisionResult maybe;
        try {
            maybe = adapterTo(
                "http://127.0.0.1:" + silent.getAddress().getPort() + "/v1/systemone", 400)
                .ask(STATE, question());
        } finally {
            silent.stop(0);
        }

        assertFalse(failureOf(definite).kind() == failureOf(maybe).kind(),
            "merging these is the defect; the assertion is that they differ");
    }

    @Test
    @DisplayName("An unresolvable host is UNREACHABLE, not ambiguous")
    void unresolvableHostIsUnreachable() {
        // Driven through the classifier rather than a live call: EndpointPolicy refuses
        // plain HTTP to a non-loopback host (correctly, and worth knowing), so a fake DNS
        // name cannot be dialled over http://. The classification under test does not
        // need a socket.
        var f = SystemOneHttpAdapter.transportFailure(
            new java.net.UnknownHostException("no-such-host.invalid"));

        assertEquals(DecisionResult.FailureKind.UNREACHABLE, f.kind(),
            "DNS never resolved, so the request was never sent");
    }

    @Test
    @DisplayName("safeReason names the cause WITHOUT echoing the host")
    void causeIsDiagnosableAndSafe() {
        // The load-bearing leak is on the DNS path, not the refusal path: a refused
        // connection reads "Connection refused (connect failed)" and names nothing, so
        // a no-leak assertion there is VACUOUS. Verified with jshell - an
        // UnknownHostException message embeds the hostname verbatim
        // ("no-such-host.invalid: nodename nor servname provided"), so this path is
        // where echoing e.getMessage() would actually leak.
        var f = SystemOneHttpAdapter.transportFailure(
            new java.net.UnknownHostException("no-such-host.invalid"));

        assertTrue(f.safeReason() != null && !f.safeReason().isBlank(),
            "a bare kind with no reason is not diagnosable");
        assertTrue(f.safeReason().contains("UnknownHostException"),
            "the cause must be nameable so an operator can act on it: " + f.safeReason());
        assertFalse(f.safeReason().contains("no-such-host.invalid"),
            "safeReason must not echo the host (privacy: values never reach output)");
    }

    @Test
    @DisplayName("An unrecognised fault is AMBIGUOUS, not optimistically definite")
    void unrecognisedFaultIsTreatedAsTheDangerousCase() {
        // Nothing today produces this type, which is exactly why it needs pinning: a
        // future JDK exception must not be classified by accident. The safe default is
        // AMBIGUOUS - we cannot prove the request was not delivered, so we must not
        // claim it was.
        assertEquals(DecisionResult.FailureKind.AMBIGUOUS,
            SystemOneHttpAdapter.transportFailure(new java.io.IOException("something new")).kind(),
            "an unknown fault must not be reported as never-sent");
        assertEquals(DecisionResult.FailureKind.AMBIGUOUS,
            SystemOneHttpAdapter.transportFailure(
                new IllegalArgumentException("bad request")).kind(),
            "an unrecognised non-IO fault must not be reported as never-sent");
        assertEquals(DecisionResult.FailureKind.UNREACHABLE,
            SystemOneHttpAdapter.transportFailure(
                new java.net.http.HttpConnectTimeoutException("t")).kind(),
            "a CONNECT timeout is definite even though it is a timeout subtype");
        assertEquals(DecisionResult.FailureKind.AMBIGUOUS,
            SystemOneHttpAdapter.transportFailure(
                new java.net.http.HttpTimeoutException("t")).kind(),
            "a plain read timeout is ambiguous");
        assertEquals(DecisionResult.FailureKind.UNREACHABLE,
            SystemOneHttpAdapter.transportFailure(
                new java.net.UnknownHostException("no-such-host.invalid")).kind(),
            "DNS never resolved, so nothing was sent");
    }
}
