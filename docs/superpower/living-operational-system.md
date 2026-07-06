# Inspecto as a Living Operational System

> Architecture philosophy, agreed with the product owner 2026-07-06. This is the north-star that the
> Component metamodel ([component-model.md](component-model.md)), the metadata network
> ([metadata-network-design.md](metadata-network-design.md)) and every rework slice serve.
> Vocabulary here that is not yet in `docs/GLOSSARY.md` is **proposed** (§6) — the glossary stays
> the binding source of truth.

## 1. The thesis

The platform is a **living operational organism**, not an ETL tool with screens. It is a set of
independent, interconnected **networks** — each with one responsibility — cooperating over **one
metadata model**. No artifact exists in isolation: every record, decision, signal, job,
visualization and user action participates in one or more networks. Because the *architecture* is
the networks and their contracts — not any particular engine — the platform can evolve from a
deterministic rule-based system into an AI-driven autonomous one **without changing its
fundamental design**: AI is just another decision engine plugged into the Decision Network.

**Biological analogy** — Data Lineage = circulatory system (every record has ancestry; data is
never lost, it changes state) · Signals = nervous system (lightweight facts that announce, never
decide) · Decisions = brain (interpret signals; deterministic first, AI-augmented later) ·
Pipelines = metabolism/process (consume data+signals+decisions, produce new ones — the tissue that
connects every network).

**Building analogy** — pillars = Metadata Model · electrical = Signal Network · water = Data
Lineage · gas = External Integrations · drainage = Cleanup & Archival · elevator = Workflow
Navigation · security system = Authorization & Audit · WiFi = Event Bus · control room = Decision
Engine · building-management system = AI Brain.

## 2. The seven networks, mapped to today's codebase

| Network | Responsibility | What exists today (honest state) |
|---|---|---|
| **Data** | records, datasets, storage, transformations, indexing, record lineage, replay | Backend engine (DuckDB, `PartitionWriter`, partitioned stores); batch/file/lineage/quarantine views; provenance T22 (`-Dprovenance.backend=duckdb`, per-batch rows in/out); reprocess = coarse replay. **Gap:** record-level ancestry is per-batch, not per-record; replay is pipeline-level. |
| **Signal** | events, notifications, alerts, triggers, scheduler outputs | Backend sync-bus + `BatchEvent`; Events page; Alerts; Notification Center (channels/prefs); run/job status ticks. **Gap:** three separate stores with three shapes — no unified signal envelope, no correlation ids, no "everything emits" discipline (§5 R4). |
| **Decision** | Expectations, Alert Rules, Decision Rules, AI reasoning, recommendations, approvals, investigations | All three canonical rule kinds have panes; data-table proMax "save as rule" produces **parameterized** rule templates (`:fieldValue`); Assist (AI) pane + backend assist scope; investigations live in Geo/Link studios + Cases. **Gap:** no unified Condition→Evaluation→**Consequence** model — each rule kind hard-wires its one consequence (§5 R5); AI is a chat surface, not yet a pluggable decision engine. |
| **Execution** | jobs, processors, workflow execution, scheduling, retries | Jobs (+ schema-driven job form), Runs (trigger/pause/reprocess), authored pipelines + dry-run/run-to-here, backend runners. **Gap:** job metadata doesn't yet declare emitted signals / produced datasets / retry policy as first-class fields (§5 R2). |
| **Metadata** | schemas, datasets, query metadata, lineage metadata, catalog, dimensions, measures | The **spine**: Component metamodel (`{kind, config, parts, wiring}`), kind registry, Catalog + reuse-graph, GLOSSARY, Metadata Bundle v1 (+v2 design). **Gap just closed by R1:** ref derivation was duplicated 4× (see metadata-network-design §2) — now single-sourced in `component-model/refs.ts`. |
| **Presentation** | dashboards, widgets, maps, graphs, reports, investigation views | VizPlugin registry + Viz Library + Dashboard Builder; Geo Map / Link Analysis studios with saved views; KPI & Reports; `/design` gallery. Already metadata-first: a Widget **is** query+presentation config (§4 Result Sets). |
| **Security** | users, permissions, audit, ownership, authentication | **Deliberately out of core** (Personal edition is auth-free; Standard/Enterprise re-add via the security module + `Authenticator` SPI). Forward seam exists: Lens **capability signals** (`canAuthorWorkbench()` …) that RBAC will re-derive; Audit log page exists. This network's rework waits for the security module — the seams are already shaped for it. |

**External Integrations** (the "gas line") ride the Execution/Signal networks today: connections +
collectors inbound; consequence-invoked APIs outbound land with R5.

## 3. Everything is Metadata — coverage map

First-class **Component kinds today:** grammar, schema, transform, sink, rule, dataset, widget,
dashboard, requirement, reconciliation, link-analysis-view, geo-map-view (+ pipeline and
connection as adjacent stores). **Identity ✓, lineage ✓ (via R1 refs), transportable ✓ (Metadata
Bundle), reusable ✓.** Versioning: not yet (provenance `contentHash`/`originVersion` in bundle v2
is the first step; a real version history is a backend concern).

**Promised by the philosophy, not yet kinds:** job, scheduler/trigger definitions, **query**,
investigation template, alert/notification definitions, case, report, user action. These join the
same registry with a config shape + `deriveRefs` + (where composite) wiring — no new machinery
(§5 R2/R3).

## 4. Queries, Parameters, Result Sets

- **Query as a first-class kind (R3):** today a query is embedded in whatever owns it (dataset
  `query`, widget `controls`→QuerySpec, geo/link view `query`, collector SQL). The `query` kind
  lifts it: `{ type: sql | graph | spatial | search | api, text|spec, parameters[] }` — referenced
  by datasets/widgets/views via `binds` refs, so one query serves many renderings.
- **Parameters (R3):** a `$`-namespace resolved at run time from context providers — `$today`,
  `$day(-7)`, `$current_user/role/pipeline/job/dataset/batch/case/alert/workspace`,
  `$dimension(name)`, `$measure(name)`. Precedents to unify (not reinvent): `:fieldValue` rule
  templates, `:watermark` collectors, `${ENV:…}` (config-time secrets — a **different** namespace,
  stays separate). Resolution seam: `ParameterContext` providers (session, scheduler, investigation,
  previous-job output, AI decision) merged in priority order.
- **Result Sets as semantic objects:** already half-real — plugins declare `VizFit`
  (dims/measures/temporal/cardinality) and `transformProps` separates result from rendering. R3
  formalizes the descriptor: a result set carries shape metadata (columns/roles/cardinality) and
  the Presentation Network *matches* candidate renderings (table, KPI, chart, time series, heat
  map, geo map, link graph, timeline, Sankey, investigation workspace…) — the same query rendered
  differently by metadata alone, which is exactly today's Widget contract generalized.

## 5. Rework roadmap (each slice independently shippable, mock-first)

- **R1 — One ref derivation (SHIPPED with this doc):** `Ref {kind,id,rel,via}` +
  `ComponentKind.deriveRefs` + structural fallbacks in `inspecto/component-model/refs.ts`;
  consumers rewired: reuse-graph, bundle closure, mock delete-protection (dashboards→widgets,
  widgets→views/datasets, views→datasets now actually guarded). Edge vocabulary:
  `binds · tiles · renders · projects · loads`.
- **R2 — Kind coverage — ✅ SHIPPED 2026-07-06:** the `job` ComponentKind
  (`modules/admin/jobs/job.kind.ts`) with the **`schedule` wiring** (`{cron?, on?}`) and the new
  **`triggers`** lineage rel (job → the pipeline whose events fire it) — the Signal network's first
  first-class edge. **Decision:** scheduler/trigger are NOT separate kinds — cron/event are
  mutually exclusive job fields with no second consumer; the schedule *wiring* is the trigger made
  first-class ("no abstraction without a second consumer"). Jobs joined the reuse-graph, the
  Metadata Bundle (runtime state — last status/run — never travels), and delete protection
  (deleting a pipeline a job triggers on now 409s; the pipelines mock handler gained the
  referential check it lacked). Declared-but-unmodeled execution metadata (retry policy, emitted
  signals, produced datasets) lands with R4's signal contract; `params` references land with R3's
  parameter namespace.
- **R3 — Query kind + Parameters + ResultSet descriptor — ✅ SHIPPED 2026-07-06** (§4). Three parts:
  (A) the **`query` ComponentKind** (`studio/queries/`) — a typed envelope `{type:'sql'|'structured',
  datasetId, text|model, parameters[]}` that `binds` its source dataset; a **Query Library** pane
  (`/studio/queries`) authors + previews it offline (resolve params → AlaSQL → describe). Joined the
  three R1 consumers (reuse-graph, bundle, delete-protection) and the mock store (Studio kind).
  (B) the **`$`-parameter namespace** (`inspecto/query/parameters.ts` + `ParameterContextService`) —
  `$today/$now/$day(-N)/$current_user/$role` + user-declared `$name` defaults, resolved to SQL literals
  before a run; deliberately never touches `:fieldValue` templates or `${ENV:}` secrets.
  (C) the **Result Set descriptor** (`inspecto/viz/result-set.ts`) — `describeResultSet(rows)` →
  columns+roles+cardinality, consumed by BOTH the query preview and the Show-Me recommender
  (`recommend()` now scores a `ResultSet`; a `VizField[]` is structurally accepted so the widget builder
  is unchanged). **Deliberate scope cuts** (R2 discipline): query types = sql|structured only
  (graph/spatial stay in the geo/link views); the widget→query link ships as the **lineage edge**
  (`WidgetConfig.queryId` → reuse-graph/protection/bundle), query-**driven rendering** through the viz
  pipeline is a follow-on; the SQL surface is a textarea (CodeMirror upgrade deferred). Seed: one shared
  `recent_high_cost` query bound by two widgets.
- **R4 — Signal envelope — 📋 PLANNED:** one shape — `{ signalId, type, at, source: Ref, correlationId,
  severity?, payload }` — emitted by runs/jobs/rules/user actions; Events page becomes the signal
  ledger; alerts/notifications become *consumers* of signals rather than parallel stores.
- **R5 — Decision network — 📋 PLANNED:** unify the three rule kinds on `Condition → Evaluation → Consequence[]`
  where a **Consequence** is a typed action (`emit-signal · start-job · create-alert · invoke-api ·
  generate-report · trigger-pipeline · render-widget`), executed via the Execution Network. The AI
  Brain then plugs in as a decision engine producing the *same* consequence objects (assist as
  proposer, human approval as a consequence gate) — architecture unchanged, sophistication grows.
- **R6 — Transportability everywhere — 📋 PLANNED:** bundle v2 (refs+provenance) implemented; per-editor and
  per-library import/export surfaces (placement per metadata-network-design §3); every new kind is
  transportable by construction.

Sequencing rationale: R1 is the spine (everything else derives edges from it); R2/R3 widen the
metadata; R4 gives the organism its nerves; R5 its brain; R6 makes any subgraph portable.

## 6. Proposed vocabulary (pending GLOSSARY adoption — do not use unilaterally yet)

**Signal** (a lightweight emitted fact; announces, never decides) · **Consequence** (a typed action
a rule/decision engine produces) · **Query** (first-class executable knowledge, typed) ·
**Parameter** (`$`-namespace runtime binding) · **Result Set** (semantic query output with shape
metadata) · **Decision Engine** (anything that turns signals into consequences: rules today, AI
next). Existing canonical terms stay binding: *Expectation / Alert Rule / Decision Rule* (never
bare "Rule"), *Pipeline* (never Flow), *Incident*, *Measure*, *Source*.

## 7. Guiding principles → enforcement

| Principle | Enforced by |
|---|---|
| Everything is Metadata | Component registry; new artifact = new kind, no bespoke stores |
| Everything has Lineage | `deriveRefs` (R1) + provenance T22; bundle v2 carries refs+provenance |
| Everything Emits Signals | R4 envelope; emitting is part of a kind's exec contract |
| Everything is Composable | parts + wiring; consequences and pipelines compose the rest |
| Everything is Transportable | Metadata Bundle (schema in `schemas/`); R6 surfaces |
| Everything is Observable | signal ledger + audit log + per-run provenance |
| AI is a Decision Layer | R5 decision-engine seam; assist plugs in, architecture untouched |
| One Metadata Model, Many Networks | this doc + component-model.md + GLOSSARY discipline |
