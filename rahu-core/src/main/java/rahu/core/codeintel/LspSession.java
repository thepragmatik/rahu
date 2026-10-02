package rahu.core.codeintel;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One jdtls session, spoken over stdio.
 *
 * <p>jdtls has no CLI: it is a language server that waits for an
 * {@code initialize} request, so every fact the agent wants about Java symbols
 * costs a request/response round trip over this pipe.
 *
 * <p>Every read is bounded by {@link #READ_TIMEOUT_SECONDS}. An unbounded read
 * would reproduce the exact failure this class exists to fix — a jdtls that
 * starts and never answers looks identical to a hang, and in an agent loop it
 * stalls the turn forever with no diagnostic.
 */
final class LspSession implements AutoCloseable {

    /** Wall-clock ceiling for one response. Generous: a cold index is slow. */
    static final long READ_TIMEOUT_SECONDS = 180;

    /**
     * The jdtls to drive, in priority order.
     *
     * <p>NOT the Homebrew build: the pinned snapshot under the profile's {@code lsp/}
     * dir is the one with Java 27 support, and it is a materially newer build
     * (LTK refactoring 3.16.100.v20260923 and batch compiler 3.46.200.v20260930,
     * against Homebrew 1.61.0's 3.16.0.v20260702 and 3.46.100.v20260826). Binding
     * to Homebrew would silently run the older compiler on a Java 27 project.
     *
     * <p>Resolution order lets an operator point at a build without a code change:
     * the {@code RAHU_JDTLS} env var, then the snapshot, then PATH.
     */
    // List.of rejects a null element, and an unset RAHU_JDTLS is null -- so the
    // unset case would fail class initialisation. Build the list explicitly.
    private static final List<String> CANDIDATES = candidates();

    private static List<String> candidates() {
        List<String> out = new ArrayList<>();
        String override = System.getenv("RAHU_JDTLS");
        if (override != null && !override.isBlank()) {
            out.add(override);
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            out.add(home + "/.hermes/profiles/uplift/lsp/jdtls-snapshot/bin/jdtls");
        }
        out.add("jdtls");
        return List.copyOf(out);
    }

    /** Picks the first candidate that exists and is executable, or fails loudly. */
    static String resolveBinary() throws IOException {
        for (String candidate : CANDIDATES) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            File f = new File(candidate);
            if (f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        throw new IOException("no usable jdtls found; tried " + CANDIDATES
                + " (set RAHU_JDTLS to override)");
    }

    private final Process process;
    private final OutputStream out;
    private final BufferedReader in;
    private final Path root;
    private final Map<String, Object> capabilities;
    private int nextId = 1;
    private boolean closed;

    private LspSession(Process process, Path root) {
        this.process = process;
        this.out = process.getOutputStream();
        this.in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.root = root;
        // Map.of() is immutable and clear() on it throws; this is filled once the
        // initialize reply lands.
        this.capabilities = new LinkedHashMap<>();
    }

    /** Spawns jdtls and completes the initialize/initialized handshake. */
    static LspSession start(Path workspaceRoot) throws IOException, InterruptedException {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        ProcessBuilder pb = new ProcessBuilder(resolveBinary());
        pb.directory(root.toFile());
        // jdtls logs heavily to stderr; let it through rather than filling the pipe,
        // which would block the server once the OS buffer filled.
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("cannot start jdtls at " + pb.command().get(0) + " (is it installed?)", e);
        }

        LspSession session = new LspSession(proc, root);
        Map<String, Object> init = session.request("initialize", Map.of(
                "processId", ProcessHandle.current().pid(),
                "rootUri", root.toUri().toString(),
                "capabilities", Map.of(),
                "workspaceFolders", List.of(Map.of("uri", root.toUri().toString(), "name", "rahu"))));
        session.notify("initialized", Map.of());

        // Capabilities are nested under "result", not at the top level of the
        // response envelope -- reading init.get("capabilities") silently yields an
        // empty map and the caller concludes the server has no features at all.
        Object result = init.get("result");
        if (result instanceof Map<?, ?> resultMap
                && resultMap.get("capabilities") instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            session.capabilities.putAll(typed);
        }
        if (session.capabilities.isEmpty()) {
            throw new IOException("jdtls returned no capabilities; keys were " + init.keySet());
        }
        return session;
    }

    Map<String, Object> serverCapabilities() {
        return Map.copyOf(capabilities);
    }

    String rootUri() {
        return root.toUri().toString();
    }

    /** Sends a notification (no id, no reply expected). */
    private void notify(String method, Object params) throws IOException {
        write(LspJson.write(Map.of("jsonrpc", "2.0", "method", method, "params", params)));
    }

    /** Sends a request and returns the whole response object, including {@code error}. */
    Map<String, Object> request(String method, Object params) throws IOException, InterruptedException {
        int id = nextId++;
        write(LspJson.write(Map.of(
                "jsonrpc", "2.0", "id", id, "method", method, "params", params)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READ_TIMEOUT_SECONDS);
        while (true) {
            if (!process.isAlive()) {
                throw new IOException("jdtls exited (" + process.exitValue() + ") before answering " + method);
            }
            if (System.nanoTime() > deadline) {
                throw new IOException("jdtls did not answer " + method + " within "
                        + READ_TIMEOUT_SECONDS + "s");
            }
            String payload = readFrame(deadline);
            String ours = LspResponse.findById(payload, id);
            if (ours != null) {
                return LspJson.parseObject(ours);
            }
            // A notification, or a request jdtls is making of us. Neither is our
            // reply, so keep reading rather than answering the wrong question.
        }
    }

    /** Issues workspace/symbol and returns the raw result list, possibly empty. */
    List<?> workspaceSymbols(String query) throws IOException, InterruptedException {
        Map<String, Object> response = request("workspace/symbol", Map.of("query", query));
        Object result = response.get("result");
        return result instanceof List<?> list ? list : new ArrayList<>();
    }

    private void write(String message) throws IOException {
        byte[] body = LspFrame.encode(message).getBytes(StandardCharsets.UTF_8);
        out.write(body);
        out.flush();
    }

    /** Reads one Content-Length framed message, or returns null if the deadline passed. */
    private String readFrame(long deadlineNanos) throws IOException, InterruptedException {
        Integer length = null;
        String line;
        // Scan the header block for Content-Length. jdtls can emit a bare CRLF or
        // another header first, so keep scanning instead of treating the first
        // blank line as the end of the frame.
        while ((line = in.readLine()) != null) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                length = Integer.parseInt(line.substring(15).trim());
                break;
            }
        }
        if (length == null) {
            throw new IOException(line == null
                    ? "jdtls closed the stream before sending a Content-Length header"
                    : "LSP frame had no Content-Length header");
        }
        // Consume exactly the blank line that ends this header block. Reading one
        // line too many here would eat the start of the next frame's header, which
        // shows up as a mangled "ntent-Length:" on the following read.
        String separator = in.readLine();
        if (separator != null && !separator.isEmpty()) {
            throw new IOException("expected a blank line after Content-Length, got: " + separator);
        }
        char[] buf = new char[length];
        int read = 0;
        while (read < length) {
            if (System.nanoTime() > deadlineNanos) {
                throw new IOException("jdtls stalled mid-body after " + read + " of " + length + " bytes");
            }
            int n = in.read(buf, read, length - read);
            if (n < 0) {
                throw new IOException("jdtls stream truncated after " + read + " of " + length + " bytes");
            }
            read += n;
        }
        return new String(buf);
    }

    @Override
    public void close() {
        if (closed) {
            return; // idempotent: try-with-resources plus a tool-layer close is normal
        }
        closed = true;
        // Politely ask first, so jdtls releases its index lock on the workspace.
        // A SIGKILL would leave the lock behind and the next run would fail to
        // start against the same directory.
        try {
            notify("shutdown", Map.of());
            notify("exit", Map.of());
            out.flush();
            // Give it a moment to exit on its own before signalling.
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (IOException e) {
            // Already gone; the destroy below is the backstop.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Destroy only if it is still alive. Nothing is left blocked on the pipe
        // at this point: every read in this class happens on the caller's thread,
        // which has returned by the time close() runs.
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        // Closing the stream releases the reader; without this a leaked server can
        // outlive the JVM on a machine that reaps lazily.
        try {
            in.close();
            out.close();
        } catch (IOException e) {
            // Nothing useful to do while tearing down.
        }
    }

    /** Exposed for the tool layer's diagnostics; never contains payloads. */
    Map<String, Object> describe() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("root", rootUri());
        d.put("capabilities", capabilities.keySet());
        return d;
    }
}