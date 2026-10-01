# Build status

Last updated: 2026-10-01T17:05+10:00 · HEAD: S02 · Next slice: S03

| Slice | Status | Evidence (commands + results) | Review | Commit(s) |
|---|---|---|---|---|
| S01 | done | `./mvnw verify` BUILD SUCCESS (4 modules); core tests 4/4; `./bin/rahu demo` exit 0 (stdout answer, stderr footer, JSONL trace); javap major version 71 (Java 27 preview agreement) | docs/reviews/004-s01-build-review.md | 264c670 |
| S02 | done | core tests 17/17 (candidate exclusions A04/A05, routing table A03/A06/A07, 200-trial invariant sweep); `./mvnw verify` BUILD SUCCESS | docs/reviews/005-s02-routing-review.md | 036ba39 |

## Open blockers

- G09 live dogfood: BLOCKED — OpenRouter API key ABSENT (presence check only, value never read);
  decision service at 127.0.0.1:8000 ABSENT. Offline work unaffected; live prerequisites list
  recorded per docs/runbooks/dogfood.md.

## Environment pins

- JAVA_HOME: /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home — OpenJDK 27 GA,
  build 27, 2026-09-15 (matches ADR 0004 planning baseline). Set per session:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`
- Note: operator requested Zulu 27 via sdk; SDKman install failed 3x (broker URL broken,
  corrupt-archive each time) and manual install was blocked by the operator; build uses
  Homebrew OpenJDK 27 per operator instruction ("Use the brew install and proceed").
- Maven: 3.9.9 (wrapper pinned via -Dmaven=3.9.9), runs on JAVA_HOME above.
- Verified 2026-10-01 from repo1.maven.org metadata: junit-jupiter 6.1.3,
  jackson-databind 2.19.0, picocli 4.7.7, maven-shade-plugin 3.6.2,
  maven-surefire-plugin 3.6.0, maven-compiler-plugin 3.16.0 (latest stable; 4.0.0-beta-5
  rejected as beta).
- Package roots: rahu.core / rahu.openrouter / rahu.systemone / rahu.cli.
- Rollback note: build rollback = `rm -rf ~/work/ai/github/java-based-agentic-harness/rahu`;
  nothing outside this directory is modified by the build.

## Preflight record (T0)

- git identity: set; clone over SSH verified (HEAD = baseline 130588a).
- Live prereqs: OPENROUTER_API_KEY absent; System One decision service absent (curl 127.0.0.1:8000).
- Dependency/version checks: latest stable versions recorded above; exact versions used.
