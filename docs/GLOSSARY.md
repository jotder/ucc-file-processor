# Inspecto — Canonical Vocabulary

> **This is the single source of truth for what every concept is called in Inspecto.** UI labels, model/field
> names, API paths, docs, and conversation must all use the **canonical term** below. The rename rolls out
> **UI → model → backend** (see §6). Companion docs: relationships in [`COMPONENT_GRAPH.md`](COMPONENT_GRAPH.md),
> rationale in [`VOCABULARY_RECOMMENDATIONS.md`](VOCABULARY_RECOMMENDATIONS.md).
>
> Decisions locked 2026-06-29 with the product owner.

---

## 0. Rules of this vocabulary (non-negotiable)

1. **One concept → one word.** Synonyms are forbidden. If two words mean the same thing, one is banned (⛔).
2. **One word → one concept.** Overloaded words are split (e.g. *measure* vs *metric*).
3. **Type vs Instance is always distinguished.** A reusable template is a *Type*; a configured, named, persisted
   thing is an *Instance*. Never use the same bare noun for both.
4. **Forbidden terms (⛔) must not appear** in UI text, model classes/fields, API routes, config keys, or docs.

**Banned → canonical (quick reference):**

| ⛔ Banned | ✅ Canonical | Reason |
|---|---|---|
| Flow | **Pipeline** | "Flow" collided with FlowGraph/Run; one word for the DAG |
| Data Store *(as a relation)* | **Dataset** | "Store" means the physical backend, not a queryable relation |
| Issue | **Incident** | aligns Alert → Incident → Case |
| Rule *(bare)* | **Expectation** \| **Alert Rule** \| **Decision Rule** | one word hid three engines |
| Metric *(for a BI aggregation)* | **Measure** | "metric" is reserved for the observability time-series |
| Collector *(as a noun/entity)* | **Source** | "collect" stays a verb; Source is the entity |

---

## 1. Tenancy

**Space** — A fully isolated project environment in one Inspecto installation. Owns its own Connections,
Sources, Schemas, Pipelines, Jobs, Datasets, Widgets, Dashboards, Incidents, Config, and audit trail. Activity in
one Space is invisible to another.

**Config** — The JSON specification of any Component, stored in **TOON** format. *Every* Component has a Config;
its **Component Type** decides the Config's shape. Think of a Component as a manifest: `{ kind, name, config }`.

**Space Template** — A reusable blueprint bundle of Components (Sources, Pipelines, Schemas, Datasets, Widgets,
Dashboards, Rules, optional seed data) that instantiates a new **Space**. Type→Instance: the Template is the
Type; the Space created from it is the Instance. Shipped verticals: Telecom Revenue Assurance, Fraud
Management, Financial Auditing, Link Analysis. *(Added Wave 0, 2026-07-02.)*

**Metadata Bundle** — A selective, artifact-level export file (**configuration only, never data rows**) for
moving definitions between Inspecto instances (staging → production): dataset metadata, Widgets, Dashboards,
saved Link-Analysis/Geo-Map views, Pipelines, and their registry pieces (grammars/schemas/transforms/sinks/
connections, secrets masked). Import previews new-vs-existing per artifact with a per-item overwrite/skip
choice. UI: Settings → *Import & Export*. Distinct from the whole-space zip bundle and from **Space
Template**. *(Added 2026-07-06; `docs/superpower/metadata-bundle.md`.)*

---

## 1-A. Personas & Surfaces

> Added Wave 0 (2026-07-02) per `superpower/frontend-review-and-completion-plan.md`.

**Lens** — A persona-scoped view of the one operator console: **Business** (consume data, investigate
provenance/lineage, raise Requirements) · **Builder** (author in Workbench + Studio) · **Ops** (built-in
operational features). A Lens filters navigation and toolbars; it is **not** a permission — RBAC arrives with
the security module and maps onto Lenses. ⛔ Never use *Lens* to mean an authorization: a Lens is
self-selected and freely switchable; a **Role** is assigned and enforced.

**Role** — An **assigned authorization** (security-module editions): a named grant set held by a user
(e.g. Business User, Pipeline Developer, Operations/Support, Power User, Admin, Super User). A user may hold
several Roles; Roles *project onto* Lenses (they decide which Lenses are available and which **Capabilities**
are granted within them) but are enforced server-side, unlike Lenses. Not built in the auth-free core.
*(Added 2026-07-03; design: `superpower/rbac-groundwork.md`.)*

**Capability** — One named authorization question the UI gates on (e.g. `canAuthorWorkbench`,
`canOperateRuns`, `canTriageRequirements` — `LensService`). The **seam between Lens and Role**: in the
auth-free core a Capability is derived from the active Lens (honor-system preview); under RBAC the same
signals are re-derived from the subject's Role grants and no pane changes. Panes gate on a Capability,
never on Lens identity. *(Added 2026-07-03.)*

**Workbench** — The Builder surface for acquisition + processing authoring: Connections, Sources, and
Pipelines. *(Formalizes the informal use in §3 Stream.)*

**Studio** — The Builder surface for BI authoring: Datasets, Widgets, Dashboards, plus the Link Analysis
and Geo Map Analysis studios. Pane labels (2026-07-06): **Viz Library** (the searchable Widget gallery),
**Widget Builder** (the Widget authoring/edit surface), **Dashboard Builder** (the Dashboard list + editor).
The labels name surfaces only — the artifacts stay **Widget** / **Dashboard** (§7).

**Requirement** — A Business-authored request for a deliverable — **KPI | Report | Reconciliation | Rule** —
with lifecycle `submitted → accepted | rejected → delivered` (Business submits; Builder triages the intake
queue and decides; delivery is recorded against the requirement), linkable to the Component(s) that satisfy
it. *(Lifecycle simplified from the Wave-0 draft per the product-owner decision 2026-07-03, shipped as C1 —
`superpower/reviews/requirements-intake.md`.)*

---

## 2. Connectivity & Ingestion

**Connection** — Named endpoint + credentials for reaching a remote system (SFTP/FTP/FTPS, a database, cloud
storage). Defined once, reused by many Sources. Holds *how to reach it*, never *what to collect*.

**Source** — A configured collection task bound to one Connection: what to collect (paths/queries), how often,
filename patterns, dedup policy. *(The runtime engine that executes Sources may be called the collector — a role,
lowercase, never a noun entity. ⛔ "Collector" as a model/UI name.)*

**Feed** *(optional)* — A Source bound to a recurring inbound delivery cadence. Use only when the recurring
delivery itself must be named.

**Batch** — A set of one or more files ingested and processed together as one unit of work.

**File** — A single collected file. Carries a **File status** (see *Run* for the status hierarchy).

---

## 3. Schema & Catalog

**Schema** *(= table/record schema)* — The column-and-type structure of one relation: field names, **Attribute
Types**, and validation. It describes a **Table**'s shape. ⚠️ It is **not** a database *namespace* — say "table
schema" if ambiguity is possible.

**Field** *(Attribute)* — One column in a Schema: name, selector (how to locate it in the raw file), Attribute
Type, and optional rules.

**Attribute Type** — The data type of a Field: `string`, `integer`, `decimal`, `boolean`, `date`, `datetime`,
`time`, `currency`, `enum`, `array`, `object`.

**Catalog** — The library/index of all Schemas (and Datasets) in a Space, with version history and usage.

**Stream** — A named external **data origin** as seen in the **Catalog**: one feed (a database, Kafka topic,
file drop, …) together with the Schemas and Tables it produces — browsable and groupable for lineage. A Stream
is *populated by* a **Connection** (the endpoint) + one or more **Sources** (the collection tasks) authored in
the Workbench; it is the **data-plane view of an origin**, not the acquisition config. ⛔ never "Data Source".

---

## 4. Rules (three distinct engines — never bare "Rule")

**Expectation** — A **data-quality** rule that validates records against a Schema (non-null, range, regex,
referential). Borrowed from the Great Expectations model.

**Alert Rule** — Watches an observability **Metric** against a threshold and fires an **Alert** when crossed.

**Decision Rule** — A **business-logic / routing** rule that transforms or routes records (e.g. send event-type
X to sink Y). Drools-style.

---

## 5. Pipeline & Processing (ELT)

> Inspecto is **ELT**, not ETL: the load is a simple write to Parquet; the real transform happens *in* the
> lakehouse, producing Derived Tables.

**Pipeline** — A named, authored **DAG of Steps** that turns raw source files into clean, partitioned Tables.
The Pipeline's `wiring` *is* its graph. ⛔ never "Flow".

**Step** — One node in a Pipeline. A Step is a Parser, Transform, Enrichment, or Sink — **or** an embedded Job —
**or** a sub-Pipeline.

**Parser** — Reads a raw file of a given format (CSV, fixed-width, XML, JSON, EDI, ASN.1, …) into rows/columns.

**Transform** — Reshapes/derives/aggregates (cubes) data. When it materializes output it produces a **Derived
Table**.

**Enrichment** — Augments each record via lookup against reference data.

**Sink** — Writes processed records to a destination. The lakehouse Sink writes **Parquet into a Table**.

**Trigger** — The start condition of a run: `cron` \| `event` \| `manual` \| `on-pipeline`. Owned by the
**Scheduler**.

---

## 6-A. Orchestration (what runs, and when)

**Executable** — The abstraction for anything the Scheduler can start and that produces a **Run**. It is either a
**Pipeline** or a **Job**.

**Job** — An atomic, Quartz-style Executable that can do *anything*. A Job may also be embedded as a **Step**
inside a Pipeline.

**Scheduler** — The Operations engine that owns **Triggers** and starts **Executables** (Pipelines or Jobs). It
defines *when*, not *what*.

**Run** — One execution of an Executable. Runs nest:

> **Run ⊇ Batch ⊇ File** — a Run contains one or more Batches; a Batch processes one or more Files. Each level
> has its own status: **Run status**, **Batch status**, **File status**.

---

## 6-B. Data plane (Lakehouse)

**Dataset** — The umbrella for any queryable relation the BI layer can bind to: **Table** \| **Derived Table** \|
**View**. ⛔ never "Data Store" (that means the physical backend).

**Table** — A Hive-style root directory of **Parquet** files, **partitioned by date / partition key** and
**split by event type**. Its structure is described by a **Schema**. (≈ Iceberg/Hive table.)

**Derived Table** — A materialized Table produced by a Transform or cube/rollup. (≈ materialized view / mart /
OLAP cube.)

**Matrix** — The **user-facing name** for a summary / cube / roll-up data asset. It **is a Derived Table**
(so it lives inside the **Dataset** umbrella) — "Matrix" is the label the Catalog and Studio show, not a new
model type. ⛔ "Cube" stays a *verb* (the Transform action that produces it), never the asset's noun.

**View** — A virtual (logical) query over a Table, Derived Table, or View. No storage of its own.

**Partition** — A physical partition of a Table (the unit of partition pruning).

**Store / Storage** — The **physical** backend that holds the Parquet/partition files. Reserve this word for the
backend only — it is never a Dataset.

**Query** *(added 2026-07-06, R3)* — A first-class, reusable **executable knowledge** Component:
`{ type (sql \| structured), source Dataset, text \| model, Parameters }`. Lifted out of the artifacts that
embed it (a Dataset's view SQL, a Widget's channel mapping) so **one Query serves many renderings**. Authored in
the **Query Library**. ⛔ not a "Report" (a Report is a *scheduled delivery* of rendered output).

**Parameter** *(added 2026-07-06, R3)* — A runtime binding in the **`$`-namespace** (`$today`, `$day(-7)`,
`$current_user`, `$role`, or a user-declared `$name`) resolved from a **Parameter Context** just before a Query
runs. ⛔ Never conflate with the two other placeholder namespaces: a `:fieldValue` **rule-template** placeholder,
or a `${ENV:KEY}` **secret reference** (config-time, server-side).

**Result Set** *(added 2026-07-06, R3)* — The **semantic description of a Query's output**: columns with type +
analytic role (dimension / measure / temporal) + cardinality. The Presentation Network *matches* candidate
renderings against it (Show-Me), so the same Result Set can be drawn many ways by metadata alone. (This is the
"resultset metadata" a Widget binds to in §7.)

---

## 7. BI / Visualization

**Visualization Type** — A reusable **template** for a visualization: chart type, table type, graph, map, etc. It
is a **Component Type** (declares a config schema). ⛔ do not call the template a "Widget".

**Widget** — A **Visualization Type + Config + a binding to a Dataset's resultset metadata** — i.e. the
configured, renderable **instance**. This is the Type→Instance pattern made concrete.

**Dashboard** — A layout (grid) of Widgets (tiles), shared per Space. Authored in the **Dashboard Builder**
pane.

**Viz Library** — The Studio pane listing every saved **Widget** (searchable by text / viz type / tags; each
card can be viewed standalone, edited in the **Widget Builder**, or placed on a Dashboard). A pane label, not
a new concept — the items are Widgets.

**Query Library** *(added 2026-07-06, R3)* — The Studio pane (`/studio/queries`) listing every saved **Query**;
authors SQL + `$`-**Parameters** and previews the **Result Set** offline. A pane label — the items are Queries.

**KPI** — A single-number **Measure** with a target/threshold, rendered as a headline tile (mini → standard →
max).

**Measure** — A BI aggregation (SUM, AVG, COUNT, …) over a Dataset. *(This is the BI sense of "metric"; ⛔ do not
call it a "Metric" — that word is reserved for observability.)*

**Report** — An **operational** report (run health, freshness, SLA — the *KPI & Reports* page). Kept distinct
from analytical **Dashboards** built in Studio.

**Reconciliation** — A comparison between two **Datasets** on matching keys (optionally with tolerances) that
produces **Breaks**. The core Revenue-Assurance / Financial-Audit workload. *(Added Wave 0, 2026-07-02.)*

**Break** — One unmatched or mismatched record found by a Reconciliation, typed **missing-left |
missing-right | value-break**. Lifecycle `open → resolved | auto-closed`: a Break **auto-closes** when its
key re-matches within tolerance on a later run; manual resolutions (with a note) are preserved across runs.
Breaks can raise **Incidents** (future). *(Lifecycle locked with the product owner 2026-07-03, shipped as
C9 — `superpower/reviews/reconciliation.md`.)*

---

## 8. Observability

**Signal** — A lightweight **emitted fact** — it *announces, never decides* (the Signal network,
living-operational-system §1). One envelope `{ signalId, type, at, source, correlationId, severity?, payload }`
where `source` is a metadata **Ref** (`rel:'emits'`) to the producer. Every run, job, Alert Rule firing, failed
Expectation, Decision Consequence and operator action emits one, to a single **signal ledger**. **Event, Alert and
Notification are *views* over this one ledger, not parallel stores** (unified in R4). ⚠️ §6-proposed → **binding**.

**Event** — A **view** of a Signal on the operational activity stream (file collected, Run started/finished,
error). The Signal Ledger page (`/events`) renders the ledger newest-first with each signal's source and severity.

**Metric** — An **observability** time-series signal (throughput, error rate, lag) derived from Signals.
*(Ops sense only; the BI aggregation is a **Measure**.)*

**Alert Rule** — *(see §4)* watches a Metric vs a threshold.

**Alert** — A fired instance of an Alert Rule (severity: info / warning / critical) — the `ALERT_FIRED` **view**
of the signal ledger.

**Notification** — Delivery of a Signal to a channel (email, webhook); a **consumer** of the ledger. Per-user
preferences in Settings.

---

## 9. Incident & Audit

> Chain: **Alert → Incident → Case.**

**Incident** — A tracked operational problem. Raised automatically by an **Alert** or a **Diagnosis**, or
manually. Has a status lifecycle (open → in-progress → resolved). ⛔ never "Issue".

**Case** — A group of related **Incidents** managed as one larger investigation with a shared resolution.

**Diagnosis** — An AI-assisted root-cause analysis of a failing Run or Source that produces an **Incident** with
a suggested fix.

**Audit Log** — The immutable *who-did-what* trail (logins, config changes, permission grants, data exports).
**Distinct** from the operational Event stream.

---

## 10. Component Metamodel (cross-cutting)

> Every authored artifact above is a **Component**. The metamodel is the spine; see
> [`COMPONENT_GRAPH.md`](COMPONENT_GRAPH.md) and [`superpower/component-model.md`](superpower/component-model.md).

**Component** — A configured, named, persisted **instance**: `{ kind, name, config, parts?, wiring? }`. Atomic =
no parts/wiring; composite = parts + a wiring strategy.

**Component Type** *(= Kind)* — The reusable **template** that declares a Component's allowed parts, wiring
strategy, and config schema. (A *Visualization Type* is a Component Type whose instances are Widgets.)

**Part** — A child-Component reference inside a composite, with an optional per-use config override.

**Wiring** — How a composite's parts connect: `graph` (Pipeline) \| `layout` (Dashboard) \| `schedule` (Job) \|
`mapping` (Widget channels) \| `none`.

**Registry** — The **derived** reuse graph over Components (composition ∪ reference). Not a new store.

---

## 11. Graphs & Relationships

> Relationships are becoming load-bearing (a future **Link Analysis Studio**). The words *graph · node · edge ·
> link · relationship · lineage* are the most overloaded in the system — this section keeps them distinct.
> Relationship analysis: [`COMPONENT_GRAPH.md`](COMPONENT_GRAPH.md).

**Graph** — A queryable relationship object: **Nodes** + typed **Edges** that can be traversed, analyzed (paths,
centrality, communities), and rendered. The subject of Link Analysis.

**Node / Edge** — The *generic rendering* primitives (`G6GraphData {nodes, edges}`) shared by one renderer
(`GraphViewComponent`). Each **plane** below overlays its own *typed* node/edge vocabulary — never reuse one
plane's words for another.

**GraphSource** — Where a Graph's nodes/edges come from. One renderer + one query seam + many sources:
`component-registry` (P1) · `lineage` (P2) · `provenance` (P2′) · `entity-projection` (P3).

### The four graph planes (keep distinct)

| Plane | Relates | Typed node / edge words | Backed by |
|---|---|---|---|
| **P1 — Artifact graph** | authored **Components** | Component / Part — `part-of`, `uses` | Registry (derived) |
| **P2 — Lineage graph** | **data assets** (Source→Table→View→KPI) | Asset — `EMITS·DECLARES·DESCRIBES·MATERIALIZES·FEEDS·JOINS_INTO·COMPUTED_FROM·CONSUMES` | `MetadataGraphService` |
| **P2′ — Provenance** | a **Batch**'s records through **Steps** | Step — `flowed-through` (+ row counts) | `DbProvenanceStore` + Provenance rows |
| **P3 — Entity / Link graph** | **records as business entities** | **Entity** / **Link** (+ attributes) | Entity Projection over a Dataset (mock-first — see below) |

**Lineage vs Provenance** — **Lineage** = the *derived asset graph* (which asset feeds which); design-time,
structural. **Provenance** = the *recorded fact* of where data actually came from (which file's records flowed
through which Step, with counts); run-time, observed.

**The store is the bridge.** Provenance has two disjoint halves that never share a `batch_id`: the **ingest**
pipeline records *file → store/partition* counts; an **authored flow** records *step → step* counts and reads a
`source_store`. They stitch on the **store name** (ingest writes it; a flow reads it), exposed by
`GET /lineage?store=` (`control/LineageRoutes`): `upstream` = files into the store, `downstream` = flows
consuming it — i.e. *file → store → flow-step → sink*. ⛔ do not "join on batch_id" — flows process stores, not files.

**Entity / Link** *(business graphs only)* — An **Entity** is a business node (a caller, an account); a **Link**
is a business edge between Entities (a call, a transaction) carrying typed attributes (call-type, duration). ⛔
Never use Entity/Link for artifacts (Component/Part) or assets (Asset/Lineage).

**Entity Projection** *(P3)* — The **mapping** (not a store) that folds a **Dataset**'s rows into an
Entity/Link graph: column → source Entity, column → target Entity, optional columns → Link type/attributes.
Built **frontend-mock-first in the Link Analysis Studio** (C5, 2026-07-04); the backend projection + schema
relationships remain open. Design: [`superpower/link-analysis-and-graphsource.md`](superpower/link-analysis-and-graphsource.md);
plan: [`superpower/link-analysis-studio-plan.md`](superpower/link-analysis-studio-plan.md).

**Link Analysis Studio** — The Builder-lens Studio pane (`/studio/link-analysis`) for graph investigation:
pick a **GraphSource** + query, render via the shared G6 host, analyze (paths, neighborhood, centrality,
communities). A saved investigation is a **Link-Analysis View** (Component kind `link-analysis-view`); when
its source is `entity-projection` it is a **Widget** (a Graph Visualization Type bound to a Dataset).

### Geo (Geo Map Analysis) *(added 2026-07-05 — plan: [`superpower/geo-map-analysis-plan.md`](superpower/geo-map-analysis-plan.md))*

**Geo Map Analysis Studio** — The Builder-lens Studio pane (`/studio/geo-map`, Phase 1) for geographic
investigation: pick a **GeoSource** + **GeoQuery**, render on the offline MapLibre host, investigate (search,
nearby, heatmap, time filter). The *where* sibling of the Link Analysis Studio's *who-connects-to-whom*.

**GeoSource** — Where a map's points/routes come from — the geo seam parallel to **GraphSource** (one map
renderer + one query seam + many sources). First source: `dataset` (lat/lon column projection over a Dataset).

**GeoQuery** — A GeoSource's saved query configuration: Dataset + location/entity/kind/time column mapping +
filters. Parallel to a GraphSource query. ⛔ not *"map config"* / *"geo filter"*.

**GeoPoint / GeoRoute** — The generic map-rendering primitives (`GeoData {points, routes}`, WGS84), the geo
analog of Node/Edge. A **GeoPoint** is a located entity occurrence; a **GeoRoute** is a relationship drawn
between two GeoPoints. ⛔ never *marker/pin* (rendering artifacts, not domain words) in model/API names.

**Geo View** — A saved geo investigation (Component kind `geo-map-view`): GeoSource id + GeoQuery + display
options + camera. Parallel to **Link-Analysis View**.

**Layer** *(geo only)* — A toggleable stratum of the map display: the offline **basemap** layers, the data
plane (points/routes), and overlays (heatmap; Phase 3 boundaries/custom GeoJSON). ⛔ don't reuse *Layer* for
non-map stacking concepts.

**Geocoder** *(geo only)* — The pluggable seam that turns a place *name* into candidate coordinates (the
"find place" jump), parallel to **GeoSource**. Ships one implementation: the offline place-table geocoder
(no network); a customer online geocoder (e.g. Nominatim URL) implements the same interface. Distinct from
**GeoSource** (which projects *Dataset rows* onto the map) — geocoding resolves a name to a point.

### Resolved collisions (do not regress)
- **`USES` → `CONSUMES`** in the **Lineage** graph (Report→KPI), so it no longer collides with the **component**
  graph's `uses` (composite→part). Two planes, two words.
- **`LineageRow` → Provenance** — the file→partition row-count record is *Provenance* data, not the Lineage
  graph. The asset graph keeps the name **Lineage**.
- **`EVENT_TABLE / TRANSFORMED_TABLE / REFERENCE_TABLE`** (lineage NodeKinds) → **Table / Derived Table /
  Reference Dataset** per §1's data-plane vocabulary.

---

## 12. Assistant

**Assistant** — The AI helper that answers questions, drafts Pipeline/Config, validates Expectations and Alert
Rules, and explains errors.

**Model Settings** — AI model selection, keys, and generation parameters used by the Assistant and Diagnosis.

---

## 13. Rename map (migration checklist · UI → model → backend)

Apply in order: **UI labels & routes → frontend models/services → backend classes/API/config keys.** Audit each
touchpoint before renaming; the backend hits below are *known examples*, not an exhaustive list.

| ⛔ Old | ✅ Canonical | Known touchpoints to audit |
|---|---|---|
| Flow | **Pipeline** | ✅ **UI DONE** (`feat/rename-flow-pipeline-runs`): authored-DAG editor FE renamed `Flow*`→`Pipeline*`, `modules/admin/flows/`→`pipelines/`, route `/flows`→`/pipelines`, `FlowsService`→`PipelinesService` + `Flow*` types, `flow-mock`→`pipeline-mock`. **Collision resolved** (full restructure): the *ingest ops* page took `/pipelines`, so it moved to **Runs** (`modules/admin/{pipelines→runs, pipeline-detail→run-detail}`, `Pipeline*`→`Run*` (`RunView`/`RunResult`/`RunStatus`), route `/pipelines`→`/runs`) — matches glossary §5 (Run = one execution). ✅ **Backend DONE** (`refactor/rename-flow-pipeline-backend`): `com.gamma.flow{,.exec}`→`com.gamma.pipeline{,.exec}` + the 18 `Flow*` types→`Pipeline*`; `JobType.FLOW`→`PIPELINE`; routes `/flows`→`/pipelines` (`FlowRoutes`→`PipelineRoutes`) and `/pipelines`→`/runs` (old `PipelineRoutes`→`RunRoutes`); FE service paths + mock-interceptor regexes re-aligned. Audit action names kept as `pipeline.*` (the audited entity is a pipeline config) via an `AuditTrail.resource()` override mapping `/runs`→`pipeline`. Kept: authored-flow storage dir `flows/` + JSON response keys. **No version bump** (nothing shipped on 4.x → no contract/data in the wild). Plans: `docs/superpower/flow-pipeline-runs-rename.md` (UI) + `flow-pipeline-backend-rename.md` (backend). |
| Data Store | **Dataset** | Studio datasets UI; `dataset-types.ts` (already "Dataset" — verify no "store" labels); backend `ComponentStore` stays = *physical store*, not a Dataset |
| Data Source *(browsable origin)* | **Stream** | **Additive, not a model rename.** New Catalog data-origin concept (§3); the acquisition *config* stays **Connection** + **Source**. Touchpoints so far: nav + Catalog labels (Phase A of `superpower/ia-vocabulary-reorg.md`). Backend Stream read-model is Phase B. |
| Cube *(noun / summary asset)* | **Matrix** | **Additive label, not a model rename.** User-facing name for a summary **Derived Table** (§6-B); the model type stays `Derived Table` / `NodeKind.DERIVED_TABLE`. Touchpoints: Catalog/Studio UI labels; persisted materialization is Phase C. |
| Issue | **Incident** | ✅ **DONE** (`2878b31`, breaking → 5.0): `ObjectType.INCIDENT`, `/objects?type=INCIDENT` + `objectType` value, UI `/issues`→`/incidents` (route file renamed), ops-mock seeds INCIDENT. No DB migration (in-memory `ObjectStore`). |
| Rule *(bare)* | **Expectation** / **Alert Rule** / **Decision Rule** | rule builder UI; `AlertRule`, rule services — split by purpose |
| Metric *(BI sense)* | **Measure** | ✅ **UI DONE** (`feat/rename-bi-metric-to-measure`): Studio/viz FE renamed — `DatasetRole`/`FieldRole` `'metric'`→`'measure'`, `NamedMetric`→`NamedMeasure`, `QueryMetric`→`QueryMeasure`, `buildMetric`/`metricId`, `isMetric`, `DatasetConfig.metrics`/`QuerySpec.measures`, plugins, mock data + specs. ✅ **Backend = NO-OP** (verified 2026-06-30): the backend BI concept is **KPI** (`kpis:` / `KpiMeta` / `NodeKind.KPI` / `IdScheme.kpi()`) — a *distinct canonical term* (a single-number Measure with a target), **not** renamed. There is no server-side "Metric" in the BI sense. Kept ops `MetricRegistry`/`MetricsService`/`AcquisitionTelemetry` as **Metric**. |
| Collector *(noun)* | **Source** | any "Collector" labels → "Source"; keep `collect()` verbs |
| `USES` *(lineage edge)* | **`CONSUMES`** | ✅ **DONE** (breaking → 5.0): `EdgeKind.CONSUMES`, `MetadataGraphBuilder` report→kpi edge; FE `models.ts` `EdgeKind` already `CONSUMES`. ⚠️ `/catalog/graph` emits the new value (no alias). |
| `EVENT_TABLE` / `TRANSFORMED_TABLE` / `REFERENCE_TABLE` | **`TABLE`** / **`DERIVED_TABLE`** / **`REFERENCE_DATASET`** | ✅ **DONE** (breaking → 5.0): `NodeKind` enum + all usages (`IdScheme`, `CatalogOverlay`, `MetadataGraphService`, `KpiToSqlSkill`, `SuggestConfigSkill`) + 5 test files; FE `models.ts` union + `node-detail.dialog.ts` `isStore()` + `catalog-graph.ts` shape/glyph. Id tokens (`event`/`xform`/`ref`) unchanged. ⚠️ `/catalog/graph` emits the new enum values (no alias). |
| `LineageRow` *(file→partition rows)* | **Provenance** *(concept)* | `inspecto/etl/LineageRow.java`, `BatchAuditWriter`; the asset graph keeps the name *Lineage* |

**Migration underway** (the *2b* coordinated breaking change, toward **5.0** — one term per verified PR). ✅ = the
rename has landed (see the touchpoint cell for the commit); unmarked rows are the agreed target, not yet started.
**Save Flow→Pipeline for last** (largest blast radius). When a rename lands, mark its row ✅ and record the commit.
