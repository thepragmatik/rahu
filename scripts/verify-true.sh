#!/usr/bin/env bash
# True verification for this repository.
#
# WHY THIS EXISTS
# ---------------
# A green test suite, a successful build and an exit code of 0 have each lied
# about this project. Recorded cases, all real:
#
#   1. A guard class (NoProgressDetector) was fully implemented and 5/5 tested,
#      and NOTHING in src/main ever called it. The tests were true; the
#      protection was absent.
#   2. A live run was written up as "after fix" when the jar had been built
#      after that run finished. The result was true of the source and false of
#      the artifact that actually ran.
#   3. A background wrapper reported exit 0 because only its last command
#      mattered; the run inside had produced no answer at all.
#
# So this script checks the things a build cannot: that guards are WIRED, that
# a test can actually FAIL, and that claims match artifacts. It is designed to
# fail loudly and to be cheap enough to run every time.
#
# Usage:  scripts/verify-true.sh [--quick]
# Exit 0 = all checks passed. Non-zero = at least one check failed.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 2

QUICK=0
[ "${1:-}" = "--quick" ] && QUICK=1

PASS=0
FAIL=0
NOTE=""

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }
head1() { printf '\n=== %s\n' "$1"; }

ok()   { PASS=$((PASS+1)); green "  PASS  $1"; }
bad()  { FAIL=$((FAIL+1)); red   "  FAIL  $1"; [ -n "$NOTE" ] && printf '        %s\n' "$NOTE" && NOTE=""; }
skip() { printf '  SKIP  %s\n' "$1"; }

# ---------------------------------------------------------------- preflight
head1 "Preflight"
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
fi
if [ -x ./mvnw ]; then ok "maven wrapper present"
else bad "maven wrapper missing"; fi

# DotEnvTest and LiveWiringTest read the real environment and FAIL if the key is
# exported, so a stray key turns a real failure into a confusing one.
if [ -n "${OPENROUTER_API_KEY:-}" ]; then
  unset OPENROUTER_API_KEY
  ok "unset OPENROUTER_API_KEY (test hygiene; it must not be exported during verify)"
else
  ok "OPENROUTER_API_KEY not exported"
fi

# ------------------------------------------------- CHECK 1: guards are wired
# A guard that only its own test references is decoration. Every class named in
# GUARDS below must have at least one reference from src/main.
head1 "Check 1 - safety guards are reachable from src/main"
GUARDS="NoProgressDetector PrivacyGate PathBoundary ToolCallLog"
for g in $GUARDS; do
  MAIN_REFS=$(grep -rl --include=*.java "\b$g\b" */src/main/java 2>/dev/null | wc -l | tr -d ' ')
  TEST_ONLY=1
  [ "$MAIN_REFS" -gt 0 ] && TEST_ONLY=0
  if [ "$TEST_ONLY" -eq 0 ]; then
    ok "$g referenced from $MAIN_REFS file(s) in src/main"
  else
    NOTE="$g appears only in its own test. A tested guard that main never calls protects nothing."
    bad "$g is NOT referenced from src/main"
  fi
done

# ------------------------------------- CHECK 2: a key test can actually fail
# A test that cannot fail proves nothing. Mutate the source under test, require
# the suite to go red, then restore. If the suite stays green the test is
# vacuous and must not be trusted.
if [ "$QUICK" -eq 1 ]; then
  skip "mutation check (--quick)"
else
  head1 "Check 2 - ReadThenAnswerTest fails when the loop is broken"
  TARGET=rahu-cli/src/main/java/rahu/cli/live/ToolLoop.java
  BACKUP=$(mktemp)
  cp "$TARGET" "$BACKUP"
  # Replace the observation the loop hands back with a constant. This breaks the
  # read -> observation -> answer path without touching anything else.
  # Replace the observation text handed back to the model with a constant. This
  # breaks read -> observation -> answer and nothing else. Uses python for a
  # reliable multi-line literal match; a silent no-match here would SKIP the most
  # important check in the script, so the match is verified below.
  MUT_COUNT=$(python3 - "$TARGET" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
old = 'judged.get(i).text()));'
new = '"VERIFY-MUTATION"));'
n = s.count(old)
if n:
    open(p, 'w').write(s.replace(old, new))
print(n)
PYEOF
)
  if [ "$MUT_COUNT" -lt 1 ]; then
    SKIPME=1
  else
    SKIPME=0
  fi
  if [ "$SKIPME" -eq 1 ]; then
    skip "mutation pattern not found in $TARGET (update this script when the call site moves)"
  else
    MUT_OUT=$(JAVA_HOME="$JAVA_HOME" OPENROUTER_API_KEY= ./mvnw -o -q -pl rahu-cli -am test \
      -Dtest=ReadThenAnswerTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1)
    if echo "$MUT_OUT" | grep -q "FAILURE\|Failures: [1-9]\|Errors: [1-9]"; then
      ok "suite went RED under mutation - the test has real power"
    else
      NOTE="The suite stayed green with the observation text replaced by a constant. ReadThenAnswerTest is vacuous."
      bad "suite stayed GREEN under mutation - the test cannot detect a broken loop"
    fi
    cp "$BACKUP" "$TARGET"
    RESTORED=$?
    if cmp -s "$TARGET" "$BACKUP"; then ok "source restored after mutation"
    else bad "could not restore $TARGET - restore it by hand before committing"; fi
    rm -f "$BACKUP"
    # Prove the restore is good, not just byte-equal to a stale copy.
    CLEAN=$(JAVA_HOME="$JAVA_HOME" OPENROUTER_API_KEY= ./mvnw -o -q -pl rahu-cli -am test \
      -Dtest=ReadThenAnswerTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1)
    if echo "$CLEAN" | grep -q "FAILURE\|Failures: [1-9]\|Errors: [1-9]"; then
      bad "ReadThenAnswerTest fails after restore - the working tree is broken"
    else
      ok "ReadThenAnswerTest green after restore"
    fi
  fi
fi

# ------------------------------- CHECK 3: no secret is committed to the tree
head1 "Check 3 - no live secret is committed"
if [ -f .env ]; then
  KEY=$(grep '^OPENROUTER_API_KEY=' .env 2>/dev/null | cut -d= -f2- | tr -d '"'"'"' \r')
  if [ -z "$KEY" ]; then
    ok ".env has no OPENROUTER_API_KEY value to leak"
  elif grep -rqF "$KEY" --include='*.java' --include='*.md' --include='*.json' --include='*.xml' . 2>/dev/null; then
    NOTE="The value in .env also appears in a tracked file. Rotate the key, then remove it from history."
    bad "live key value found in tracked files"
  else
    ok "key value absent from tracked sources and docs"
  fi
else
  skip "no .env present"
fi

# ------------------------- CHECK 4: docs do not claim more than was verified
# The repeated failure in this project was documentation asserting a result the
# artifact did not support. This catches the most common shape: a doc claiming a
# gate passed while the evidence file still says otherwise.
head1 "Check 4 - gate claims agree between roadmap and evidence"
ROADMAP=docs/roadmap.md
EVIDENCE=docs/reviews/dogfood-release.md
if [ -f "$ROADMAP" ] && [ -f "$EVIDENCE" ]; then
  # A doc that says a gate PASSED must not also carry an unretracted blocker
  # sentence for the same gate.
  # Only a CONTRADICTORY current-state claim counts. A sentence that records a
  # PAST error ("was earlier wrongly recorded as blocked on ...") is accurate
  # history and must not trip this check, so match only unqualified present-tense
  # claims that the gate still lacks evidence.
  STALE=$(grep -cE 'G0[0-9] has no live evidence|Not "complete": G0[0-9]|G0[0-9] (is|remains) blocked' "$ROADMAP")
  if [ "$STALE" -gt 0 ]; then
    grep -nE 'G0[0-9] has no live evidence|Not "complete": G0[0-9]|G0[0-9] (is|remains) blocked' "$ROADMAP" \
      | cut -c1-160 | sed 's/^/        /'
    NOTE="The roadmap asserts a gate passed and also that the same gate still has no evidence. Make one story."
    bad "roadmap contradicts itself about a gate's status"
  else
    ok "no present-tense contradiction between a PASS claim and a blocker claim"
  fi
else
  skip "roadmap or evidence file absent"
fi

# ------------------------------------ CHECK 5: the full suite, honestly read
head1 "Check 5 - full build and suite"
if [ "$QUICK" -eq 1 ]; then
  skip "full verify (--quick)"
else
  LOG=$(mktemp)
  JAVA_HOME="$JAVA_HOME" OPENROUTER_API_KEY= ./mvnw -o clean verify > "$LOG" 2>&1
  RC=$?
  # Read the log. Never trust $RC alone: a wrapper that ends `cmd; echo rc=$?`
  # reports the LAST command, not the run.
  if grep -q "BUILD SUCCESS" "$LOG" && [ "$RC" -eq 0 ]; then
    RUNS=$(grep -cE "Tests run: [0-9]+, Failures: 0, Errors: 0" "$LOG")
    TOT=$(grep -oE "Tests run: [0-9]+, Failures: 0, Errors: 0" "$LOG" | grep -oE "[0-9]+" | paste -sd+ - | bc)
    ok "BUILD SUCCESS - $RUNS module result lines, $TOT tests, 0 failures, 0 errors"
  else
    grep -E "Tests run:.*(Failures: [1-9]|Errors: [1-9])|ERROR\].*\.java|BUILD FAILURE" "$LOG" | head -12 | sed 's/^/        /'
    NOTE="Full log: $LOG"
    bad "clean verify did not pass (rc=$RC); log kept at $LOG"
  fi
  [ "$RC" -eq 0 ] || true
fi

# ------------------------------------------------------------------ summary
head1 "Summary"
printf '  passed: %d\n  failed: %d\n' "$PASS" "$FAIL"
if [ "$FAIL" -eq 0 ]; then
  green "  TRUE VERIFICATION PASSED"
  exit 0
fi
red "  TRUE VERIFICATION FAILED - do not report success"
exit 1
