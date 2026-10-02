"""Assemble the Rahu HTML documentation pages from the shared shell.

Each page is a top-down read: the reader meets the whole system before any part of
it, then narrows. Sections never depend on a script to be readable — the JS only
adds progressive enhancement (isolation, filtering, scroll-spy, copy buttons).

Style follows Google and Microsoft developer-writing conventions, which agree on
the points that matter here: short sentences, active voice, one idea per paragraph,
a summary first, no second person, and headings that stand alone as labels.
"""

from __future__ import annotations

import pathlib

HERE = pathlib.Path(__file__).parent
SHELL = (HERE / "_shell.html").read_text(encoding="utf-8")


def page(*, title: str, short: str, desc: str, nav: str, hero: str, body: str) -> str:
    return (SHELL
            .replace("{title}", title)
            .replace("{short}", short)
            .replace("{desc}", desc)
            .replace("{nav}", nav)
            .replace("{hero}", hero)
            .replace("{body}", body))


def nav(*items: tuple[str, str, str]) -> str:
    """items: (href, label, key) — key marks the active entry."""
    out = []
    for href, label, key in items:
        cur = ' aria-current="true"' if key else ""
        out.append(f'<a href="{href}"{cur}>{label}</a>')
    return "\n      ".join(out)


def figure(src: str, caption: str, sub: str = "") -> str:
    sub_html = f"<strong>{sub}</strong>" if sub else ""
    return f'''<figure>
      <div class="scroll">
        <img src="{src}" alt="{caption}" style="max-width:100%">
      </div>
      <div class="legend">
        {sub_html}
        <span style="flex:1 1 100%">{caption}</span>
      </div>
    </figure>'''


# ===========================================================================
# Page 1 — Architecture (top-down)
# ===========================================================================
P1_NAV = nav(
    ("index.html", "Overview", "overview"),
    ("architecture.html", "Architecture", "arch"),
    ("decisions.html", "Decision records", "adr"),
    ("evidence.html", "Audit evidence", "ev"),
)

P1_HERO = '''<div class="hero"><div class="wrap">
  <h1>How Rahu is put together</h1>
  <p class="lede">Rahu reads a repository and explains it. A small decision service
  proposes which model to use and which files matter; ordinary Java decides what
  actually happens. This page explains the whole system first and the details
  second, so you can stop reading as soon as you have what you need.</p>
  <div class="kpis">
    <div class="kpi"><span class="n">4</span><span class="l">modules</span></div>
    <div class="kpi"><span class="n">306</span><span class="l">tests green</span></div>
    <div class="kpi"><span class="n">0</span><span class="l">deps in core</span></div>
    <div class="kpi"><span class="n">15k</span><span class="l">lines of Java</span></div>
    <div class="kpi"><span class="n">3</span><span class="l">direct dependencies</span></div>
  </div>
</div></div>'''

P1_BODY = f'''
<section id="start">
  <h2 id="the-short-version"><span class="num">01</span>The short version</h2>
  <p>Rahu has two halves that talk to each other in one direction only.</p>
  <p>The <strong>decision plane</strong> is a small, bounded classifier. It answers
  narrow questions with narrow answers: which model, which reasoning effort, which
  files are worth reading, whether to compact the conversation. It never writes
  prose and never touches a file.</p>
  <p>The <strong>execution plane</strong> does the work. It runs the loop, admits
  tools, spends money, and decides what happens. It accepts advice from the decision
  plane, and it can always refuse.</p>

  <div class="note good">
    <span class="lbl">The central design claim</span>
    <p>A model can suggest. It cannot grant. Authority lives in ordinary Java that
    you can read, run and test without a network.</p>
  </div>

  <p>That direction is not a convention you have to trust. It is structural. The
  core module has no JSON library and no HTTP client at all, so it has no way to
  speak to a provider even if it wanted to. Enforcement lives where you can test
  it, not in prompt text.</p>
</section>

<section id="layers">
  <h2 id="the-five-layers"><span class="num">02</span>Five layers</h2>
  <p>The system is five layers. Select a layer to see what it owns and what it
  refuses to do.</p>

  <div class="stack" data-layer>
    <button class="layer" data-detail="<strong>Surface.</strong> Commands and configuration parsing. <code>rahu-cli</code>. It reads strict JSON, validates every key against a closed set, and wires the other modules together. It owns no domain logic, so a bug in argument parsing cannot corrupt a budget or a privacy decision. Answer text goes to standard output; diagnostics go to standard error, so piping the answer stays safe."><span class="t">1 · Surface</span><span class="d">Commands, configuration, composition, persistence</span></button>
    <button class="layer" data-detail="<strong>Adapters.</strong> <code>rahu-openrouter</code> and <code>rahu-systemone</code>. Each speaks one provider's wire format and translates it into core types. This is the only place JSON parsing and HTTP live. Keeping them here means a provider change touches one module, and it means core can be tested with no fakes of a network protocol."><span class="t">2 · Adapters</span><span class="d">OpenRouter generation, System One decisions — JSON and HTTP live only here</span></button>
    <button class="layer" data-detail="<strong>Authority.</strong> <code>rahu-core/authority</code>, <code>privacy</code>, <code>runtime</code>. Admission, budget reservation, the privacy gate, the run state machine. These classes decide what may happen. They take no instructions from a model and consult no prompt. Every rule here is a testable method with a name that states the rule."><span class="t">3 · Authority</span><span class="d">Admission, budget, privacy gate, run state machine — never model-advised</span></button>
    <button class="layer" data-detail="<strong>Domain.</strong> <code>rahu-core/routing</code>, <code>context</code>, <code>decision</code>. Candidate construction, route resolution, context assembly, compaction planning, typed decision outcomes. This layer turns a model suggestion into either a legal action or a typed refusal. It is where &quot;that answer is not acceptable&quot; becomes a value rather than an exception."><span class="t">4 · Domain</span><span class="d">Candidates, routes, context, typed decision outcomes</span></button>
    <button class="layer" data-detail="<strong>Capabilities.</strong> <code>rahu-core/tools</code>. Read-only workspace access with path boundaries, schema validation and typed results. A proposed call is a proposal until this layer admits it. The tools cannot write, cannot execute and cannot leave the workspace root, and a denial is a value the model can read rather than a stack trace."><span class="t">5 · Capabilities</span><span class="d">Read-only workspace tools with deterministic admission</span></button>
    <div class="detail"></div>
  </div>

  <div class="note">
    <span class="lbl">Why five layers and not seven modules</span>
    <p>Seven harness areas are a coverage checklist, not an org chart. Splitting a
    15,000-line project into seven Maven modules would add build friction without
    adding a boundary worth defending. Authority, capability and surface are the
    boundaries that can actually fail.</p>
  </div>
</section>

<section id="modules">
  <h2 id="modules-and-dependencies"><span class="num">03</span>Modules and dependencies</h2>
  <p>Dependencies point downward. Nothing depends on the thing above it.</p>
  {figure("01-modules.svg", "Rahu depends on its adapters; the adapters depend on core; core depends on nothing but the JDK. The green line is verified, not asserted: no import of a JSON library or an HTTP client exists anywhere in rahu-core.", "Module graph")}
  <p>This is checked, not claimed. A grep for <code>jackson</code>,
  <code>com.fasterxml</code>, <code>java.net.http</code> and <code>HttpClient</code>
  across <code>rahu-core/src/main/java</code> returns nothing.</p>

  <div class="tablewrap">
  <table>
    <thead><tr><th>Module</th><th>Owns</th><th>Depends on</th><th>Why the boundary holds</th></tr></thead>
    <tbody>
      <tr><td><code>rahu-core</code></td><td>Domain types, state transitions, candidate filtering, budgets, tool contracts, events</td><td>The JDK only</td><td>No provider SDK, so no provider can change a core decision by changing a request</td></tr>
      <tr><td><code>rahu-openrouter</code></td><td>Catalog parsing, capability evidence, generation HTTP mapping, continuation envelope</td><td>core, one JSON library</td><td>Wire formats die here, so a new provider is a new module rather than a new core branch</td></tr>
      <tr><td><code>rahu-systemone</code></td><td>Typed decision protocol, compatibility profiles, response validation</td><td>core, one JSON library</td><td>A malformed decision becomes a typed failure, never a repaired guess</td></tr>
      <tr><td><code>rahu-cli</code></td><td>Configuration, composition, commands, persistence, replay</td><td>core and both adapters</td><td>Composition is explicit, so a run's wiring is visible by reading the constructor</td></tr>
    </tbody>
  </table>
  </div>

  <div class="note warn">
    <span class="lbl">Supply chain</span>
    <p>Three direct dependencies in total: Jackson, picocli and JUnit. All are
    current releases, all are pinned to an exact version through a build property,
    and none uses a version range. A range is an unpinned install that can change
    what you build without your repository changing.</p>
  </div>
</section>

<section id="planes">
  <h2 id="two-planes"><span class="num">04</span>Two planes</h2>
  <p>The decision plane proposes. The execution plane disposes.</p>
  {figure("02-planes.svg", "The decision plane returns bounded typed answers. It cannot widen the model pool, add a tool, lift a privacy block, or add budget. Those four denials are enforced in Java, so they hold even if the model asks politely.", "Decision and execution planes")}
  <p>Four limits are worth stating plainly, because each one was once a real
  defect rather than a hypothetical:</p>
  <ul>
    <li><strong>The pool cannot widen.</strong> A fallback may choose among
    configured models only. It cannot introduce a model the operator did not list.</li>
    <li><strong>A decision cannot add a tool.</strong> Tool relevance is advice.
    Admission is deterministic.</li>
    <li><strong>A decision cannot unblock privacy.</strong> Explicit input
    classification cannot override a known protected-data finding.</li>
    <li><strong>A decision cannot add budget.</strong> Money comes from the ledger,
    and the ledger is not model-advised.</li>
  </ul>
</section>

<section id="candidates">
  <h2 id="execution-candidates"><span class="num">05</span>Execution candidates</h2>
  <p>The distinctive unit of this design is the <strong>execution candidate</strong>:
  one legal tuple of model, reasoning policy and provider constraints.</p>
  <p>Code builds the list of candidates that are actually possible. The decision
  service chooses one from that list. Choosing independently would allow an
  impossible pair, such as a reasoning level the model does not support, which the
  provider would reject at a cost the budget already committed.</p>

  <div class="seg" data-target="cand">
    <button data-panel="cand-ok" aria-pressed="true">Legal pair</button>
    <button data-panel="cand-bad" aria-pressed="false">Rejected before dispatch</button>
  </div>
  <div id="cand-ok">
    <div class="note good">
      <span class="lbl">Accepted</span>
      <p>The model supports low and high effort, and mandatory reasoning. The
      candidate set offers default, disabled reasoning, low and high. The decision
      service picks <code>high</code>. The tuple is legal, so the request succeeds
      and the budget reserved for it matches what is spent.</p>
    </div>
  </div>
  <div id="cand-bad" class="hidden">
    <div class="note bad">
      <span class="lbl">Refused</span>
      <p>The decision service names a model that is not in the configured pool, or
      a reasoning level the model does not support. Java rejects the answer as
      inadmissible, falls back to a configured feasible route, and makes no request
      for the rejected candidate. No money is spent on an impossible pair.</p>
    </div>
  </div>

  <p>Default reasoning, disabled reasoning, and an explicit supported effort are
  three distinct values. Collapsing them loses the distinction between &quot;the
  provider will choose&quot; and &quot;we asked for nothing&quot;.</p>
</section>

<section id="loop">
  <h2 id="the-bounded-loop"><span class="num">06</span>The bounded loop</h2>
  <p>A run moves through named phases. The transition table is data, not control
  flow, so an illegal move is an error rather than a surprise.</p>
  {figure("03-run-state-machine.svg", "The eight phases of a run and the legal moves between them. Every phase may also move straight to TERMINAL, which is what bounds the loop. COMPACTING returns to DECIDING, so running out of context is recoverable rather than fatal.", "Run state machine")}
  <p>Four limits apply at once: model steps, wall-clock deadline, token budget and
  cost. Whichever binds first ends the run. A separate detector notices when a turn
  stops making progress, so the loop cannot spin quietly on an unproductive
  exchange.</p>
</section>

<section id="request">
  <h2 id="one-request"><span class="num">07</span>One request end to end</h2>
  <p>The whole path of a single turn, with every stop condition beside it.</p>
  {figure("04-request-flow.svg", "Ten steps from configuration to settlement. The two pink steps are privacy enforcement. A block at step 3 costs zero requests and zero reservation; a block at step 10 stops the send and releases only the reservation that was definitely unused.", "Request flow")}
</section>

<section id="ledger">
  <h2 id="money"><span class="num">08</span>Money</h2>
  <p>The cost gate reserves before dispatch rather than checking afterwards. A
  post-hoc check can overspend, because the request has already been paid for by
  the time you discover it was too expensive.</p>
  {figure("05-ledger.svg", "Three ledger buckets constrain admission together: money already spent, money held before dispatch, and money whose outcome is ambiguous. One reservation is held once and referenced by each level, so settled cost rolls up exactly once.", "Cost ledger")}
  <p>Two rules keep this honest, and both were learned from defects rather than
  designed in advance:</p>
  <ul>
    <li><strong>All three buckets count.</strong> An earlier version compared only
    reserved and uncertain money. Once a reservation settled it stopped counting,
    so a fully spent allowance measured as empty again.</li>
    <li><strong>A child overrun reaches the parent.</strong> A run that bills above
    its estimate must stop new paid work in the session that pays for it, not only
    in the run that overspent.</li>
  </ul>
  <p>A conversation reset clears content, never money. The method
  <code>resetConversation</code> is deliberately a no-op for the ledger, and a test
  asserts that settled and uncertain totals survive it.</p>
</section>

<section id="privacy">
  <h2 id="privacy"><span class="num">09</span>Privacy</h2>
  <p>The privacy boundary is enforced twice, in Java, with no model involved in
  either decision.</p>
  <div class="grid">
    <div class="card"><div class="swatch" style="background:var(--rose)"></div>
      <h4>Before admission</h4>
      <p>Content is classified locally and checked against the approved sources.
      A blocked request makes zero model calls and reserves zero money.</p></div>
    <div class="card"><div class="swatch" style="background:var(--rose)"></div>
      <h4>Before transport</h4>
      <p>The exact serialised body is re-checked against the safe view. This
      catches anything an earlier step failed to cover.</p></div>
    <div class="card"><div class="swatch" style="background:var(--amber)"></div>
      <h4>Fallback re-passes</h4>
      <p>A fallback candidate is checked independently for the same content. A
      different model is not a reason to lower the bar.</p></div>
    <div class="card"><div class="swatch" style="background:var(--blue)"></div>
      <h4>Credentials</h4>
      <p>An API key is the narrow exception: it travels only in the
      authentication field of the service it was configured for, never in a prompt,
      a URL or a log.</p></div>
  </div>
  <div class="note bad">
    <span class="lbl">Honest limit</span>
    <p>Pattern detection cannot find everything. Uncertainty blocks rather than
    passes, and this project does not claim universal detection. A local endpoint
    or an approved-source label is not permission to send protected content.</p>
  </div>
</section>

<section id="next">
  <h2 id="where-next"><span class="num">10</span>Where to go next</h2>
  <div class="grid">
    <div class="card"><h4>Decision records</h4>
      <p>Why the loop is owned, why a decision plane exists, why candidates are
      joint, and what was deliberately deferred.</p>
      <p><a href="decisions.html">Read the eight ADRs</a></p></div>
    <div class="card"><h4>Audit evidence</h4>
      <p>Three adversarial review rounds, including the claims that turned out to
      be stale, and the defects they found.</p>
      <p><a href="evidence.html">See what was found and fixed</a></p></div>
    <div class="card"><h4>Repository docs</h4>
      <p>Fifteen specification files, one per subsystem, each with acceptance
      scenarios mapped to named tests.</p>
      <p><a href="../README.md">Back to the index</a></p></div>
  </div>
</section>
'''

(HERE / "architecture.html").write_text(
    page(title="Architecture", short="Architecture",
         desc="Top-down architecture of Rahu: two planes, five layers, four modules, "
              "the bounded run loop and the cost ledger. Self-contained HTML with "
              "inline SVG diagrams and no network requests.",
         nav=P1_NAV, hero=P1_HERO, body=P1_BODY),
    encoding="utf-8")
print("architecture.html written")