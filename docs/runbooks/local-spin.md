# Local spin — verify the harness works

Five minutes, no release. Every command below was run on this machine; the arrows are
actual observed output, not expectations.

## Prerequisites

- JDK 27 (the default JDK on this machine is older):
  `export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`
- For the live steps only: `OPENROUTER_API_KEY` set in the environment **or** in the
  gitignored `rahu/.env`. Nothing else is needed — no local decision service.

## 0. Build

```bash
cd ~/work/ai/github/java-based-agentic-harness/rahu
./mvnw verify
```

Expected: `BUILD SUCCESS`, 136 tests, 0 failures.

## 1. Offline path (no credential, no cost)

```bash
./bin/rahu demo
./bin/rahu config validate --config examples/offline.json
./bin/rahu eval --suite docs/evals/suites/smoke-v1.json --config examples/offline.json
```

Expected:
- `demo` → stdout `Rahu offline demo: routing kernel composed; no network calls were made.`,
  a trace id on stderr, exit 0.
- `config validate` → `config valid: mode=offline, routing=shadow`, exit 0.
- `eval` → `eval complete: 6/6 tasks succeeded (offline)`, exit 0, report `mode:"offline"`,
  per-task cost `"unavailable (offline)"`.

## 2. Live config sanity (no cost)

```bash
./bin/rahu config validate --config config.local.json
```

Expected: `config valid: mode=live, routing=shadow`, exit 0. This makes **no** network call.

## 3. Live chat — one real turn (cost: a few millionths of a dollar)

```bash
printf 'Reply with exactly: SPIN_OK\n/status\n/exit\n' \
  | ./bin/rahu chat --config config.local.json --input-classification approved-nonsensitive
```

Expected (observed):
```
rahu chat (live) — model mistralai/mistral-nemo, decision typesafe/jev-1.13, shadow routing
decision (shadow, typesafe/jev-1.13): granite@default (confidence 0.93)
SPIN_OK
model=mistralai/mistral-nemo · tokens in=75 out=4 cost=$0.000003 · 456 ms · ledger=0.000003 USD
```
Exit 0.

What this proves in one shot: the decision plane really called Jev (a routed answer came
back), generation really called OpenRouter (`SPIN_OK` is the model's output, not a fixture),
the billed cost came back from the provider, and the ledger settled it exactly.

## 4. Session memory + ledger accumulation (two turns)

```bash
printf 'Remember the word PLUM. Reply with exactly: ONE\nWhat word did I ask you to remember? Reply with just the word.\n/status\n/exit\n' \
  | ./bin/rahu chat --config config.local.json --input-classification approved-nonsensitive
```

Expected (observed): `ONE`, then `PLUM`, then `/status` showing
`turns=2, settled=0.000006 USD, uncertain=0, remaining=0.499994`.
`PLUM` proves history carried across turns; the settled total is the exact sum of the two
billed calls.

## 5. The privacy gate fails closed

```bash
printf 'hello there\n' | ./bin/rahu chat --config config.local.json
```

Expected (observed): `privacy blocked (unknown-provenance); nothing was sent.`, **exit 4**.
No request leaves the process without an explicit input classification. This is the control
that keeps names, emails and identifiers off external services.

## 6. The eval runner refuses to fake a live run

```bash
./bin/rahu eval --suite docs/evals/suites/smoke-v1.json --config config.local.json
```

Expected (observed): a message that the config is live and needs an explicit budget,
**exit 3**. An offline run never labels itself live.

## Working-as-expected checklist

- [ ] `./mvnw verify` → BUILD SUCCESS, 136 tests
- [ ] `demo` → exit 0, trace id printed
- [ ] `config validate` offline and live → exit 0
- [ ] `eval` offline → 6/6, report `mode:"offline"`
- [ ] live chat → Jev decision printed, model answer returned, cost + ledger settled
- [ ] two-turn live chat → recalled `PLUM`, ledger = sum of both calls
- [ ] live chat without classification → exit 4, nothing sent
- [ ] `eval` with a live config → exit 3

## Known limits (not yet working)

- Routing is **shadow**: the Jev decision is recorded and printed, but the baseline model
  (mistral-nemo) always executes. Active routing is S12b.
- Live turns write no trace file, so replay covers offline runs only.
- Live chat does not run compaction yet.
- The decision call's own cost (~$0.00002) is not metered into the ledger; only generation cost is.
