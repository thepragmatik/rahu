# Build status

Last updated: 2026-10-02T14:55+10:00 · HEAD: 2dc6fb1 (Part 0 refactor) · Next: Track T (tool unlock) in worktree `../rahu-wt-tools`, per .hermes/plans/2026-10-02_114504 (which supersedes S12b-first ordering)

## Groundwork (2026-10-02, plan 2026-10-02_114504 Part 0)

- [x] Task 0.1 baseline: `./mvnw verify` BUILD SUCCESS, 136 tests (85 core + 9 openrouter + 7 systemone + 35 cli), clean tree @ 3252e17.
- [x] Task 0.2 refactor: `LiveTurnDriver` extracted to `rahu-cli/src/main/java/rahu/cli/live/`; `ChatCommand.runLive` is now adapter wiring + delegation; still 136 tests; live probe `REFACTOR_OK` byte-identical footer, exit 0. Commit 2dc6fb1.
- [x] Task 0.3 worktrees: `../rahu-wt-tools` (feat/tools-unlock), `../rahu-wt-decisions` (feat/decision-ops), both @ 2dc6fb1; `dependency:go-offline` primed first (rc=0).

| Slice | Status | Evidence (commands + results) | Review | Commit(s) |
|---|---|---|---|---|
| S01 | done | `./mvnw verify` BUILD SUCCESS (4 modules); core tests 4/4; `./bin/rahu demo` exit 0 (stdout answer, stderr footer, JSONL trace); javap major version 71 (Java 27 preview agreement) | docs/reviews/004-s01-build-review.md | 264c670 |
| S02 | done | core tests 17/17 (candidate exclusions A04/A05, routing table A03/A06/A07, 200-trial invariant sweep); `./mvnw verify` BUILD SUCCESS | docs/reviews/005-s02-routing-review.md | 036ba39 |
| S03 | done | cli tests 10/10; packaged `config validate`/`route inspect`/exit-2 error paths verified against examples/offline.json; `./mvnw verify` BUILD SUCCESS (27 tests) | docs/reviews/006-s03-config-cli-review.md | ad9ddc0 |
| S04 | done | openrouter tests 7/7 (request mapping, effort/Disabled, tools, ordered calls + continuation capture, usage unknown-not-zero, length-incomplete, 401/429 typed) | (folded into build-status; S04 review in S05 review evidence) | 5865f38 |
| S05 | done | systemone tests 7/7 (envelope shape, probabilities+raw confidence, invalid label rejected, malformed/NaN typed, noul bounds, 500/429, truncated rejected); 38 tests total | docs/reviews/007-s05-systemone-review.md | 1e4c73a |
| S06 | done | 30 new runtime/privacy/authority tests (A12/A13/A24/A25/A27/A33/A34); 76 tests total; verify green | docs/reviews/008-s06-runtime-privacy-review.md | f97d4d9, 8f62289, ce29b13, e7c346b |
| S07 | done | 21 new tool tests (A08/A09/A24 + registry freeze); 92 tests total; verify green; jdtls 1.62.0 snapshot caught 9 write-time defects this slice | docs/reviews/009-s07-tools-review.md | 22a0384, 9bd34d8, 85f9bfb, 1f93741, 5ed0310 |
| S08 | done | 8 new trace/replay tests (A10/A11/A16); 100 tests total; verify green | docs/reviews/010-s08-traces-replay-review.md | df6fbea |
| S09 | done | 17 new context tests (A14/A22/A23/A29/A32) + scripted two-turn chat run; 117 tests total; verify green | docs/reviews/011-s09-context-chat-review.md | 6e22c49, 7a622c2 |
| S10 | done | 9 new eval tests + packaged eval run: smoke-v1 6/6, dogfood 3/3, exit 0, honest offline costs; live refused pending G09 prereqs; 123 tests total | docs/reviews/012-s10-eval-runner-review.md | dae0e30 |
| S11 | done | clean-clone G01 ritual PASS (128 tests, demo/eval 6/6); config.schema.json generated+tested; DotEnv .env key source (env-first); cheap-model pool validated against live catalog; README quickstart | docs/reviews/013-s11-packaging-review.md | 38226b9, dfe1b55, 5ff95cf, ff9add0 |
| S12a | done | live wiring: OpenRouter decisions (Jev) + budgeted live chat; 134 tests; LIVE turn verified (Jev shadow decision + nemo answer, 533ms, $0.000004 settled) | docs/reviews/014-s12a-live-wiring-review.md | 761a0df |

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
