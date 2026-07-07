# USER_GUIDE.md audit — guide vs glossary vs shipped UI

*2026-07-07 · audited against `docs/GLOSSARY.md` (locked 2026-06-29) and the implemented UI
(`inspecto-ui/src/app`: nav `mock-api/common/navigation/data.ts`, routes `app.routes.ts`, pane
components). Lenses applied: ETL/ELT engineering, BI development, Operations, business analysis.*

**Verdict.** The guide is a well-written **Builder/Ops reference** masquerading as a **product
guide**. Its prose quality, screen-entry discipline (*what it is → what you can do → what to
notice → how it connects*) and Type/Instance hygiene are genuinely good — and it documents the
product as designed, not as shipped. The Business persona's entire surface (KPI & Reports,
Requirements, Reconciliation) is missing from both the guide **and the sidebar**; the guide's
opening claim that "every term is used exactly as defined in GLOSSARY.md" (USER_GUIDE.md:7–9) is
violated three times by the guide itself; and the concept most confusing "as implemented" —
**Report** — is defined two contradictory ways *inside the glossary* and implemented as a third
thing. Fix list at the end, P0→P2.

---

## A. Factual errors — the guide says things that are false (P0)

| # | Guide says | Reality | Evidence |
|---|---|---|---|
| A1 | "**Overview** — The default landing page" (USER_GUIDE.md:96) | `/` redirects **per Lens**: business → `/kpi-reports`, builder → `/pipelines`, ops → `/events`. Overview (`/dashboard`) is nobody's default. A Business user's first screen is a page the guide never mentions. | `app.routes.ts:11–15` (`LENS_HOME`) |
| A2 | "an Alert Rule watches a **measure** against a threshold" — three times: Alerts entry (:123–124), workflow (:391), vocabulary table (:431) | Alert Rules watch an observability **Metric**. **Measure** is the BI aggregation. This is *the exact* Measure/Metric overload the glossary was built to kill — committed inside the doc that claims perfect glossary compliance. | GLOSSARY.md §4 (:148), §8 (:294–299) |
| A3 | "operational **KPI** tiles" on Overview (:97) | KPI is canonically BI: "a single-number **Measure** with a target" (§7). Ops health tiles are Metrics-derived stats, not KPIs. Mirror image of A2. | GLOSSARY.md §7 (:263) |
| A4 | "Some **screens** and controls only appear in the edition or Lens that enables them" (:17–19) | Nav is **never** filtered by Lens — "every lens can reach every route"; a Lens changes only the home route and capability-gated toolbars. Edition gating is real; Lens screen-gating is not. | `app.routes.ts:8–10` (comment), `LensService` capabilities |

A2 deserves emphasis: the guide's own vocabulary table holds the correct Measure row ("*not
Metric — that's observability*") **two rows below** the incorrect Alert Rule row. The rename
program (§13, `feat/rename-bi-metric-to-measure`) did this work in code; the guide un-does it in
prose.

## B. Shipped but invisible — orphan capabilities (P0)

Three fully implemented panes have **no sidebar entry, no guide section, and no inbound links**
(verified: the only ways in are the lens-home redirect or typing the URL):

| # | Pane | What it is | Why it matters |
|---|---|---|---|
| B1 | **KPI & Reports** — `/kpi-reports` | Business-lens **home page**: grid of Studio Dashboards + **scheduled exports** (C6: cron schedule, format, run-now, download-latest) | The flagship *consume-and-deliver* BI surface — scheduled report delivery is a headline capability of any BI studio, and it is documented **nowhere** |
| B2 | **Requirements** — `/requirements` | The Business→Builder intake loop (C1): request a **KPI \| Report \| Reconciliation \| Rule**, lifecycle `submitted → accepted\|rejected → delivered` | *The* business-analyst feature per GLOSSARY §1-A; doc-invisible end to end |
| B3 | **Reconciliation** — `/reconciliation` | Dataset-vs-dataset compare with tolerances producing **Breaks** (C9: auto-close lifecycle, manual resolutions preserved) | The glossary calls this "the core Revenue-Assurance / Financial-Audit workload" (§7:272–279) — the product's vertical differentiator |

Evidence: `data.ts:17–90` (no nav items), `app.routes.ts:85–87` (live routes),
`kpi-reports.component.html`, `requirements.component.html`, `reconciliations.component.html`.

This is a **product IA bug, not just a docs bug** — the fix is nav entries *and* guide sections.
Recommended nav placement: a new **Business** group above Operations (KPI & Reports,
Requirements, Reconciliation), matching the §1-A persona map; Requirements doubles as the
Builder's triage queue.

Lesser orphans: **Space Templates** are implemented (`space-template-gallery.dialog`,
`SpacesService.templates()`, four shipped verticals per §1) but the guide only says "create one"
(:74–78); the **Notification Center** is a full page (`/notification-center`, history + prefs
tabs, C4) that the guide knows only as a Settings drawer.

## C. Concepts that are genuinely confusing as implemented (P1)

**C1 — "Report" means three things; pick one.** The glossary defines Report twice,
contradictorily: §7 "an **operational** report (run health, freshness, SLA — the *KPI & Reports*
page). Kept distinct from analytical Dashboards" (:269) vs §6-B "a Report is a *scheduled
delivery* of rendered output" (:231). The shipped KPI & Reports page implements a **third**
reading: it lists *analytical Studio Dashboards* and schedules exports of them — its empty state
literally says "Build a dashboard in Studio and it will appear here"
(`kpi-reports.component.html:12–13`), the very Dashboard/Report conflation §7 forbids.
**Recommendation:** lock **Report = a scheduled delivery of rendered output** (matches the C6
implementation and §6-B); rewrite §7; fold "run health / freshness / SLA" into Operations
vocabulary (that content actually lives on Overview / Processing Status). Then the page name is
honest: KPIs (once pinnable) + Reports (scheduled deliveries). Product-owner decision required.

**C2 — "dashboard" is still two things at the route layer.** Ops Overview lives at `/dashboard`
(`data.ts:18`, `modules/admin/dashboard/`); BI Dashboards live at `/studio/dashboards`. Labels
dodge the collision; URLs, module names, and anyone reading a link do not. Rename the route to
`/overview` (redirect kept), module dir when convenient.

**C3 — Alert Rules cannot be authored in the product.** The Alerts page renders `FiredAlert[]` +
`AlertRule[]` read-only; arming a rule means **saving a `*_alert.toon` file next to the configs
on disk** — the UI's own toast admits it (`alerts.component.ts:98`). Of the three rule engines,
Expectations and Decision Rules have first-class authoring panes; the one that heads the entire
**Alert → Incident → Case** chain has none, and the guide silently skips where Alert Rules come
from. Either build authoring (an "Alert Rules" tab on Alerts, or a Workbench sibling of
Expectations/Decision Rules — the IA already implies it) or document the file-based workflow
explicitly. Silence is the worst option.

**C4 — Stream is user-visible but never defined.** The Catalog grid shows a **"Stream"** column
(`catalog.component.ts:106`); GLOSSARY §3 defines Stream carefully (data-plane origin; ⛔ "Data
Source"). The guide never defines it — and writes "which **Source stream** feeds which Table"
(:255), mushing Source (acquisition config) into Stream (catalog origin) in one compound noun,
the precise confusion §3 exists to prevent. Fix the sentence, define Stream in §3.3, add a
vocabulary-table row.

**C5 — Matrix exists only in the glossary.** §6-B asserts "'Matrix' **is** the label the Catalog
and Studio show" (:219); zero occurrences anywhere in the UI (grep), and the §13 rename row is
unmarked (not started). The guide rightly omits it — the glossary states an intention as a fact.
Fix the tense or land the label; never teach a word the product never renders.

**C6 — Signal-Ledger theology leaks into user docs.** "The Signal Ledger … Event, Alert and
Notification are views over this one ledger" is R4 internal architecture. Users need the
operational distinction — Events = what happened, Alerts = thresholds crossed, Notifications =
what was sent to you — one sentence of ledger, not a data-model lecture.

**C7 — Lenses have no documented purpose.** The guide explains switching mechanics but never what
each Lens is *for*; combined with B1/B2 the Business persona is structurally absent. Add the
§1-A persona→surface map (Business consumes + raises Requirements; Builder authors Workbench +
Studio; Ops monitors).

**C8 — "pro" / "pro max" table tiers** (:336–338). Consumer-phone tier branding in an enterprise
guide, describing tiers the user can't choose (each screen fixes its tier). Describe capability
in place ("this list includes an SQL editor and filter builder") and drop the tier names from
user-facing prose.

**C9 — "Stage-2 lookups"** (:194). Internal architecture numbering (architecture.md's Stage-1/2)
used once, defined nowhere in the guide.

**C10 — Scheduling has four homes and no story.** Sources carry schedules (:206), Jobs carry
`cron | event | manual` triggers (:174), Pipelines are "run now" in the guide while GLOSSARY §5
adds `on-pipeline` chaining, and scheduled exports (B1) are a fourth timer. "Where do I set when
things run?" currently has four partial answers. Add a **Scheduling & Triggers** box that names
all four in one table.

**C11 — Parser format lists disagree.** Guide (:162–163): nine formats (ASN.1, DSV, HTML, JSON,
Parquet, plain text, XLSX, XML, other). GLOSSARY §5 (:174): "(CSV, fixed-width, XML, JSON, EDI,
ASN.1, …)" — EDI and fixed-width appear only in the glossary. Align both to the shipped parser
list.

## D. Expert-lens findings

### ETL / ELT engineer
- **The execution model is never stated.** GLOSSARY §5's banner — Inspecto is **ELT**, load is a
  plain Parquet write, real transforms happen in-lakehouse producing Derived Tables — appears
  nowhere in the guide, whose Pipeline prose (parser → transform → sink in a DAG) reads as
  classic in-flight ETL. A data engineer cannot tell where Transform executes or when Derived
  Tables materialize. Add a one-paragraph **data lifecycle** with a diagram: Connection → Source
  (collect) → Batch/Files → Pipeline (parse/shape) → **Table** (partitioned Parquet) →
  Transform/cube → **Derived Table** → Query → Widget → Dashboard → export.
- **Quarantine is a dead end.** Quarantine counts appear on Overview, Processing Status, Runs and
  in Decision Rule consequences — and the guide never says how to *inspect, remediate, or
  reprocess* quarantined records, nor how replaying interacts with Source dedup policy (:207).
  Document the remediation path or file the product gap; an ops team will hit this in week one.
- Credit: **Run ⊇ Batch ⊇ File** with per-level status is explained cleanly (:166–171).

### BI developer (the "dashboard development studio" ambition)
- The authoring chain **Query → Result Set (dimension/measure/temporal) → Widget → Dashboard** is
  conceptually strong — Show-Me-style role matching is a real differentiator — but the guide
  never says **where roles get assigned** (query editor? dataset binding?), never names the
  **Widget Builder** pane (GLOSSARY §1-A does), and never documents **KPI authoring** at all: KPI
  is a first-class glossary concept with tile sizes (mini→standard→max) and there is no
  documented way to pin a number with a target. For a BI studio, "how do I make a KPI" is
  question #1.
- **No semantic-layer story.** Can a **Measure** be defined once and reused across Widgets
  (the code has `NamedMeasure`), or is every Widget self-contained? If reuse exists, document it;
  if not, say so honestly. A BI studio without a measure-reuse layer degenerates into
  copy-per-widget SQL — the thing Query/Result Set was designed to prevent.
- **Delivery is undocumented.** Scheduled exports (formats, run-now, download-latest) ship today
  (B1) and appear nowhere. Sharing = "Dashboards are saved per Space" (:230) — the entire
  distribution model in eight words. Refresh/caching semantics: unstated ("reflects the state at
  load time" exists only for ops Overview).

### Operations
- C3 (Alert-Rule authoring) is the dominant finding — the monitoring chain's entry point is
  UI-invisible.
- The three overlapping health screens (Overview / Processing Status / Runs) are individually
  well described; under incident pressure users need a **"which screen answers which question"**
  matrix, not three prose entries.
- Retention: Config documents *audit* retention only; Events/Signal retention is unstated.
- Incident assignment/ownership is absent from the guide — fine if not built (auth-free core),
  but say so; "add notes" (:130) implies more than it delivers.
- Credit: the **Events vs Audit** separation (:113–116) is drawn exactly right, and live-tail /
  saved filters / CSV export are documented.

### Business analyst
- The Business persona is **structurally absent**: their home page (B1), intake loop (B2), and
  reconciliation workload (B3) are all missing, and all five §7 workflows are Builder/Ops
  workflows. The BA loop — *consume a dashboard → question a number → trace provenance → raise a
  Requirement → track delivery* — exists in the product and appears nowhere in the guide.
- Provenance (P2′) is documented only as a Builder Catalog tool; the business framing ("where did
  this number come from?") is never made.
- Recommend opening the guide with three **"Start here"** persona paths (Business / Builder /
  Ops), each: your home page, your five screens, your one workflow.

## E. Prioritized fix list

**P0 — correctness (this week, ~1 day): ✅ DONE 2026-07-07.**
1. ✅ Fixed A1–A4 (A1 landing-page claim, A2 measure→Metric in all three places, A3
   "operational KPI"→"operational health", A4 Lens screen-gating claim in the editions note).
2. ✅ Added the **Business** nav group (KPI & Reports / Requirements / Reconciliation) above
   Operations in `data.ts`; verified in the preview (group renders, all three routes load with
   correct `<h1>`s, `lint:tokens` + `test:ci` green — 1061 passed, 0 failures).
3. ✅ Added guide **§2 Business** (three screen entries) + a "Consume, schedule, and request"
   workflow; Space Templates now called out in the Spaces entry.
4. ✅ Fixed the "Source stream" sentence, defined **Stream** in the Catalog entry, and added
   **Stream / Requirement / Reconciliation-Break / Metric / KPI** rows to the vocabulary table.
   *(Report row correction deferred with C1 — its definition is still contested; see P1.)*

**P1 — concept repairs (needs product-owner sign-off where noted):**
5. ✅ **Report** definition resolved (PO decision 2026-07-07): Report = a **scheduled delivery of
   rendered output**; GLOSSARY §7 rewritten, guide + vocab table updated.
6. ✅ **Alert-Rule authoring built** (PO decision 2026-07-07): Alerts pane gained an Alert Rules
   authoring section (create/edit/delete via schema-form dialog, `canAuthorAlertRules` gate,
   mock-served CRUD; plan: `superpower/alert-rule-authoring-plan.md`; backend write endpoints →
   `superpower/backend-backlog.md` §4).
7. ✅ Data lifecycle (ELT) diagram + Scheduling & Triggers box added to the guide.
8. Document scheduled exports (✅ in the KPI & Reports entry), KPI authoring path, and the
   Measure-reuse answer (D-BI) — **the latter two still open**.
9. ✅ Persona→surface "Start here" paths + the ops screen-selection matrix added.
10. Document quarantine remediation/replay or file the product gap (D-ETL) — **open**.

**P2 — hygiene:**
11. De-jargon: ✅ Signal-Ledger prose trimmed (C6); "pro/pro max" (C8) and "Stage-2" (C9) —
    **still open**.
12. Align parser format lists (C11); fix GLOSSARY §6-B Matrix tense (C5) — **open**.
13. ✅ Route renamed `/dashboard` → `/overview` with redirect (C2); nav + space-switcher updated.
14. ✅ **Doc-lint in CI**: `tools/check-vocabulary.mjs` scans the user-facing docs for ⛔ terms
    (Flow, Data Store, Collector-as-noun) and the `measure…threshold` confusion pattern (A2);
    wired into `ci.yml` as an early guard step. Deliberately scoped to user-facing docs — the
    design/OKF tree legitimately discusses internal names the rename program keeps (§13).

## F. What the guide gets right (keep)

Consistent screen-entry shape; Type/Instance discipline (Visualization Type vs Widget); Events vs
Audit; Lineage vs Provenance; Run⊇Batch⊇File; honest notes on empty states, a11y (status =
text+color), and offline maps; menu-search vs global-search distinction; the §8 vocabulary table
as a device (it just needs correcting and extending). The bones are excellent — the audit above
is about making the guide describe the shipped product, speak its own canonical language, and
stop hiding the Business half of the platform.
