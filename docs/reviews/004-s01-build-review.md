# S01 build and offline composition review

## Scope and hypothesis

Date: 2026-10-01. Change: S01 (Maven Wrapper, four modules, core domain types, offline demo,
packaged launcher). Affected requirements: R01, R06, R13, R22 (A01, A19, A28). Smallest useful
result: a clean `./mvnw verify` plus a packaged offline demo through `bin/rahu`. Hypothesis:
a single multi-module reactor with preview flags agreed across compile/test/package/run
composes without toolchain drift. Falsifiers: preview class files that fail at test or
launcher time; demo requiring network/keys; adapter classes leaking into core.

## Evidence

- `git rev-parse HEAD` = `130588a421ce94d63e2af1bf1069994b65c5a895` (baseline, docs-only).
- `mvn -N wrapper:wrapper -Dmaven=3.9.9`; `./mvnw --version` = Apache Maven 3.9.9 on
  JAVA_HOME `/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home` (OpenJDK 27 GA,
  build 27, 2026-09-15 — matches ADR 0004).
- RED first: `./mvnw -pl rahu-core test` failed to compile MoneyTest on the missing types
  (package Cost does not exist / cannot find symbol) before implementation existed.
- GREEN after implementation: `./mvnw -pl rahu-core test` = Tests run: 4, Failures: 0
  (MoneyTest 3, CoreBoundaryTest 1).
- `./mvnw verify` = BUILD SUCCESS, all four modules, shade produces
  `rahu-cli/target/rahu-cli.jar` (2.78 MB, shaded).
- A19 preview agreement: `javap -v rahu.core.ReasoningPolicy` = `major version: 71`
  (Java 27 class files); the same JDK ran tests (`--enable-preview` in surefire argLine)
  and the packaged launcher (`./bin/rahu` uses `java --enable-preview -jar ...`).
- A01 packaged demo: `./bin/rahu demo` = exit 0; stdout = one deterministic answer line;
  stderr = route/cost footer (`Cost unavailable (offline demo) · 1 generation step`);
  metadata-only JSONL trace written to `.rahu/runs/<uuid>/events.jsonl` with
  schemaVersion/runId/sequence/timestamp/elapsedMs/type envelope (observability.md shape).
  Zero external calls (offline by construction; no network-capable code in the demo path).
- A28: CoreBoundaryTest scans core class bytes for adapter/CLI package references — green.
  Reactor enforces compile-time direction (core has no dependencies).
- Version pins verified 2026-10-01 from repo1.maven.org metadata: junit-jupiter 6.1.3,
  jackson-databind 2.19.0, picocli 4.7.7, shade 3.6.2, surefire 3.6.0, compiler 3.16.0.

## Findings

| Severity | Failure mode and evidence | Correction | Residual risk and owner |
|---|---|---|---|
| Medium | palantir-java-format (via spotless 3.10.3) crashes on JDK 27 javac internals: `NoSuchFieldError: JCTree$JCCompilationUnit.endPositions` on all 8 source files during `spotless:apply` | Formatter dropped from the build; reason documented in parent pom.xml comment and here; manual style conventions apply until a JDK-27-compatible formatter release exists | Format drift between contributors until then; owner: build; revisit per slice |
| Low | shade reports 1 overlapping resource across shaded jars | Standard shade notice; no action needed for alpha (no module-info, no signed jars) | None known |
| Low | shade writes `dependency-reduced-pom.xml` into the repo | Added to .gitignore | None |
| Low | Demo trace content is provisional (4 event types, not the full S08 schema) | Accepted: S08 owns the versioned envelope/golden fixtures; demo uses the documented field names so drift stays small | Envelope fields may evolve in S08; owner: S08 |

## Decision

Proceed. S01 acceptance evidence is real and recorded; no high-severity findings. Toolchain
note: Zulu 27 via SDKman was attempted 3 times and failed on a broken download-broker URL
(corrupt archive each attempt, deterministic); the operator directed use of the Homebrew
OpenJDK 27 build instead — recorded in build-status.md. Next check that resolves remaining
uncertainty: S02 routing property tests exercising the candidate/decision types.
