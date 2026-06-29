# Inspecto — Vocabulary Reconciliation & Model Issues

> Companion to [`GLOSSARY.md`](GLOSSARY.md) and [`COMPONENT_GRAPH.md`](COMPONENT_GRAPH.md). Reconciles the
> project's vocabulary with **industry-standard** terms for ingestion, ELT, lakehouse, BI, rule engines,
> orchestration, observability, and audit — so the team interprets each concept identically. Recommendations are
> opinionated; **decisions to confirm** are collected in §7.

---

## 1. What this system *is*, in industry terms

Stripped to its architecture, Inspecto is a **lakehouse ELT platform with an embedded BI layer and an
operations/observability plane.** Each subsystem has a mature, recognizable vocabulary worth borrowing wholesale:

| Inspecto subsystem | Industry pattern | Reference stacks (borrow their words) |
|---|---|---|
| File/feed collection | **Ingestion / Extract-Load (EL)** | Airbyte, Fivetran, NiFi, Azure Data Factory |
| "ETL" (simple L → Parquet, then transform) | **ELT + Medallion** (bronze→silver→gold) | dbt, Databricks medallion |
| Output storage (Hive-style Parquet dirs) | **Lakehouse table format** | Apache Iceberg / Delta / Hive Metastore |
| Schema | **Table / record schema** (not a DB namespace) | Avro / Parquet / Iceberg schema |
| Transform / cubing | **Models / marts / OLAP rollups** | dbt models, OLAP cubes |
| Charts / dashboards | **Semantic + visualization layer** | Apache Superset, Looker, Metabase |
| Rule engine | **Data-quality expectations** and/or **alert rules** | Great Expectations, Soda, Prometheus alerting |
| Job / Pipeline / Scheduler | **Orchestration** (DAG + task + trigger) | Airflow, Dagster, Quartz |
| Event / Metric / Alert | **Observability** (telemetry → monitor → alert) | Prometheus + Grafana, Datadog |
| Audit | **Audit trail** (who-did-what, compliance) | SOC2 / ITIL audit logging |

**Naming this out loud matters:** once the team agrees "the data plane is a lakehouse, the orchestration plane is
Airflow-shaped, the BI plane is Superset-shaped," every ambiguous local term has a canonical anchor to defer to.

---

## 2. The three core modeling issues (highest-leverage fixes)

### Issue A — Make **Type → Instance** explicit and *universal*

You intuited this for UI ("a *reusable* chart/table template" vs "a *renderable* component with mapped config +
resultset metadata"). It is actually the spine of the **whole** system, not just viz:

```
Type / Template  =  reusable, code-backed, declares a config-schema   (a parser type, a transform type, a chart type)
Instance         =  Type  +  Config  +  bindings                       (this CSV parser, this rollup, this revenue chart)
```

- This is the Class/Object, Plugin/Configured-plugin, **Kubernetes `kind` + `spec`** pattern. Your Component
  metamodel already encodes it (`ComponentKind` vs `Component`); the fix is to **name it consistently
  everywhere** and stop treating "Chart" as one node — it is *Chart Type* (template) + *Chart* (instance).
- **Invariant to adopt:** *every persisted artifact = `{ kind, name, config, parts?, wiring? }`* where `config`
  is the JSON-in-TOON spec. That is a manifest, exactly like a K8s object. "Mostly has configuration" becomes
  "**always** has a config; the kind decides its shape."

**Recommended words:** `Component Type` (a.k.a. **Kind**) for the template; `Component` for the configured
instance. For the viz specialization: **Visualization Type** (template) → **Widget** / **Visualization**
(instance bound to a Dataset's resultset metadata).

### Issue B — Name the **data plane** as a lakehouse; rename "Data Store"

Your data model is a clean lakehouse and deserves precise, standard terms:

| Concept | Your term | Recommended term | Why |
|---|---|---|---|
| Hive-style Parquet root dir, partitioned by date/partition-key, split by event type | Table | **Table** ✅ | matches Iceberg/Hive exactly |
| Output of a transform / cube | Derived Table | **Derived Table** ✅ (≈ *materialized view* / *mart* / *cube*) | keep; note the synonyms |
| Logical query over table/view, no storage | View | **View** ✅ | standard |
| Umbrella over table + view | **Data Store** ⚠️ | rename → **Dataset** (or *Data Asset* / *Relation*) | "Data Store" universally means the *storage backend* (a DB, an S3 bucket), **not** a queryable relation. Reserve **"Store/Storage"** for the physical backend. |
| The physical Parquet/partition files | (implicit) | **Partition** / physical files | the unit of partition pruning |

So the data plane reads: **Dataset = Table \| Derived Table \| View.** BI binds to a **Dataset**; the engine
stores it in a **Store**. This single rename removes the worst ambiguity in the current vocabulary.

### Issue C — Separate **what runs** from **when it runs** (Job vs Pipeline vs Scheduler)

Today "Job uses Pipeline" is too flat. Your own description is richer — *"a job can do anything **or** take part
in a data pipeline; the Scheduler fires a start signal to a job **or** a pipeline."* Model an orchestration layer
like Airflow/Dagster:

```
Executable (Runnable)        anything the Scheduler can start; produces a Run
 ├── Pipeline   = composite executable: a DAG of Steps (wiring = graph)
 └── Job        = atomic executable (Quartz-style): "do anything"
                   ↳ a Job may ALSO be embedded as a Step inside a Pipeline

Scheduler  owns  Triggers (cron | event | manual)  →  start →  any Executable
```

- **Trigger/Schedule is orthogonal** to the executable. The Scheduler doesn't "own jobs"; it owns *triggers* that
  *start executables*. This is exactly Airflow's "schedule → DAG" and Quartz's "Trigger → Job."
- A **Pipeline** is a DAG; a **Step** can be a parser/transform/sink **or an embedded Job** **or a sub-Pipeline**.
- This removes the false hierarchy and matches "a job can take part in a pipeline."

---

## 3. Vocabulary reconciliation (full table)

✅ keep · ⚠️ rename/qualify · ➕ introduce

| Your term | Meaning | Industry-standard | Action |
|---|---|---|---|
| Connection | endpoint + credentials | Connection / Linked Service | ✅ keep |
| Source / Collector | configured collection task | Source connector / Feed | ✅ keep "Source"; "Collector" = its runtime role |
| *(recurring inbound delivery)* | files arriving on a cadence | **Feed** | ➕ optional: a *Feed* = a Source bound to a delivery cadence |
| Batch | files processed together | Batch / Load | ✅ keep |
| File status / Batch status / Run status | execution states | task/run state | ⚠️ keep, but make nesting explicit: **Run ⊇ Batch ⊇ File** |
| Schema | record/table field structure | **table/record schema** | ⚠️ keep, qualify — it is *not* a DB *namespace* schema |
| Schema Field / Attribute | one column | Field / Column / Attribute | ✅ keep |
| Parser | format reader | Parser / Deserializer / Decoder | ✅ keep |
| Pipeline / Flow | DAG of data steps | Pipeline / Data flow / DAG | ⚠️ **pick one word: "Pipeline."** Drop "Flow" as a synonym (collides with "Flow run") |
| Pipeline Run | one execution | Run / DAG Run | ✅ keep |
| Transformer | reshape/derive step | Transform / Model (dbt) | ✅ keep "Transform"; its materialized output = a **Derived Table** |
| Enrichment | lookup/augment step | Enrichment / Lookup | ✅ keep |
| Sink | output writer | Sink / Loader / Writer | ✅ keep |
| Job | Quartz-style executable | Job / Task / Operator | ✅ keep; define as the generic *executable* (see Issue C) |
| Scheduler | fires start signals | Scheduler / Orchestrator | ✅ keep; the *when* = **Trigger/Schedule** |
| Data Store | table+view umbrella | **Dataset / Data Asset** | ⚠️ **rename** (see Issue B) |
| Table | Hive Parquet root dir | Table (Iceberg/Hive) | ✅ keep |
| Derived Table | transform/cube output | Derived Table / Materialized View / Cube / Mart | ✅ keep |
| View | logical query | View / Virtual Dataset | ✅ keep |
| UI Component (chart/table/graph/map template) | reusable viz type | **Visualization Type / Viz plugin / Widget type** | ⚠️ qualify as a *Type/Template* |
| *(rendered instance: template+config+resultset)* | bound, displayable viz | **Widget / Visualization (instance)** | ➕ name the instance distinctly from the template |
| Dashboard | layout of widgets | Dashboard (tiles) | ✅ keep |
| KPI | single number + target | KPI / Scorecard / Big-number metric | ✅ keep |
| Report / KPI & Reports | aggregated operational view | Operational report | ✅ keep; see §4 (operational vs analytical) |
| Event | system activity record | Event / Log event | ✅ keep |
| Metric | aggregated signal | Metric / Measure / Time series | ⚠️ keep, but it means two things (§4) |
| Alert Rule / Alert | threshold def / fired instance | Alert rule / Monitor → Alert | ✅ keep |
| Notification | message on alert | Notification | ✅ keep |
| Issue | tracked problem | Issue / **Incident** | ⚠️ decide Issue-vs-Incident naming (§7) |
| Case | group of issues | Case / **Problem** (ITIL) | ⚠️ align hierarchy: Alert → Incident → Problem |
| Diagnosis | AI root-cause | Diagnosis / RCA | ✅ keep |
| Audit Log | who-did-what trail | Audit log / Audit trail | ✅ keep — distinct from Event stream |
| Rule (rule engine) | condition/query over data | **Expectation** (DQ) / Decision rule / Alert rule | ⚠️ split by purpose (§4) |
| Config | JSON-in-TOON settings | Configuration / Spec / Manifest | ✅ keep; it is the universal `spec` of every Component |

---

## 4. Overloaded words to disambiguate (same word, two meanings)

These cause the most cross-team confusion. Pick a qualifier for each:

1. **Schema** — *table/record schema* (columns + types of one relation) vs DB *namespace* (a collection of
   tables). You mean the former. Say **"table schema"** when ambiguous.
2. **Metric** — *BI measure* (a SUM/AVG aggregation in a chart) vs *ops metric* (a Prometheus-style time
   series). Two planes. Suggest **"measure"** for BI, **"metric"** for observability.
3. **Component** — *template/type* vs *configured instance*. Always qualify (Issue A).
4. **Rule** — three distinct engines hide under one word:
   - **Data-Quality Rule / Expectation** — validates records against a Schema (Great Expectations model).
   - **Alert Rule** — watches an ops Metric against a threshold.
   - **Decision/Business Rule** — transforms/routes data (Drools-style), if present.
   Name the purpose; don't let one "Rule" node mean all three.
5. **Report** — *operational report* (run health, freshness, SLA — the KPI & Reports / Operation Dashboard) vs
   *analytical/BI report* (business dashboards in Studio). Keep these two report planes labeled separately.
6. **Pipeline vs Flow** — currently used interchangeably; collapse to **Pipeline**.

---

## 5. Refined model — data plane + orchestration plane

The structural metamodel in `COMPONENT_GRAPH.md` still holds; these two planes are what that graph
under-specified. (Bronze/Silver/Gold = medallion tiers, optional but clarifying.)

```mermaid
flowchart TB
  classDef ing fill:#3a5f1e,stroke:#7cb342,color:#fff;
  classDef proc fill:#3a1e5f,stroke:#9a4ad9,color:#fff;
  classDef data fill:#5f3a1e,stroke:#d98a4a,color:#fff;
  classDef bi fill:#5f1e4a,stroke:#d94a9a,color:#fff;
  classDef orch fill:#1e3a5f,stroke:#4a90d9,color:#fff;

  %% orchestration (when)
  SCHED[Scheduler]:::orch -->|owns| TRIG[Trigger / Schedule<br/>cron · event · manual]:::orch
  TRIG -->|starts| EXEC{{Executable}}:::orch
  EXEC --- PIPE[Pipeline<br/>DAG of Steps]:::proc
  EXEC --- JOB[Job<br/>atomic / Quartz]:::orch
  JOB -. may be a .-> STEP[Pipeline Step]:::proc

  %% ingestion → run
  CONN[Connection]:::ing -->|used by| SRC[Source / Feed]:::ing
  SRC -->|delivers files into| RUN
  PIPE -->|produces| RUN[Run ⊇ Batch ⊇ File]:::proc

  %% ELT: parse → validate → transform → write
  PIPE -->|step| PARSER[Parser]:::proc
  PIPE -->|step| XFORM[Transform / Cube]:::proc
  PIPE -->|step| SINK[Sink]:::proc
  PARSER -.validates against.-> SCHEMA[Table Schema]:::data

  %% data plane (lakehouse)
  RUN -->|writes Parquet| TABLE[Table<br/>Hive dir · part. by date/key · split by event-type]:::data
  SCHEMA -->|describes| TABLE
  XFORM -->|materializes| DERIV[Derived Table / Cube]:::data
  TABLE --> DERIV
  VIEW[View<br/>virtual query]:::data -.over.-> TABLE
  VIEW -.over.-> DERIV
  DATASET[[Dataset = Table | Derived Table | View]]:::data
  TABLE --- DATASET
  DERIV --- DATASET
  VIEW --- DATASET

  %% BI plane
  VIZTYPE[Visualization Type<br/>chart · table · graph · map  template]:::bi -->|instantiated as| WIDGET[Widget / Visualization<br/>= Type + config + resultset binding]:::bi
  WIDGET -->|binds to| DATASET
  DASH[Dashboard]:::bi -->|tiles| WIDGET
  KPI[KPI]:::bi -->|reads| DATASET
```

**Reading it:** the Scheduler (when) starts an Executable (Pipeline or Job); a Pipeline Run ingests via a Source,
parses against a Table Schema, transforms, and **writes Parquet into a Table**; Transforms materialize **Derived
Tables/Cubes**; **Views** are virtual; all three are **Datasets**; the BI layer instantiates a **Visualization
Type** into a **Widget** bound to a Dataset's resultset.

---

## 6. Issues this corrects in the earlier graph (`COMPONENT_GRAPH.md`)

1. **Dataset was a vague "physical/virtual/materialized" blob** → it is a concrete lakehouse: Table / Derived
   Table / View, with the umbrella renamed from "Data Store" to **Dataset**.
2. **Template→Instance was collapsed** → now first-class and universal (Type + Config + bindings).
3. **"Job uses Pipeline" was too flat** → Executable abstraction; Scheduler→Trigger→Executable; a Job can be a
   Pipeline Step.
4. **The lakehouse output ("L") was missing** → Run writes partitioned Parquet into a Table described by a Schema.
5. **Run/Batch/File were one node** → explicit containment **Run ⊇ Batch ⊇ File**.
6. **"ETL" mislabels the architecture** → it is **ELT + medallion** (simple L, transform-in-lakehouse).

---

## 7. Decisions — LOCKED (2026-06-29)

All resolved with the product owner. The canonical result lives in [`GLOSSARY.md`](GLOSSARY.md); this section is
the decision record.

1. **"Data Store" → "Dataset"** ✅ — reserve *Store* for the physical backend.
2. **Drop "Flow"; standardize on "Pipeline"** ✅ everywhere.
3. **Incident hierarchy: Alert → Incident → Case** ✅ — "Issue" is renamed to **Incident**; Case stays the
   top-level grouping.
4. **Viz instance word: Widget** ✅ — a Visualization Type + config + resultset binding.
5. **Rule engine: several, named by purpose** ✅ — **Expectation** (data quality) · **Alert Rule** (ops
   threshold) · **Decision Rule** (routing/business logic). Bare "Rule" is banned.
6. **Adopt measure (BI) / metric (ops) split** ✅ — *adopted* (consistent with the one-word-one-concept rule);
   **Measure** = BI aggregation, **Metric** = observability time-series.
