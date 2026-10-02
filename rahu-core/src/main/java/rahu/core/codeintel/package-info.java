/**
 * A dependency-free LSP client for the Java language server.
 *
 * <p>{@code jdtls} has no CLI: running it starts a server that waits for an
 * {@code initialize} request, so {@code jdtls --version} hangs by design and every
 * capability costs a round trip over a pipe. This package supplies the client, so
 * the agent can ask real questions about the code instead of re-deriving them from
 * greps and compile errors.
 *
 * <p>WHICH SERVER: {@link LspSession#resolveBinary()} resolves the {@code RAHU_JDTLS}
 * override, then the pinned snapshot under the profile's {@code lsp/} dir, then
 * {@code PATH}. It never selects the Homebrew build, which is roughly seven weeks
 * older on the LTK refactoring and batch-compiler plugins and would run an older
 * compiler against this Java 27 project.
 *
 * <p>SAFETY: every read is bounded by {@link LspSession#READ_TIMEOUT_SECONDS} so a
 * server that starts and never answers surfaces as an error instead of stalling an
 * agent turn. {@code start()} throws when the server reports no capabilities rather
 * than returning a session that silently answers nothing. Nothing here calls
 * {@code workspace/applyEdit}: refactors must show their diff before anything is
 * applied.
 *
 * <p>SCOPE: objects, arrays, strings, numbers, booleans and null — enough to speak
 * LSP, not a general JSON library. {@code rahu-core} is JDK-only by architecture.
 *
 * <p>See {@code docs/reviews/018-jdtls-adoption.md} for the capability evidence and
 * the cost measurement that must pass before this is wired into the agent loop.
 */
package rahu.core.codeintel;

