#!/usr/bin/env python3
"""Generate the Rahu architecture diagrams as standalone inline SVG.

Why hand-authored SVG instead of Mermaid/D3/Graphviz:

  * The charter requires a fully offline, default-off verification path with no
    live services and no network. A diagram that fetches a JS library from a CDN
    silently breaks that guarantee the first time someone opens the file offline.
  * These are architecture diagrams with a fixed, reviewed shape. They change when
    an ADR changes, not per-render. Authoring them directly keeps a human in the
    loop, which is the property that matters for a document meant to be trusted.
  * No external request means the docs carry no third-party script, no CDN
    dependency, and no supply-chain surface. That was an explicit operator
    requirement for this deliverable.

Run:  python3 docs/architecture-html/make_diagrams.py
Output: SVG files next to this script, referenced by the HTML pages.
"""

from __future__ import annotations

import html
import pathlib

OUT = pathlib.Path(__file__).parent

# Palette mirrors the CSS custom properties. SVG rendered standalone (opened
# directly, or embedded in Markdown) cannot read the stylesheet's variables, so
# these are duplicated deliberately. Keep the two in sync.
DARK = {
    "bg": "#0d1117", "raised": "#161b22", "sunken": "#0a0e13", "inset": "#1c2330",
    "ink": "#e6edf3", "muted": "#9aa7b4", "faint": "#6b7885",
    "rule": "#2a3340", "bright": "#3d4855",
    "blue": "#5aa2ff", "teal": "#3fb9a5", "amber": "#e3a008",
    "rose": "#f2666e", "violet": "#a78bfa", "slate": "#8892a0",
}


def esc(text: object) -> str:
    return html.escape(str(text), quote=True)


def head(w: int, h: int, title: str) -> list[str]:
    return [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" '
        f'width="{w}" height="{h}" role="img" aria-labelledby="t d">',
        f"<title id=\"t\">{esc(title)}</title>",
        f"<desc id=\"d\">Architecture diagram for {esc(title)}. "
        "Colours carry meaning: blue is structure, teal is verified evidence, "
        "amber is degraded or risky, violet is an external boundary.</desc>",
        "<defs>",
        '  <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" '
        'markerWidth="7" markerHeight="7" orient="auto-start-reverse">',
        f'    <path d="M0,0 L10,5 L0,10 z" fill="{DARK["blue"]}"/>',
        "  </marker>",
        '  <marker id="arrowV" viewBox="0 0 10 10" refX="9" refY="5" '
        'markerWidth="7" markerHeight="7" orient="auto-start-reverse">',
        f'    <path d="M0,0 L10,5 L0,10 z" fill="{DARK["violet"]}"/>',
        "  </marker>",
        '  <marker id="arrowA" viewBox="0 0 10 10" refX="9" refY="5" '
        'markerWidth="7" markerHeight="7" orient="auto-start-reverse">',
        f'    <path d="M0,0 L10,5 L0,10 z" fill="{DARK["amber"]}"/>',
        "  </marker>",
        "</defs>",
        f'<rect width="{w}" height="{h}" fill="{DARK["bg"]}" rx="12"/>',
    ]


def box(x, y, w, h, title, sub, accent, dashed=False):
    c = DARK
    dash = ' stroke-dasharray="5 4"' if dashed else ""
    out = [
        f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8" '
        f'fill="{c["raised"]}" stroke="{accent}" stroke-width="1.6"{dash}/>',
        f'<rect x="{x}" y="{y}" width="3.5" height="{h}" rx="1.75" fill="{accent}"/>',
    ]
    if sub:
        out.append(
            f'<text x="{x + 16}" y="{y + 25}" class="d-label" '
            f'style="font:600 13px -apple-system,Segoe UI,Roboto,sans-serif">'
            f"{esc(title)}</text>"
        )
        for i, line in enumerate(sub.split("\n")):
            out.append(
                f'<text x="{x + 16}" y="{y + 43 + i * 14}" '
                f'style="font:400 10.5px ui-monospace,Menlo,monospace" '
                f'fill="{c["muted"]}">{esc(line)}</text>'
            )
    else:
        out.append(
            f'<text x="{x + 16}" y="{y + h / 2 + 4.5}" '
            f'style="font:600 13px -apple-system,Segoe UI,Roboto,sans-serif" '
            f'fill="{c["ink"]}">{esc(title)}</text>'
        )
    return out


def edge(path, kind="flow", label=None, lx=0, ly=0):
    c = DARK
    style = {
        "flow": f'stroke="{c["blue"]}" stroke-width="2" marker-end="url(#arrow)"',
        "data": f'stroke="{c["violet"]}" stroke-width="1.8" '
                f'stroke-dasharray="6 4" marker-end="url(#arrowV)"',
        "risk": f'stroke="{c["amber"]}" stroke-width="1.8" '
                f'stroke-dasharray="3 3" marker-end="url(#arrowA)"',
        "plain": f'stroke="{c["bright"]}" stroke-width="1.6"',
    }[kind]
    out = [f'<path d="{path}" fill="none" {style} stroke-linecap="round"/>']
    if label:
        out.append(
            f'<text x="{lx}" y="{ly}" text-anchor="middle" '
            f'style="font:600 10px ui-monospace,Menlo,monospace" '
            f'fill="{c["faint"]}">{esc(label)}</text>'
        )
    return out


def wrap(name: str, body: list[str], w: int, h: int, title: str) -> str:
    svg = head(w, h, title) + body + ["</svg>", ""]
    path = OUT / name
    path.write_text("\n".join(svg), encoding="utf-8")
    return name


# --------------------------------------------------------------------------
# 1. Module and dependency direction
# --------------------------------------------------------------------------
def modules() -> str:
    c = DARK
    b: list[str] = [
        f'<text x="24" y="30" style="font:700 15px -apple-system,Segoe UI,sans-serif" '
        f'fill="{c["ink"]}">Modules and dependency direction</text>',
        f'<text x="24" y="49" style="font:400 11.5px ui-monospace,Menlo,monospace" '
        f'fill="{c["faint"]}">arrows point at the module being depended ON. '
        f"rahu-core has no dependency beyond the JDK.</text>",
    ]
    # CLI on top, then adapters, then core at the bottom: dependencies flow down.
    b += box(300, 70, 340, 62, "rahu-cli", "composition, commands, persistence", c["blue"])
    b += box(90, 190, 340, 76, "rahu-openrouter",
             "catalog, generation HTTP,\ncontinuation envelope", c["violet"])
    b += box(510, 190, 340, 76, "rahu-systemone",
             "decision HTTP protocol,\nresponse validation", c["violet"])
    b += box(300, 330, 340, 88, "rahu-core",
             "domain types, authority, ledgers,\nrouting, tools, privacy — JDK only",
             c["teal"])
    b += box(300, 470, 340, 58, "JDK", "no JSON library, no HTTP client", c["slate"])

    b += edge("M420,132 L300,190", "flow")
    b += edge("M520,132 L680,190", "flow")
    b += edge("M260,266 L380,330", "flow")
    b += edge("M680,266 L560,330", "flow")
    b += edge("M470,418 L470,470", "plain")
    b.append(
        f'<text x="24" y="440" style="font:600 10.5px ui-monospace,Menlo,monospace" '
        f'fill="{c["teal"]}">VERIFIED: no jackson / java.net.http import exists in '
        f"rahu-core/src/main/java</text>"
    )
    b += legend_items([
        (c["blue"], "composition"),
        (c["violet"], "adapters: JSON + HTTP live here"),
        (c["teal"], "deterministic authority"),
        (c["slate"], "platform"),
    ], y=490)
    return wrap("01-modules.svg", b, 940, 530,
                "Rahu modules and dependency direction")


def legend_items(items, y):
    x = 24
    out = []
    for colour, label in items:
        out.append(f'<rect x="{x}" y="{y - 9}" width="10" height="10" rx="3" '
                   f'fill="{colour}"/>')
        out.append(f'<text x="{x + 16}" y="{y}" style="font:400 11px '
                   f'-apple-system,Segoe UI,sans-serif" fill="{DARK["muted"]}">'
                   f"{esc(label)}</text>")
        x += 22 + len(label) * 6.1
    return out


# --------------------------------------------------------------------------
# 2. The two planes: decision vs execution
# --------------------------------------------------------------------------
def planes() -> str:
    c = DARK
    b: list[str] = [
        f'<text x="24" y="30" style="font:700 15px -apple-system,Segoe UI,sans-serif" '
        f'fill="{c["ink"]}">Two planes, one direction of trust</text>',
        f'<text x="24" y="49" style="font:400 11.5px ui-monospace,Menlo,monospace" '
        f'fill="{c["faint"]}">The decision plane advises. The execution plane '
        f"obeys. Advice can never widen authority.</text>",
        f'<rect x="24" y="66" width="440" height="330" rx="12" fill="none" '
        f'stroke="{c["blue"]}" stroke-width="1.5" stroke-dasharray="6 5"/>',
        f'<text x="40" y="90" style="font:700 11px ui-monospace,Menlo,monospace" '
        f'fill="{c["blue"]}">EXECUTION PLANE — owns authority</text>',
        f'<rect x="500" y="66" width="420" height="330" rx="12" fill="none" '
        f'stroke="{c["violet"]}" stroke-width="1.5" stroke-dasharray="6 5"/>',
        f'<text x="516" y="90" style="font:700 11px ui-monospace,Menlo,monospace" '
        f'fill="{c["violet"]}">DECISION PLANE — bounded advice</text>',
    ]
    b += box(48, 108, 392, 60, "Agent runtime", "RunStateMachine, bounded loop", c["blue"])
    b += box(48, 186, 392, 72, "Deterministic authority",
             "admission, budget, privacy gate,\ntool permission — plain Java", c["teal"])
    b += box(48, 276, 392, 60, "Tools", "read-only workspace access", c["teal"])
    b += box(48, 354, 392, 34, "Generation provider", None, c["slate"])

    b += box(524, 108, 372, 60, "System One decision port",
             "classify, route, tool relevance,\ncompaction policy", c["violet"])
    b += box(524, 196, 372, 58, "Bounded typed answers",
             "choice, score, noul — no prose", c["violet"])
    b += box(524, 282, 372, 106, "Cannot grant",
             "cannot widen the model pool\ncannot add a tool\ncannot unblock privacy\n"
             "cannot add budget", c["rose"])

    b += edge("M440,140 L524,140", "flow", "request", 482, 132)
    b += edge("M524,176 L440,176", "flow", "advice", 482, 168)
    b += edge("M244,168 L244,186", "plain")
    b += edge("M244,258 L244,276", "plain")
    b += edge("M296,336 L296,354", "plain")

    b += edge("M916,160 L916,220", "plain")
    b.append(
        f'<text x="24" y="425" style="font:400 11.5px -apple-system,Segoe UI,sans-serif" '
        f'fill="{c["muted"]}">The trust arrow points one way: the decision plane '
        f"proposes, Java disposes.</text>"
    )
    b.append(
        f'<text x="24" y="446" style="font:400 11.5px -apple-system,Segoe UI,sans-serif" '
        f'fill="{c["muted"]}">Every bound below is enforced in ordinary testable '
        f'Java, not in prompt text.</text>'
    )
    b += legend_items([
        (c["blue"], "runtime"), (c["teal"], "deterministic"),
        (c["violet"], "decision port"), (c["rose"], "hard boundary"),
    ], y=486)
    return wrap("02-planes.svg", b, 944, 520,
                "The Rahu decision plane and execution plane")


# --------------------------------------------------------------------------
# 3. The bounded run loop as a state machine
# --------------------------------------------------------------------------
def state_machine() -> str:
    c = DARK
    b: list[str] = [
        f'<text x="24" y="30" style="font:700 15px -apple-system,Segoe UI,sans-serif" '
        f'fill="{c["ink"]}">RunPhase — the bounded loop</text>',
        f'<text x="24" y="49" style="font:400 11.5px ui-monospace,Menlo,monospace" '
        f'fill="{c["faint"]}">verbatim from RunStateMachine. LEGAL. A terminal phase '
        f'permits no successor.</text>',
    ]
    phases = {
        "CREATED": (24, 90, c["slate"]),
        "DECIDING": (208, 90, c["violet"]),
        "ADMITTED": (392, 90, c["blue"]),
        "GENERATING": (576, 90, c["blue"]),
        "VALIDATING_TOOLS": (760, 90, c["teal"]),
        "EXECUTING_TOOLS": (760, 186, c["teal"]),
        "COMPACTING": (576, 186, c["amber"]),
        "TERMINAL": (392, 186, c["rose"]),
    }
    w, h = 168, 52
    for name, (x, y, colour) in phases.items():
        b += box(x, y, w, h, name, None, colour,
                 dashed=(name == "TERMINAL"))
    b += edge(f"M192,116 L208,116", "flow")
    b += edge(f"M376,116 L392,116", "flow")
    b += edge(f"M560,116 L576,116", "flow")
    b += edge(f"M744,116 L760,116", "flow")
    b += edge(f"M844,142 L844,186", "flow")
    b += edge(f"M760,212 L560,212", "flow")
    b += edge(f"M576,238 L534,238", "flow", "terminal is always legal", 484, 232)
    b += edge(f"M192,116 L192,268 L392,268 L392,238", "plain", "", 0, 0)
    b += edge(f"M296,116 L296,300 L660,300 L660,238", "plain")
    b += edge(f"M660,300 L760,300 L760,238", "plain")

    b.append(
        f'<text x="24" y="360" style="font:600 11px ui-monospace,Menlo,monospace" '
        f'fill="{c["amber"]}">Every phase may also transition straight to TERMINAL. '
        f"That is what bounds the loop.</text>"
    )
    b.append(
        f'<text x="24" y="381" style="font:400 11.5px -apple-system,Segoe UI,sans-serif" '
        f'fill="{c["muted"]}">The loop is bounded by model-step, time, token and cost '
        f"admission — and by the phase table itself.</text>"
    )
    b.append(
        f'<text x="24" y="402" style="font:400 11.5px -apple-system,Segoe UI,sans-serif" '
        f'fill="{c["muted"]}">COMPACTING returns to DECIDING, so a context-size '
        f"failure is recoverable rather than terminal.</text>"
    )
    b += legend_items([
        (c["slate"], "start"), (c["violet"], "decision"),
        (c["blue"], "generation"), (c["teal"], "tools"),
        (c["amber"], "context pressure"), (c["rose"], "terminal"),
    ], y=440)
    return wrap("03-run-state-machine.svg", b, 952, 470,
                "RunPhase state machine of the bounded run loop")


# --------------------------------------------------------------------------
# 4. Top-down: how one request flows
# --------------------------------------------------------------------------
def request_flow() -> str:
    c = DARK
    steps = [
        ("1  Configure", "strict JSON, validated against a closed key set", c["slate"]),
        ("2  Plan context", "provenance, trust and privacy class per item", c["blue"]),
        ("3  Privacy gate", "pre-admission. Blocked here costs zero requests", c["rose"]),
        ("4  Decide", "System One picks a task class and a route", c["violet"]),
        ("5  Candidates", "code builds only feasible model x reasoning pairs", c["blue"]),
        ("6  Reserve", "pre-dispatch reservation against the ledger", c["amber"]),
        ("7  Generate", "one request; provider continuation preserved", c["blue"]),
        ("8  Authorise tools", "every proposed call re-checked before it runs", c["teal"]),
        ("9  Execute serially", "read-only workspace tools, bounded results", c["teal"]),
        ("10  Re-check + settle", "final serialised body re-gated, then settle", c["rose"]),
    ]
    b: list[str] = [
        f'<text x="24" y="30" style="font:700 15px -apple-system,Segoe UI,sans-serif" '
        f'fill="{c["ink"]}">One request, end to end</text>',
        f'<text x="24" y="49" style="font:400 11.5px ui-monospace,Menlo,monospace" '
        f'fill="{c["faint"]}">Read top to bottom. Pink steps are privacy '
        f"enforcement; the run stops there, before any paid work.</text>",
    ]
    y = 74
    for i, (title, sub, colour) in enumerate(steps):
        b += box(24, y, 560, 44, title, sub, colour)
        if i < len(steps) - 1:
            b += edge(f"M304,{y + 44} L304,{y + 56}", "flow")
        y += 56
    # side rail: what can stop it
    b.append(
        f'<rect x="616" y="74" width="316" height="330" rx="10" fill="none" '
        f'stroke="{c["rose"]}" stroke-width="1.4" stroke-dasharray="6 5"/>'
    )
    b.append(
        f'<text x="634" y="98" style="font:700 11px ui-monospace,Menlo,monospace" '
        f'fill="{c["rose"]}">STOP CONDITIONS</text>'
    )
    stops = [
        "privacy finding -> zero requests",
        "cost admission denied -> zero dispatch",
        "no feasible candidate -> terminate",
        "decision malformed -> typed failure",
        "decision suggests infeasible -> fallback",
        "no progress -> bounded terminal",
        "step / time / token limit -> terminal",
    ]
    yy = 122
    for s in stops:
        b.append(
            f'<text x="634" y="{yy}" style="font:400 11.5px '
            f'-apple-system,Segoe UI,sans-serif" fill="{c["muted"]}">'
            f"• {esc(s)}</text>"
        )
        yy += 26
    b.append(
        f'<text x="634" y="{yy + 10}" style="font:600 10.5px ui-monospace,Menlo,monospace" '
        f'fill="{c["amber"]}">Each stop is a typed outcome,</text>'
    )
    b.append(
        f'<text x="634" y="{yy + 26}" style="font:600 10.5px ui-monospace,Menlo,monospace" '
        f'fill="{c["amber"]}">never an exception or a guess.</text>'
    )
    return wrap("04-request-flow.svg", b, 952, 440,
                "End-to-end flow of a single Rahu request")


# --------------------------------------------------------------------------
# 5. The ledger's three buckets
# --------------------------------------------------------------------------
def ledger_model() -> str:
    c = DARK
    b: list[str] = [
        f'<text x="24" y="30" style="font:700 15px -apple-system,Segoe UI,sans-serif" '
        f'fill="{c["ink"]}">The ledger counts three things, not one</text>',
        f'<text x="24" y="49" style="font:400 11.5px ui-monospace,Menlo,monospace" '
        f'fill="{c["faint"]}">settled + max(reserved, uncertain) + requested must fit '
        f"the allowance. Omitting any term is a bug.</text>",
    ]
    buckets = [
        ("SETTLED", "money already spent", c["rose"], "counts against the allowance"),
        ("RESERVED", "held before dispatch", c["amber"], "counts against the allowance"),
        ("UNCERTAIN", "ambiguous: may or may not have been billed",
         c["violet"], "counts in full, until resolved"),
    ]
    x = 24
    for name, sub, colour, note in buckets:
        b.append(f'<rect x="{x}" y="74" width="288" height="104" rx="10" '
                 f'fill="{DARK["raised"]}" stroke="{colour}" stroke-width="1.6"/>')
        b.append(f'<text x="{x + 18}" y="104" style="font:700 13px '
                 f'-apple-system,Segoe UI,sans-serif" fill="{colour}">{name}</text>')
        for i, line in enumerate(sub.split("\n")):
            b.append(f'<text x="{x + 18}" y="{126 + i * 15}" style="font:400 11px '
                     f'-apple-system,Segoe UI,sans-serif" fill="{DARK["muted"]}">'
                     f"{esc(line)}</text>")
        b.append(f'<text x="{x + 18}" y="164" style="font:600 10px '
                 f'ui-monospace,Menlo,monospace" fill="{colour}">{esc(note)}</text>')
        x += 312
    b.append(
        f'<text x="24" y="214" style="font:700 12px ui-monospace,Menlo,monospace" '
        f'fill="{c["ink"]}">ALLOWANCE</text>'
    )
    b += box(24, 226, 900, 50, "session.maxCostUsd",
             "one allowance covers the run; a new turn, a compaction or a "
             "conversation reset cannot reset it", c["teal"])
    b += edge("M168,226 L168,190", "flow")
    b += edge("M480,226 L480,190", "flow")
    b += edge("M792,226 L792,190", "flow")

    b.append(
        f'<rect x="24" y="300" width="900" height="112" rx="10" fill="none" '
        f'stroke="{c["blue"]}" stroke-width="1.4" stroke-dasharray="6 5"/>'
    )
    b.append(f'<text x="42" y="324" style="font:700 11px ui-monospace,Menlo,monospace" '
             f'fill="{c["blue"]}">THREE ROLLS UP, ONE MONEY</text>')
    b.append(f'<text x="42" y="350" style="font:400 11.5px '
             f'-apple-system,Segoe UI,sans-serif" fill="{DARK["muted"]}">'
             f"A run ledger reserves against its session ledger, which reserves "
             f"against the experiment ledger.</text>")
    b.append(f'<text x="42" y="370" style="font:400 11.5px '
             f'-apple-system,Segoe UI,sans-serif" fill="{DARK["muted"]}">'
             f"Each level holds the SAME reservation, so settled cost rolls up "
             f"once and is never summed twice.</text>")
    b.append(f'<text x="42" y="392" style="font:600 11px ui-monospace,Menlo,monospace" '
             f'fill="{c["rose"]}">A child overrun trips overshoot at the parent too, '
             f"or one run's excess is invisible to whoever pays.</text>")
    return wrap("05-ledger.svg", b, 952, 440,
                "Rahu cost ledger: settled, reserved and uncertain")


def main() -> None:
    made = [
        modules(),
        planes(),
        state_machine(),
        request_flow(),
        ledger_model(),
    ]
    for name in made:
        size = (OUT / name).stat().st_size
        print(f"{name:32s} {size:6d} bytes")


if __name__ == "__main__":
    main()