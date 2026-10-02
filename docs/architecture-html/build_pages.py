"""Build the remaining Rahu HTML pages: index, decision records, and audit evidence.

The audit evidence page exists because of an unusual property of this project: two
of three adversarial review rounds contained findings that were stale on arrival.
Recording the stale claims alongside the real ones is the point. A review process
that only publishes its hits cannot be audited.
"""

from __future__ import annotations

import pathlib

from build_arch import HERE, nav, page, figure, P1_NAV  # reuse the shared shell

# ===========================================================================
# Page 2 — index
# ===========================================================================
P0_NAV = nav(
    ("index.html", "Overview", "overview"),
    ("architecture.html", "Architecture", "arch"),
    ("decisions.html", "Decision records", "adr"),
    ("evidence.html", "Audit evidence", "ev"),
)

P0_HERO = '''<div class="hero"><div class="wrap">
  <h1>Rahu</h1>
  <p class="lede">A read-only assistant that reads a repository and explains it,
  with a small decision service proposing which model to use and which files
  matter, and ordinary Java deciding what actually happens.</p>
  <div class="kpis">
    <div class="kpi"><span class="n">306</span><span class="l">tests green</span></div>
    <div class="kpi"><span class="n">4</span><span class="l">modules</span></div>
    <div class="kpi"><span class="n">15</span><span class="l">specifications</span></div>
    <div class="kpi"><span class="n">8</span><span class="l">decision records</span></div>
    <div class="kpi"><span class="n">3</span><span class="l">direct dependencies</span></div>
  </div>
</div></div>'''

P0_BODY = f'''
<section id="what">
  <h2 id="what-it-does"><span class="num">01</span>What it does</h2>
  <p>Rahu answers questions about a codebase. It lists files, reads them, searches
  literal text, and writes an answer with a trace of how it got there.</p>
  <p>It does not edit your repository, run your code, or write files. That limit is
  the reason it is safe to point at private code.</p>
  <div class="grid">
    <div class="card"><div class="swatch" style="background:var(--blue)"></div>
      <h4>Reads</h4><p>List, read and literal-search inside a workspace root, with
      typed results and a path boundary.</p></div>
    <div class="card"><div class="swatch" style="background:var(--violet)"></div>
      <h4>Decides</h4><p>A bounded decision service picks the model, reasoning
      effort and relevant files. It proposes; Java disposes.</p></div>
    <div class="card"><div class="swatch" style="background:var(--amber)"></div>
      <h4>Accounts</h4><p>Every dispatched request reserves money before it runs and
      settles after. One session allowance covers the whole run.</p></div>
    <div class="card"><div class="swatch" style="background:var(--rose)"></div>
      <h4>Gates</h4><p>Outbound content is classified locally, twice: before
      admission and again before transport.</p></div>
  </div>
</section>

<section id="start">
  <h2 id="start-here"><span class="num">02</span>Start here</h2>
  <div class="tablewrap">
  <table>
    <thead><tr><th>If you want to</th><th>Read</th><th>Run</th></tr></thead>
    <tbody>
      <tr><td>Understand the design</td><td><a href="architecture.html">Architecture</a></td><td>—</td></tr>
      <tr><td>Know why it is built this way</td><td><a href="decisions.html">Decision records</a></td><td>—</td></tr>
      <tr><td>See what review found</td><td><a href="evidence.html">Audit evidence</a></td><td>—</td></tr>
      <tr><td>Build it</td><td><a href="../runbooks/dogfood.md">Setup runbook</a></td><td><code>./mvnw clean verify</code></td></tr>
      <tr><td>Try it with no network</td><td><a href="../product.md">Product spec</a></td><td><code>bin/rahu demo</code></td></tr>
      <tr><td>Understand a requirement</td><td><a href="../specs/">Specifications</a></td><td>—</td></tr>
    </tbody>
  </table>
  </div>
  <div class="note">
    <span class="lbl">Verification needs no keys</span>
    <p>The default verification path runs with no live model and no credentials.
    Two tests read the real environment, so unset
    <code>OPENROUTER_API_KEY</code> before <code>./mvnw verify</code> or they fail by
    design rather than by defect.</p>
  </div>
</section>

<section id="shape">
  <h2 id="the-short-shape"><span class="num">03</span>The shape in one picture</h2>
  {figure("02-planes.svg", "The decision plane advises; the execution plane decides. Every limit shown here is enforced in Java and covered by a named test.", "System shape")}
</section>

<section id="honest">
  <h2 id="what-is-not-finished"><span class="num">04</span>What is not finished</h2>
  <p>This is a dogfood alpha. Some things are built and proven, some are built and
  deliberately switched off, and some need an operator decision.</p>
  <div class="tablewrap">
  <table>
    <thead><tr><th>Area</th><th>State</th><th>Note</th></tr></thead>
    <tbody>
      <tr><td>Offline build and tests</td><td><span class="badge ok">Verified</span></td><td>306 tests green on a clean verify</td></tr>
      <tr><td>Release gates G01–G08</td><td><span class="badge ok">Verified</span></td><td>Individually evidenced in the release manifest</td></tr>
      <tr><td>Injection detection</td><td><span class="badge warn">Shadow only</span></td><td>Scored but never enforced; calibration is unproven</td></tr>
      <tr><td>Search reranking</td><td><span class="badge warn">Default off</span></td><td>Built and tested; needs live measurement before enforcement</td></tr>
      <tr><td>G09 dogfood run</td><td><span class="badge mute">Operator</span></td><td>Needs an aggregate spending ceiling</td></tr>
      <tr><td>Acceptance A25–A32</td><td><span class="badge warn">Partial</span></td><td>Covered by slice reviews rather than a clean pass</td></tr>
    </tbody>
  </table>
  </div>
  <div class="note warn">
    <span class="lbl">Not claimed</span>
    <p>A green test count is not a review. A passing detector does not mean all
    protected data was recognised. &quot;Offline complete&quot; is not declared, and
    shadow routing stays off until a separate promotion gate supports the change.</p>
  </div>
</section>

<section id="principles">
  <h2 id="working-rules"><span class="num">05</span>Working rules</h2>
  <p>Four rules shaped this codebase, and each one exists because its absence
  caused a real defect.</p>
  <ol>
    <li><strong>Test the contract, not the history.</strong> A test that pins a bug
    fails when you fix the bug. Several early tests here did exactly that.</li>
    <li><strong>One rule, one owner.</strong> A security rule written twice is
    enforced once. The loopback rule was duplicated, and the first fix passed its
    tests because the tests covered one copy.</li>
    <li><strong>Verify every claim at the current commit.</strong> Two audit rounds
    reported defects that had already been fixed.</li>
    <li><strong>Nothing external.</strong> No CDN, no remote font, no telemetry.
    These pages render offline and make no network request.</li>
  </ol>
</section>
'''

# ===========================================================================
# Page 3 — decision records
# ===========================================================================
P2_NAV = P1_NAV

P2_HERO = '''<div class="hero"><div class="wrap">
  <h1>Decision records</h1>
  <p class="lede">Eight accepted decisions that explain why the system looks the
  way it does, and what was deliberately left out. Each one records the
  alternatives that were considered, because a decision without a rejected
  alternative is not yet a decision.</p>
</div></div>'''

ADRS = [
    ("0001", "Own the agent loop", "Accepted",
     "General agent frameworks control their own lifecycle, which hides exactly the "
     "routing boundaries, continuation handling and budget decisions this project "
     "exists to test.",
     "A small bounded loop in ordinary Java, with narrow ports for decisions, "
     "generation, tools and observations. Libraries may parse JSON and speak HTTP; "
     "none of them controls the loop.",
     "Loses the ecosystem features a framework would have provided. Accepted: "
     "inspectability is the research variable here."),
    ("0002", "A genuine System One decision plane", "Accepted",
     "Decision models answer bounded questions well and generate prose badly. Model "
     "confidence is neither permission nor evidence of a correct answer.",
     "Typed HTTP decisions for classification, routing, advisory tool relevance and "
     "compaction policy. Generation models write answers, summaries and tool "
     "arguments. Java owns feasibility, validation, budgets and authority.",
     "A real decision service becomes an operational dependency. Accepted: a fake "
     "labelled as the real thing would defeat the research question."),
    ("0003", "Joint execution candidates", "Accepted",
     "Choosing a model and an effort independently can produce combinations the "
     "provider does not support. Omitting a configuration value is not the same as "
     "disabling reasoning.",
     "Java builds the legal tuples from configured pools and capability evidence. "
     "The decision service chooses a stable candidate ID. Provider default, disabled "
     "reasoning and explicit effort are three separate policies.",
     "The candidate set must be built before the decision is asked, which costs a "
     "round of local work per turn. Accepted: an impossible pair costs a paid "
     "rejection."),
    ("0004", "Java 27 with preview features", "Accepted",
     "The owner asked for the latest Java and permits preview features where they "
     "improve a concrete design.",
     "Pin the verified working distribution. Use records, sealed outcomes, pattern "
     "switches, virtual threads and structured concurrency where the semantics fit, "
     "and enable preview consistently in compilation, tests, launcher and packaging.",
     "Preview features can change between releases, so the build is pinned to a "
     "verified release rather than a moving target."),
    ("0005", "Traces and policy replay", "Accepted",
     "Routing decisions and failures must be observable. Event-sourcing the whole "
     "runtime would promise deterministic model regeneration, which cannot be "
     "delivered.",
     "Append schema-versioned JSONL from an ordinary in-memory state machine. "
     "Metadata capture is default; payload capture is explicit. Replay is "
     "deterministic only with frozen inputs and recorded outcomes.",
     "Gives up the ability to reconstruct state from the log alone. Accepted: the "
     "alternative would have produced a false guarantee."),
    ("0006", "Strict configuration and shadow-first routing", "Accepted",
     "Silent model selection creates surprise cost and quality. Provider aliases and "
     "capability data change often, so a baked-in list rots.",
     "Strict JSON against a generated schema, a closed key set, explicit model pools, "
     "and shadow mode as the default: the decision service proposes, and the "
     "configured baseline runs.",
     "Shadow mode doubles decision cost while it is on. Accepted: the alternative is "
     "changing routing with no measurement."),
    ("0007", "Seven subsystems, four modules", "Accepted",
     "A single autonomous build should reach a usable harness. The earlier handoff "
     "allowed stopping at the first slice and left several contracts implicit.",
     "Require explicit coverage of all seven harness areas, with a minimal "
     "implementation or a deliberate absence. Keep four modules, read-only tools, no "
     "dynamic plugins, no cross-process recovery and no sub-agents.",
     "One agent cannot claim orchestration design. Accepted: an honest single-agent "
     "position is worth more than an untested delegation claim."),
    ("0008", "Strict outbound privacy admission", "Accepted",
     "Excluding secrets from logs does not stop disclosure through classification, "
     "summaries, tool observations or adapter fields. Sending raw content to a "
     "privacy classifier has already disclosed it.",
     "Deterministic local classification before admission and again on the exact "
     "serialised body before transport. Unknown or restricted content fails closed. "
     "Explicit classification cannot override a known protected-data finding.",
     "Detection is imperfect, so uncertainty blocks rather than passes. The "
     "release report states this limit rather than claiming universal detection."),
]

rows = []
for num, title, status, context, decision, consequence in ADRS:
    rows.append(f'''
<details>
  <summary><strong style="margin-right:.5rem">{num}</strong>{title}
    <span class="badge {"ok" if status=="Accepted" else "warn"}"
      style="margin-left:auto">{status}</span></summary>
  <div class="body">
    <h4>The problem</h4><p>{context}</p>
    <h4>The decision</h4><p>{decision}</p>
    <h4>What it costs</h4><p>{consequence}</p>
    <p style="margin-top:.8rem"><a href="../adr/{ {'0001':'0001-own-loop','0002':'0002-decision-plane','0003':'0003-execution-candidates','0004':'0004-java-preview','0005':'0005-traces-and-replay','0006':'0006-configuration-and-shadow','0007':'0007-seven-subsystem-alpha','0008':'0008-outbound-privacy'}[num] }.md">Read ADR {num}</a></p>
  </div>
</details>''')

P2_BODY = f'''
<section id="read">
  <h2 id="how-to-read"><span class="num">01</span>How to read these</h2>
  <p>Each record has three parts: the problem, the decision, and what the decision
  costs. The third part is the one that matters most, because a decision with no
  recorded downside has usually not been examined.</p>
  <p>Expand any record below. All eight are accepted.</p>
</section>

<section id="records">
  <h2 id="the-eight-records"><span class="num">02</span>The eight records</h2>
  {"".join(rows)}
</section>

<section id="deferred">
  <h2 id="deliberately-deferred"><span class="num">03</span>Deliberately deferred</h2>
  <p>These are not gaps. Each was considered and deferred for a stated reason, and
  none has a placeholder abstraction waiting for it.</p>
  <div class="grid">
    <div class="card"><h4>Streaming</h4><p>Non-streaming generation is enough for a
    review assistant. Streaming adds partial-output states to every error path.</p></div>
    <div class="card"><h4>Effectful tools</h4><p>Read-only tools mean a prompt
    injection cannot cause damage. That property is worth more than convenience.</p></div>
    <div class="card"><h4>Durable resume</h4><p>Needs a persistent tool journal and
    a recovery protocol, plus a reason to want either.</p></div>
    <div class="card"><h4>Vector memory</h4><p>In-process ordered history is enough
    for follow-up questions in one session.</p></div>
    <div class="card"><h4>Runtime plugins and MCP</h4><p>Needs separate trust and
    permission semantics that the current authority path does not model.</p></div>
    <div class="card"><h4>Multi-agent</h4><p>Needs a strong single-agent baseline
    and shared budget semantics before delegation means anything.</p></div>
  </div>
  <div class="note">
    <span class="lbl">The rule behind the list</span>
    <p>No abstraction is pre-created for a hypothetical future system. An interface
    with one implementation and no substitution boundary is decoration, and it
    hides the cost of the real design.</p>
  </div>
</section>
'''

# ===========================================================================
# Page 4 — audit evidence
# ===========================================================================
P3_NAV = P1_NAV

P3_HERO = '''<div class="hero"><div class="wrap">
  <h1>Audit evidence</h1>
  <p class="lede">Three rounds of adversarial review found seventeen defects,
  eleven of them high severity. This page records the findings, the fixes, and —
  just as importantly — the claims that turned out to be wrong on arrival.</p>
  <div class="kpis">
    <div class="kpi"><span class="n">17</span><span class="l">defects found</span></div>
    <div class="kpi"><span class="n">11</span><span class="l">high severity</span></div>
    <div class="kpi"><span class="n">3</span><span class="l">review rounds</span></div>
    <div class="kpi"><span class="n">4</span><span class="l">stale claims caught</span></div>
  </div>
</div></div>'''

FINDINGS = '''
<tr data-sev="high"><td>F-12</td><td>High</td><td>Settled spend stopped counting against the allowance; an exhausted budget measured as empty</td><td>Fixed</td><td><code>dcfc0e1</code></td></tr>
<tr data-sev="high"><td>F-13</td><td>High</td><td>A child settlement never released the parent's reservation, so the parent ran out early</td><td>Fixed</td><td><code>dcfc0e1</code></td></tr>
<tr data-sev="high"><td>F-6</td><td>High</td><td>Three raw exception types escaped the tool boundary; one echoed a path back to the model</td><td>Fixed</td><td><code>cdef376</code></td></tr>
<tr data-sev="high"><td>F-7</td><td>High</td><td>An empty response body threw a null pointer error out of the batch decision API</td><td>Fixed</td><td><code>5d81b85</code></td></tr>
<tr data-sev="high"><td>F-8</td><td>High</td><td>The loopback rule existed twice; the first fix corrected only the core copy</td><td>Fixed</td><td><code>5d81b85</code></td></tr>
<tr data-sev="high"><td>F-11</td><td>High</td><td><code>confidenceField</code> documented and schema-exposed but never read</td><td>Recorded</td><td>needs a wire-contract change</td></tr>
<tr data-sev="medium"><td>F-9</td><td>Medium</td><td><code>Question</code> was unsealed, so a forgotten type still serialised as an empty object</td><td>Fixed</td><td><code>5d81b85</code></td></tr>
<tr data-sev="medium"><td>F-10</td><td>Medium</td><td>NaN passed both range guards, serialising as a string and rejecting every route</td><td>Fixed</td><td><code>5d81b85</code></td></tr>
<tr data-sev="medium"><td>F-2</td><td>Medium</td><td>Four configuration keys were documented and schema-exposed but had no effect</td><td>Fixed</td><td><code>990647f</code></td></tr>
<tr data-sev="medium"><td>F-4</td><td>Medium</td><td>The rerank test passed vacuously; it asserted nothing that could fail</td><td>Fixed</td><td><code>4067ea0</code></td></tr>
<tr data-sev="low"><td>F-14</td><td>Low</td><td>Transport faults collapsed to one kind, hiding whether work was actually processed</td><td>Recorded</td><td>needs an enum addition</td></tr>
<tr data-sev="low"><td>F-15</td><td>Low</td><td><code>DecisionResult</code> is sealed with no permits clause and no exhaustive switch</td><td>Recorded</td><td>low practical risk</td></tr>
'''

STALE = '''
<tr><td>Loopback prefix check is a live fail-open</td><td>Stale</td><td>Already fixed at <code>4002166</code>; the report read pre-fix code</td></tr>
<tr><td>Rerank test passes vacuously</td><td>Stale</td><td>Already fixed at <code>4067ea0</code>; the reviewer caught itself reading a pre-fix file</td></tr>
<tr><td><code>DecisionResult</code> is sealed</td><td>Misleading</td><td>True, but it has no permits clause and every consumer uses type tests, so nothing is enforced</td></tr>
<tr><td>Search skip marker is missing</td><td>Overstated</td><td>A real gap, but the marker alone was the weaker fix; the flag was correct</td></tr>
'''

P3_BODY = f'''
<section id="method">
  <h2 id="how-the-audit-ran"><span class="num">01</span>How the audit ran</h2>
  <p>Three review rounds ran as independent sub-agents, each with a different
  lens: authority and dependency direction; state, money and failure semantics; and
  API surface and design honesty. They read the project's own charter and tested
  its promises against the code.</p>
  <p>Every claim was then re-derived from the working tree before anything was
  changed.</p>
  <div class="note warn">
    <span class="lbl">The finding that shaped the process</span>
    <p>Two of the three reports contained claims that were already fixed. One
    reported a security bug that had been corrected eleven commits earlier; another
    read a 285-line version of a file that was 316 lines long. Both cited real
    files and real line numbers, and both were confidently wrong.</p>
    <p>A report that names a file and a line can still describe a file that no
    longer exists. Every claim gets checked against the tree before it earns a
    commit.</p>
  </div>
</section>

<section id="findings">
  <h2 id="what-was-found"><span class="num">02</span>What was found</h2>
  <p>Filter by severity. Twelve of the seventeen findings are listed here; the
  remainder are recorded in the repository reviews.</p>
  <div class="seg" data-filter-group="find" role="group" aria-label="Filter findings">
    <button data-filter="all" aria-pressed="true">All</button>
    <button data-filter="high" aria-pressed="false">High</button>
    <button data-filter="medium" aria-pressed="false">Medium</button>
    <button data-filter="low" aria-pressed="false">Low</button>
  </div>
  <p id="find" role="status" aria-live="polite" style="font-size:.85rem;color:var(--ink-faint)"></p>
  <div class="tablewrap" data-filter-group="find">
    <table>
      <thead><tr><th>ID</th><th>Severity</th><th>Finding</th><th>State</th><th>Commit</th></tr></thead>
      <tbody>{FINDINGS}</tbody>
    </table>
  </div>
</section>

<section id="patterns">
  <h2 id="the-recurring-pattern"><span class="num">03</span>The recurring pattern</h2>
  <p>Most of these are one defect wearing different clothes: <strong>a capability
  that is documented, reachable, and not actually enforced.</strong></p>
  <div class="grid">
    <div class="card"><div class="swatch" style="background:var(--amber)"></div>
      <h4>A rule written twice</h4>
      <p>The loopback check lived in core and in an adapter. The first fix corrected
      core and passed its tests, because the tests covered one copy. The security
      property survived by luck.</p></div>
    <div class="card"><div class="swatch" style="background:var(--rose)"></div>
      <h4>Absence read as evidence</h4>
      <p>A search that skipped an unreadable file reported success. The model
      concluded &quot;nothing found&quot; from a scan that could not finish.</p></div>
    <div class="card"><div class="swatch" style="background:var(--violet)"></div>
      <h4>An open type where a closed one belongs</h4>
      <p>An unsealed interface let a documented-but-unimplemented question type
      serialise as <code>{{}}</code> — valid JSON, no answer.</p></div>
    <div class="card"><div class="swatch" style="background:var(--blue)"></div>
      <h4>A comparison that inverts on NaN</h4>
      <p>Every comparison against NaN is false, so a range guard written in
      negative form admitted it. In the router it rejected every decision
      permanently, with no error anywhere.</p></div>
  </div>
</section>

<section id="stale">
  <h2 id="claims-that-were-wrong"><span class="num">04</span>Claims that were wrong</h2>
  <p>These did not survive verification. They are published because a review
  process that shows only its hits cannot itself be reviewed.</p>
  <div class="tablewrap">
  <table>
    <thead><tr><th>Claim</th><th>Verdict</th><th>What was actually true</th></tr></thead>
    <tbody>{STALE}</tbody>
  </table>
  </div>
</section>

<section id="method-notes">
  <h2 id="how-this-project-tests"><span class="num">05</span>How this project tests itself</h2>
  <p>The suite is stronger than average in one specific way, and it is worth
  keeping.</p>
  <div class="note good">
    <span class="lbl">The pattern to preserve</span>
    <p><code>CostGateTest.spentAllowanceRefusesBeforeDispatch</code> asserts that
    the provider was <strong>never called</strong>, rather than that a method
    returned a particular value. It tests the consequence, not the mechanism.</p>
  </div>
  <p>That distinction caught several real bugs here, and it also exposed a
  mistake in this project's own tests: several early cases asserted that an
  exception was thrown. When the underlying bug was fixed, those tests failed —
  because they had pinned the defect rather than the contract. They now assert the
  typed outcome, so they keep passing if the throw is ever removed and fail if a raw
  exception escapes again.</p>
  <p>One negative control in the tool-boundary suite proves a new warning marker
  does not fire on a healthy tree. A marker that always fires is noise, and a model
  learns to ignore noise.</p>
</section>

<section id="honest-state">
  <h2 id="what-is-still-open"><span class="num">06</span>What is still open</h2>
  <div class="tablewrap">
  <table>
    <thead><tr><th>Item</th><th>Why it is open</th></tr></thead>
    <tbody>
      <tr><td><code>confidenceField</code> is not read</td><td>Honouring it changes the wire contract and the acceptance table, so it needs its own review. The default path is correct, so only a non-default setting is affected.</td></tr>
      <tr><td>Transport faults share one kind</td><td>A read timeout and a connection refusal are both reported as a timeout, so a caller cannot tell whether a retry duplicates work. Needs an enum addition.</td></tr>
      <tr><td>Acceptance A25–A32</td><td>Covered by slice reviews rather than a clean requirement-to-test pass.</td></tr>
      <tr><td>Live dogfood run</td><td>Needs an aggregate spending ceiling from the operator.</td></tr>
      <tr><td>Injection enforcement</td><td>Scoring is not calibrated; the lowest-scoring attacks are the subtle ones. Enforcement stays off.</td></tr>
    </tbody>
  </table>
  </div>
</section>
'''

for name, kwargs in [
    ("index.html", dict(title="Overview", short="Overview",
        desc="Rahu: a read-only repository analysis harness with a bounded decision "
             "plane. Self-contained HTML documentation, no network requests.",
        nav=P0_NAV, hero=P0_HERO, body=P0_BODY)),
    ("decisions.html", dict(title="Decision records", short="Decisions",
        desc="The eight accepted architecture decision records for Rahu, with the "
             "alternatives considered and the cost of each decision.",
        nav=P2_NAV, hero=P2_HERO, body=P2_BODY)),
    ("evidence.html", dict(title="Audit evidence", short="Evidence",
        desc="Three rounds of adversarial architecture review on Rahu: defects found, "
             "fixes applied, and the stale claims that were caught.",
        nav=P3_NAV, hero=P3_HERO, body=P3_BODY)),
]:
    (HERE / name).write_text(page(**kwargs), encoding="utf-8")
    print("wrote", name)