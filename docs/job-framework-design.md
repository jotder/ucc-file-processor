# Job Framework — Design

> **Pluggable Job Types · parameterized Triggers · Signals · Run Artifacts · hot-deployable Job Packs**
>
> **Status: DESIGN (2026-07-08). No implementation yet — this document is the spec to build from.**
> It is an **evolution of the shipped `com.gamma.job` subsystem** (cron/event/manual scheduling,
> non-overlap locking, run ledger, `JobType.PIPELINE` per [`flow-live-execution-plan.md`](flow-live-execution-plan.md)
> — T32 phases A+B+C shipped 2026-06-18/19), **not** a replacement. It subsumes and expands
> [`superpower/backend-backlog.md`](superpower/backend-backlog.md) §3 (Phase D "Job templates:
> trigger-condition-action"). Vocabulary is bound to [`GLOSSARY.md`](GLOSSARY.md) §5, §6-A, §6-B, §8.

---

## 1. Problem & goals

Today every Job is an individually authored `*_job.toon` whose `type:` must be one of four **compiled-in**
enum values (`ENRICH | REPORT | MAINTENANCE | PIPELINE`), built by a hard-coded `switch` in
`JobService.build()`. Triggers fire but carry **no parameters**; chaining works only through the single
hard-wired `BatchEvent` kind; a Run records an outcome row but produces **no queryable description of what
it made**; and adding a new kind of Job means editing the engine.

The goal is a **Job definition & execution framework** where:

- **R1** — Jobs are invoked by **Triggers** owned by the **Scheduler** (cron / event / manual / on-pipeline
  — as today), extended with **on-signal** Triggers.
- **R2** — the firing Trigger / Signal can carry **parameters** into the Run.
- **R3** — the framework can **query a Job Type for its required parameters through an interface**, and
  missing values can be **deduced** (today, event date, record time / watermark, …).
- **R4** — a Job has **its own validated configuration** (TOON, per-type schema).
- **R5** — a Run **logs structured events**.
- **R6** — a Job **emits Signals** (glossary §8 envelope, one ledger).
- **R7** — a Run **produces results/records with queryable result metadata** (Run Artifacts), which is what
  makes chaining Job → Job into a working composition ("job pipeline").
- **R8** — Job Types arrive as **hot-deployable plugins** (Job Packs), discovered by implemented SPI
  interface and/or annotation.
- **R9** — developers implement the logic; the platform ships **template Job Types** (templated SQL,
  bespoke transformation, fraud logic, …).

**Non-goals:** no scheduler rewrite (the `Scheduler`/`CronExpression` primitives stay); no ingest Jobs
(ingest is Pipeline-poll-exclusive — `JobType.java:8`, T23 §3.8); no framework/DI dependency (house rule:
framework-free, `ServiceLoader`, manual constructors); no auth/RBAC in core (space-scoping only).

## 2. As-built baseline (what already exists — reuse, don't rebuild)

| Piece | Where | Role in this design |
|---|---|---|
| `Job` (`name()/type()/run()`), `@PublicApi` since 2.8.0 | `inspecto/src/main/java/com/gamma/job/Job.java` | Extended with a context-taking `run(JobContext)` via default-method bridge (§6.3) |
| `JobConfig` (name/type/cron/onPipeline/enabled/catchUp/params) | `com/gamma/job/JobConfig.java` | Gains trigger v2 keys (`on_signal`, `bind`, `when`, `params`) — additive |
| `JobType` enum (ENRICH/REPORT/MAINTENANCE/PIPELINE) | `com/gamma/job/JobType.java` | Replaced by the open **Job Type registry** (§6.1); enum values become built-in registrations |
| `JobService` (registry, cron arming, `on_pipeline` dispatch, manual trigger + actor, `LockingRunner` non-overlap, virtual-thread executor, catch-up T26, deletion fence T25) | `com/gamma/job/JobService.java` | Stays the **Scheduler-facing host**; `build()` delegates to the registry |
| `JobRunLedger` (CSV + bounded history + optional `DbJobRunStore` DuckDB projection T27) | `com/gamma/job/JobRunLedger.java` | Gains the **Run Log** and **Run Artifact** tables (§9, §10) |
| `JobTemplate` (`*_job_template.toon`, `${param}` substitution) | `com/gamma/job/JobTemplate.java` | Kept as **authoring-time** instantiation; distinct from runtime Parameters (§7.4) |
| `Scheduler` (2-thread `ScheduledExecutorService`; `everySeconds`, `cron`) + `CronExpression` | `com/gamma/service/Scheduler.java`, `CronExpression.java` | Unchanged |
| `BatchEventBus` (synchronous fan-out, per-listener failure isolation) | `com/gamma/service/BatchEventBus.java` | Wrapped by the **Signal bus** with interop adapters (§8.3) |
| `EventLog` (process-wide, per-space MDC routing, pluggable `EventStore`) | `com/gamma/event/EventLog.java` | Persistence seam for the signal ledger (§8.1) |
| `TriggerCoalescer` (debounce event storms into one follow-up run) | `com/gamma/pipeline/exec/TriggerCoalescer.java` | Reused for on-signal storm folding (§8.4) |
| ServiceLoader SPI precedent (`SourceConnectorFactory` + `META-INF/services`, also `AssistAgent`, `DescriptionProvider`, `IntelligenceAgent`, `NotificationChannel`) | `com/gamma/acquire/SourceConnectors.java:38` | The discovery idiom Job Packs follow (§12) |
| `ConfigCodec` (.toon) + `ConfigSpec`s + `ConfigSafetyValidator` (path jail, bounds, allow-lists) | `com/gamma/config/…` | Every Job Type's config schema goes through this gate (§6.1, R4) |
| `PipelineWatermarkStore` (incremental watermark, T32-C), `ViewStore`/`ViewDefinition` | `com/gamma/pipeline/…` | Precedents for `$job.last_success_time` deduction and Dataset registration of Artifacts |
| `AuditTrail` (one seam after `ControlApi.dispatch`) | `com/gamma/control/AuditTrail.java` | New write routes are audited automatically |
| `retry/RetryPolicy`, `CircuitBreaker`, `RateLimiter` | `com/gamma/acquire/…` | Reused for per-Job `retry:` (§16) |

## 3. Vocabulary (binding, per GLOSSARY.md)

Canonical terms used throughout — **Type vs Instance vs Execution** is the spine:

| Term | Meaning here | Glossary |
|---|---|---|
| **Job Type** | The *template implementation* — code that knows how to run (registry entry, plugin-provided). Today: the enum. | §6-A (Type of Job) |
| **Job** | An *authored instance*: a named `*_job.toon` binding a Job Type to config, Triggers, and parameter values. An atomic, Quartz-style Executable. | §6-A |
| **Run** | One execution of an Executable (Run ⊇ Batch ⊇ File). | §6-A |
| **Trigger** | The start condition of a Run: `cron` \| `event` \| `manual` \| `on-pipeline` (+ proposed `on-signal`). Owned by the **Scheduler**; *decides*. | §5 |
| **Signal** | A lightweight emitted fact — *announces, never decides*. Envelope `{signalId, type, at, source, correlationId, severity?, payload}`, one **signal ledger**; Event/Alert/Notification are views. | §8 |
| **Parameter** | A runtime binding in the **`$`-namespace** resolved from a **Parameter Context** just before execution. Never conflated with `:fieldValue` rule-template placeholders or `${ENV:KEY}` secret references. | §6-B |
| **Result Set** | The semantic description of an output: columns with type + analytic role + cardinality. | §6-B |
| **Dataset** | Any queryable relation (Table \| Derived Table \| View). | §6-B |
| **Pipeline** | The authored DAG of Steps (ELT). A Job may be embedded as a **Step** inside a Pipeline. ⛔ never "Flow". | §5 |

**Proposed additions** (to be registered in `GLOSSARY.md` §6-A + the §13 rename map when implementation
starts — this doc does not edit the glossary):

- **Job Pack** — a hot-deployable jar bundling one or more Job Types (SPI implementations + their shaded
  dependencies). Distinct from the *plugin ingester* (`plugins.md`) and from the agent's *InspectoPack*.
- **Run Artifact** — the recorded description of one output a Run produced: kind (dataset \| file \| report),
  a ref, its Result Set, row/byte counts, and a watermark. The queryable "result metadata" of R7.
- **Trigger kind `on-signal`** — extends the §5 Trigger list; `on-pipeline` becomes sugar for a specific
  signal type (§8.3).

## 4. Requirement → gap analysis

| Req | Exists today | Gap this design fills |
|---|---|---|
| R1 invoke via Scheduler Triggers | ✅ cron/event/manual/on-pipeline (`JobService`) | `on-signal` generalization only |
| R2 parameterized Triggers/Signals | ❌ `BatchEvent` carries fixed fields; cron carries nothing | Signal payload + Trigger `args:`/`bind:` (§8.2) |
| R3 parameter query interface + deduction | ❌ (`JobTemplate` `${param}` is authoring-time only) | `ParameterDecl` + `parameters(config)` SPI + Parameter Context deduction (§7) |
| R4 own configuration | ◐ free-form `params` map, no per-type schema | `JobTypeDescriptor.configSpec()` validated by `ConfigSpec` + `ConfigSafetyValidator` (§6.1) |
| R5 log events | ◐ run outcome row + process `EventLog` | Structured per-Run **Run Log**, persisted + API (§9) |
| R6 emit signals | ◐ one hard-wired chain `BatchEvent` | `SignalEmitter`, typed signals, one ledger (§8) |
| R7 results + queryable result metadata → composition | ❌ `JobResult` is pass/fail + counters | **Run Artifacts** + catalog + `$upstream` binding (§10, §11) |
| R8 hot-deployable plugins | ❌ classpath-only ServiceLoader | **Job Packs**: watched dir, isolated classloaders (§12) |
| R9 template implementations | ◐ four built-ins | `sql.template` + bespoke pack examples (§15) |

## 5. Architecture overview

```
                       ┌────────────────────────────── Scheduler (owns Triggers; decides WHEN) ─┐
   cron ──────────────►│                                                                        │
   manual (API+actor)─►│  JobService (host: arming, dispatch, non-overlap, catch-up, fence)     │
   on-pipeline ───────►│                                                                        │
   on-signal ─────────►│   1. resolve Job Type ──► JobTypeRegistry ◄── built-ins                │
        ▲              │   2. resolve Parameters ─► ParameterResolver ◄── classpath SPI         │
        │              │      (trigger args ∘ signal bind ∘ config ∘ deduced ∘ defaults)  ▲     │
        │              │   3. run on virtual thread under LockingRunner                   │     │
        │              └───────────────┬────────────────────────────────────────────┐    │     │
        │                              ▼                                             │  Job Packs
        │                    Job.run(JobContext)                                     │  (watched dir,
        │       ┌──────────────┬──────┴───────┬────────────────┐                     │  isolated
        │       ▼              ▼              ▼                ▼                     │  classloaders)
        │   RunLog         SignalEmitter   ArtifactRecorder  JobServices             │
        │   (per-Run       (typed          (dataset/file     (data dirs, DuckDB,     │
        │    events,        signals)        + Result Set)     SecretResolver,        │
        │    persisted)        │               │              ViewStore)             │
        │                      ▼               ▼                                     │
        │              SIGNAL LEDGER      Run Artifact catalog (DbJobRunStore)       │
        │              (one ledger;       queryable: API + $upstream(...) bindings   │
        └── on-signal   Event/Alert/                                                 │
            Triggers    Notification = views)                                        │
            subscribe                                                                ▼
                                                              JobRunLedger (CSV + memory + DuckDB)
```

Flow of one Run: a Trigger fires → `JobService` looks up the Job's **Job Type** in the registry →
the **ParameterResolver** collects the Type's declared parameters and resolves each from the layered
Parameter Context → the Run starts (non-overlap lock, virtual thread) with a **JobContext** → the Job logs,
emits Signals, records Artifacts → the Run outcome + Artifacts land in the ledger/catalog → emitted Signals
may satisfy other Jobs' `on-signal` Triggers (with `bind:`/`when:`), which is how Job→Job composition runs.

## 6. The SPI — Job Types as plugins

### 6.1 `JobTypeProvider`, `JobTypeDescriptor`, `@JobTypeMeta`

New package `com.gamma.job.spi` (engine module, zero new dependencies):

```java
/** ServiceLoader entry point. One provider = one Job Type. */
public interface JobTypeProvider {

    JobTypeDescriptor descriptor();

    /**
     * R3: the framework queries required parameters THROUGH this interface.
     * Config-aware so a type can derive its parameters from the authored Job
     * (e.g. sql.template scans its SQL text for $placeholders, §15.1).
     */
    default List<ParameterDecl> parameters(JobConfig config) {
        return descriptor().parameters();
    }

    /** Build the runnable for one authored Job. Called once per (re)load, not per Run. */
    Job create(JobInit init);
}

public record JobTypeDescriptor(
        String id,                      // registry key, e.g. "report", "sql.template", "fraud.velocity-screen"
        String title,
        String description,
        ConfigSpec configSpec,          // per-type config schema; enforced with ConfigSafetyValidator
        List<ParameterDecl> parameters, // static declarations (config-independent part)
        List<String> emits,             // signal types this Job Type may emit (catalog/UI wiring)
        List<ArtifactDecl> artifacts) { // artifact kinds it may record (catalog/UI wiring)
}

/** Init-time wiring (manual DI): validated config + host services. */
public record JobInit(JobConfig config, JobServices services) {}
```

Discovery is **interface-first** (`ServiceLoader`, the house idiom — same as `SourceConnectorFactory`).
The annotation is the **secondary, declarative face** of the same metadata:

```java
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
public @interface JobTypeMeta {
    String id();
    String title();
    String[] emits() default {};
}
```

Rules: `ServiceLoader` (via `META-INF/services/com.gamma.job.spi.JobTypeProvider`) is authoritative for
*loading*; `@JobTypeMeta` is read after load for *validation and cataloging* — if present, its `id()` must
match `descriptor().id()` or the provider is rejected (fail-closed, §12.3). This satisfies R8's
"interfaces and/or annotations" without adding a classpath-scanning library.

**`JobTypeRegistry`** replaces the `JobService.build()` enum switch:

- Built-ins register first: `enrich`, `report`, `maintenance`, `pipeline` — the four existing runners
  wrapped as providers. **Existing `*_job.toon` files keep working unchanged** (`type:` strings are the
  registry keys; `JobType.from()` parsing becomes a registry lookup).
- Classpath providers next (`ServiceLoader.load(JobTypeProvider.class)`) — how optional Maven modules
  (the `inspecto-connectors` pattern) contribute types per edition.
- Job Packs last (§12), tracked with their owning classloader.
- Id collision ⇒ the *later* registration is rejected and a `job.type.rejected` signal is emitted.
  Core built-ins are never overridable.

### 6.2 `Job` v2 and `JobContext`

```java
public interface Job {
    String name();
    String type();
    JobResult run(JobContext ctx) throws Exception;
}

/** Everything a Run may touch. A narrow facade — never the whole SourceService. */
public interface JobContext {
    String runId();
    String spaceId();
    TriggerInfo trigger();        // kind, actor, scheduledTime, firing Signal (null for cron/manual)
    Params params();              // resolved Parameters: typed getters (string/integer/date/instant/…)
    Map<String, Object> config(); // the Job's validated own configuration (R4)
    RunLog log();                 // R5
    SignalEmitter signals();      // R6
    ArtifactRecorder artifacts(); // R7
    JobServices services();       // dataDir(), duckDb(), secretResolver(), viewStore(), clock()
}
```

`JobServices` exposes exactly the seams the existing built-ins already receive by constructor today
(data root `-Ddata.dir`, jobs audit dir, DuckDB opener with the mandatory native-access flag handled by
the launcher, `SecretResolver`, `ViewStore`) — secrets only ever through the `SecretsProvider` seam.

### 6.3 Compatibility with the `@PublicApi` `Job` interface

`Job` is public API (`@PublicApi since 2.8.0`); embedders may implement it. Per
[`api-stability.md`](api-stability.md), the migration is a default-method bridge, not a break:

- The existing no-arg `run()` remains and is `@Deprecated(since = "5.x")`.
- New entry point `run(JobContext)` gets a `default` implementation delegating to legacy `run()` —
  old implementations keep working, blind to the context.
- The four built-ins and `PipelineJobRunner` are ported to the context signature in P0.
- Legacy `JobType` enum stays as deprecated constants mapping to registry ids for source compatibility.

## 7. Parameters (R2 + R3)

### 7.1 Declaration

```java
public record ParameterDecl(
        String name,            // e.g. "event_date"
        ParamType type,         // STRING | INTEGER | DECIMAL | BOOLEAN | DATE | INSTANT | DATASET_REF
        boolean required,
        String deduce,          // $-expression tried when nothing explicit is bound, e.g. "$day(-1)"
        String defaultValue,    // literal fallback after deduction
        String description) {}
```

Declared by the Job Type (`descriptor().parameters()` and/or config-aware `parameters(config)`), surfaced
verbatim by `GET /jobs/types/{id}` so the UI can render an authoring form (this is what unblocks the
Workbench → Jobs form of `ia-vocabulary-reorg` Phase D).

### 7.2 Resolution order (deterministic, first hit wins)

For each declared parameter, the `ParameterResolver` evaluates layers of the **Parameter Context**:

1. **Trigger args** — explicit values on this firing: manual `POST /jobs/{name}/trigger` JSON body
   `params:{}`, or a cron/on-signal Trigger's static `args:` block.
2. **Signal bindings** — the Trigger's `bind:` map, evaluated against the firing Signal's payload
   (`$signal.<field>`), for on-signal/on-pipeline Triggers.
3. **Job config** — the authored `params:` block in the `*_job.toon`.
4. **Deduction** — the declaration's `deduce` expression against the built-in context (§7.3).
5. **Default** — the declaration's literal `defaultValue`.
6. Still unresolved and `required` ⇒ the Run **fails fast** in state `REJECTED` before user code executes,
   with a Run Log entry naming the parameter and a `job.run.rejected` signal. Fail-closed, never a
   half-configured Run.

All resolved values are recorded on the Run (ledger + API) — a Run is reproducible from its record.

### 7.3 Deduced parameters — the built-in `$`-context

Reuses the glossary §6-B `$`-namespace (shared semantics with the Query Core of
[`rule-builder-design.md`](rule-builder-design.md); one resolver implementation serves both):

| Expression | Meaning |
|---|---|
| `$today`, `$now` | Current date / instant (space clock, cron zone-aware) |
| `$day(-n)`, `$month(-n)` | Date arithmetic (yesterday = `$day(-1)`) |
| `$run.id`, `$run.fire_time`, `$run.scheduled_time` | This Run's identity and timing (scheduled vs actual) |
| `$run.actor` | `cron` \| `event` \| `manual:<actor>` (existing attribution, T32-C) |
| `$job.last_success_time` | **Record time**: end of this Job's last successful Run (from `JobRunLedger`) — the natural incremental watermark, generalizing `PipelineWatermarkStore` |
| `$signal.<field>` | Payload field of the firing Signal (on-signal Triggers) — **event date** lives here, e.g. `$signal.event_date` |
| `$upstream(<job>).artifact(<name>).<attr>` | Result metadata of another Job's most recent successful Run (§10) — `ref`, `rows`, `watermark`, `time_range` |

The user-story deductions map directly: *today* → `$today`; *event date* → `$signal.event_date` (or
`$day(-1)` for a nightly cron over yesterday's partition); *record time* → `$job.last_success_time`.

### 7.4 Three placeholder namespaces — never conflated (glossary §6-B ⚠)

| Syntax | Moment | Mechanism |
|---|---|---|
| `$name` | **Run time** | Parameter Context (this section) |
| `${param}` in `*_job_template.toon` | **Authoring time** | Existing `JobTemplate` substitution — instantiates a `JobConfig` once; kept as-is |
| `${ENV:KEY}` | **Config-load time, server-side** | Secret reference via `SecretResolver` — never enters the Parameter Context, never logged |

## 8. Triggers & Signals (R1 + R2 + R6)

### 8.1 Signal envelope & ledger

Exactly the glossary §8 envelope — `com.gamma.signal.Signal`:

```java
public record Signal(
        String signalId,               // framework-stamped ULID
        String type,                   // dotted lower-kebab taxonomy: "job.run.completed", "fraud.suspicious-activity"
        Instant at,
        Ref source,                    // rel:'emits' → the producing Job/Run/Pipeline
        String correlationId,          // propagated across a chain (§8.4)
        Severity severity,             // INFO | WARNING | CRITICAL
        Map<String, Object> payload) {}
```

- Jobs emit via `ctx.signals().emit(type, severity, payload)`; the framework stamps identity, time,
  source Ref, correlation, and the space.
- The framework itself emits lifecycle signals for every Run: `job.run.started`, `job.run.completed`
  (payload: outcome, duration, artifact summaries), `job.run.failed`, `job.run.rejected`.
- **One ledger** (the ledger unification of platform track R4 — glossary §8: Event/Alert/Notification
  are *views*; not to be confused with requirement R4 of §1): persistence goes
  through the existing `EventLog`/`EventStore` seam with a dedicated signal event type + a DuckDB
  projection for querying (same dual-write pattern as `JobRunLedger` → `DbJobRunStore`). The `/events`
  page already renders the ledger view.
- Payloads are data, not commands — a Signal *announces, never decides*. Deciding is the Trigger's job.

### 8.2 Trigger configuration v2 (`*_job.toon`, additive keys)

```
name: casefile_export
type: report
on_signal: fraud.suspicious-activity
bind:
  event_date: $signal.event_date
  findings_dataset: $signal.dataset
when: $signal.findings > 0
enabled: true
```

- `on_signal: <type>` — subscribe this Job to a signal type (exact type or a `prefix.*` glob).
- `bind:` — map signal payload → this Job's Parameters (layer 2 of §7.2).
- `when:` — optional guard expression over the same `$`-context, evaluated **before** parameter
  resolution completes the Run plan; false ⇒ Run recorded as `SKIPPED` (cheap, no user code). This is
  the "condition" of backlog Phase D's trigger-condition-action, generalized.
- `args:` — static parameter values a cron Trigger supplies (layer 1).
- Existing keys unchanged: `cron:`, `on_pipeline:`, `enabled:`, `catch_up:`. A Job may carry several
  trigger keys (cron *and* on_signal), exactly as `JobService` dispatches today.

### 8.3 `BatchEvent` interop & migration

`BatchEventBus` stays (AlertService, enrichment triggers, `TriggerCoalescer` all hang off it). The Signal
bus wraps it with two adapters:

- Every published `BatchEvent` is mirrored as signal type `pipeline.commit` (payload: pipeline, runId,
  status, parts, rows, ms) — so `on_pipeline: X` becomes sugar for
  `on_signal: pipeline.commit` + `when: $signal.pipeline == "X"`, with zero behavior change.
- `job.run.completed` signals are mirrored back as the chain `BatchEvent` that `PipelineJobRunner`
  already publishes — existing chained jobs keep firing.

Migration is therefore incremental; nothing existing breaks the day the Signal bus lands.

### 8.4 Loop & storm protection

- **Correlation chain**: a Run triggered by a Signal inherits its `correlationId` and increments a
  `chainDepth` carried in framework metadata. Depth > `-Djobs.signal.maxChainDepth` (default 8) ⇒ the
  Trigger does not fire; a `job.chain.cut` WARNING signal (with the full chain) is emitted instead.
  This makes accidental A→B→A cycles self-extinguishing.
- **Storms**: on-signal dispatch reuses `TriggerCoalescer` — a burst of matching signals while a Run
  holds the non-overlap lock folds into one follow-up Run (already proven for event-triggered Pipeline jobs).

## 9. Run Log — structured event logging (R5)

```java
public interface RunLog {
    void info(String message, Object... kv);
    void warn(String message, Object... kv);
    void error(String message, Throwable t, Object... kv);
}
```

- Entries are `RunLogEntry(runId, seq, at, level, message, kv-map)`; persisted append-only next to the
  run ledger (JSONL under the jobs audit dir, DuckDB projection for querying — the `JobRunLedger` T27
  pattern), bounded per Run (`-Djobs.runlog.maxEntries`, default 10 000, overflow summarized).
- `WARN`/`ERROR` entries are additionally mirrored to the signal ledger (severity mapped) so the
  operational stream sees them without polling per-Run logs.
- Surfaced at `GET /jobs/{name}/runs/{runId}/log` (§14). Secrets never reach the log by construction
  (`${ENV:KEY}` values are resolved outside the Parameter Context, §7.4).

## 10. Run Artifacts — queryable result metadata (R7)

```java
public interface ArtifactRecorder {
    /** A produced/updated Dataset (Table | Derived Table | View). */
    void dataset(String name, String datasetRef, ResultSetMeta resultSet, long rows, Instant watermark);
    /** A produced file (export, report output, …). */
    void file(String name, Path path, long bytes);
}

public record ResultSetMeta(List<Column> columns) {
    public record Column(String name, String sqlType, Role role) {}  // Role: DIMENSION | MEASURE | TEMPORAL
}
```

- Stored as rows in a new `job_run_artifacts` DuckDB table beside `DbJobRunStore` (runId, jobName, seq,
  name, kind, ref, resultSet JSON, rows, bytes, watermark, timeRange).
- **Queryable** two ways:
  - **API**: `GET /jobs/{name}/runs/{runId}/artifacts` and `GET /jobs/{name}/artifacts/latest` (§14).
  - **Parameter Context**: `$upstream(<job>).artifact(<name>).<attr>` (§7.3) — how a downstream Job in a
    composition binds to what its predecessor produced *without* the predecessor pushing anything.
- `ResultSetMeta` **is** the glossary §6-B *Result Set* — the same shape Studio/Show-Me matches against,
  so a Job-produced Dataset is immediately bindable by Widgets.
- Dataset artifacts may **register in the catalog**: a `dataset(...)` record with a new ref creates/updates
  a `ViewDefinition` via the existing `ViewStore` (the T32-C `sink.view` path), making the output visible
  in Catalog → Tables. Opt-out per artifact for scratch outputs.
- The lifecycle signal `job.run.completed` carries artifact summaries in its payload — so `bind:` can pass
  `$signal.dataset` refs to the next Job with no extra query (the §15.3 walkthrough shows both styles).

## 11. Composing Jobs (the "job pipeline", R7)

Two sanctioned composition forms — both use canonical vocabulary (⛔ a chain of Jobs is not a new
"pipeline" concept):

1. **Signal chaining** (this design, P1): downstream Job declares `on_signal` + `bind` + `when` (§8.2)
   against the upstream's lifecycle or domain signals, pulling result metadata via `$signal.*` or
   `$upstream(...)`. Loose coupling: either side can be re-authored independently; the chain is visible in
   the ledger via `correlationId`. This generalizes the shipped `on_pipeline` job chaining (T32 Phase B).
2. **Embedding a Job as a Pipeline Step** (glossary §5: a Step may be an embedded Job): an authored
   Pipeline node of type `step.job` invokes a Job through the same `JobContext`, with upstream step
   outputs bound as Parameters. This belongs to the pipeline-graph/component-model track
   ([`flow-graph-design.md`](flow-graph-design.md), [`superpower/component-model.md`](superpower/component-model.md))
   and is deliberately **out of scope here beyond the seam**: `JobContext` + `ParameterDecl` are designed
   so a Step host can drive them (nothing in the SPI assumes the Scheduler is the caller).

## 12. Job Packs — hot deployment (R8)

### 12.1 Packaging & discovery

A **Job Pack** is a single jar:

```
fraud-screen-pack-1.2.0.jar
├── META-INF/services/com.gamma.job.spi.JobTypeProvider   (one FQCN per line)
├── META-INF/MANIFEST.MF                                  (Pack-Id, Pack-Version, Built-By)
├── com/acme/fraud/VelocityScreenJobType.class            (@JobTypeMeta + implements JobTypeProvider)
├── com/acme/fraud/VelocityScreenJob.class
└── … shaded third-party deps (stay inside the pack; never leak onto the core classpath)
```

- Deployment dir: `-Djobs.packs.dir=<dir>` — **absent by default ⇒ feature entirely off** (fail-closed;
  Personal edition simply doesn't set it). The dir must pass the `ConfigSafetyValidator` path jail.
- `JobPackManager` scans at startup and watches with JDK `WatchService` (settle-delay to avoid reading
  half-copied jars — same stability idea as the acquisition `StabilityGate`).
- Each jar gets its **own `URLClassLoader`** (parent = application loader, parent-first: SPI/API types come
  from the engine; pack-private deps are shaded so isolation needs no child-first tricks).
  `ServiceLoader.load(JobTypeProvider.class, packLoader)` discovers the providers; `@JobTypeMeta` is then
  cross-checked (§6.1). Discovery is thus by **implemented interface and/or annotation**, per R8.

### 12.2 Lifecycle

| Transition | Behavior |
|---|---|
| **Load** | Validate manifest → load providers → validate descriptors (ids well-formed, no collision, `configSpec` parses) → register types → `job.pack.loaded` signal. Any failure rejects the *whole pack* (no partial registration), `job.pack.rejected` signal with cause. |
| **Reload** (new jar version appears) | Register new version's types; **quiesce** old: in-flight Runs finish on the old classloader (the `LockingRunner` non-overlap lock already serializes per-Job Runs); new Runs build from the new provider; old loader is closed after its last Run completes. |
| **Unload** (jar removed) | Types deregister. Authored Jobs referencing them flip to `unavailable`: Triggers stay armed but firings are `REJECTED` with a clear Run Log entry + `job.run.rejected` signal (fail-closed — never silently skipped, never a stale-code run). Jar restored ⇒ Jobs resume untouched. |
| **Crash-consistency** | Registry is rebuilt from the dir at startup; no persisted registry state to corrupt. |

### 12.3 Fail-closed & security posture

- Absent flag = no dynamic code loading at all; the attack surface stays the (air-gappable) fat JAR.
- The packs dir is an **admin-controlled filesystem location** — the same trust boundary as the config
  write-root. Optional `-Djobs.packs.requireSignature=true` verifies jar signatures before loading
  (Standard-edition hardening; ships default-on in the standard flavor's launcher flags, not as
  `if (edition)` code).
- Pack code runs in-process (no sandbox — same trust level as a connector module today; stated
  explicitly so nobody mistakes packs for a multi-tenant plugin marketplace). Secrets remain reachable
  only through the `SecretResolver` seam on `JobServices`; packs never see raw env/config secrets.
- Every pack transition is audited (signals + `AuditTrail` on the management routes) with the pack id,
  version, and file hash.

### 12.4 Editions

Build flavors, never branches (house rule): the framework core lives in `inspecto/` (zero new
dependencies — `WatchService`, `URLClassLoader`, `ServiceLoader` are JDK). First-party optional Job Types
can also ship the **classpath way** as Maven modules (the `inspecto-connectors` precedent) — hot deploy is
for site-local/bespoke logic; the two coexist because both funnel into the same registry.

## 13. Configuration shapes (TOON, no `#` comments — the codec rejects them)

A parameterized cron Job over yesterday's partition (values explained in §15):

```
name: velocity_screen_daily
type: fraud.velocity-screen
cron: "0 6 * * *"
enabled: true
params:
  transactions_dataset: transactions
  max_tx_per_account: 40
```

A signal-chained downstream Job with bindings and a guard — see §8.2. A template-instantiated Job keeps
using the existing `*_job_template.toon` mechanism unchanged (§7.4). All new keys (`on_signal`, `bind`,
`when`, `args`, `params`) are additive to `JobConfig` parsing; every shape passes `ConfigSpec` structural
validation plus `ConfigSafetyValidator` (path jail, bounds, allow-lists) before a Job is registered.

## 14. Control API surface

Following the `RouteModule` pattern (`JobRoutes` extended); writes obey the endpoint-skill fail-closed
gate order (write-root 503 → validate 422 → path jail 403 → conflict 409 → act atomically); everything
mutating is audited by the existing single `AuditTrail` seam.

| Route | Purpose |
|---|---|
| `GET /jobs/types` | Registry listing (id, title, origin: built-in \| module \| pack@version) |
| `GET /jobs/types/{id}` | Full descriptor: config schema + `ParameterDecl`s + emitted signal types + artifact decls — drives UI form generation |
| `POST /jobs/{name}/trigger` | Existing; body gains `params: {}` (layer-1 args) alongside `?actor=` |
| `GET /jobs/{name}/runs/{runId}/log` | Run Log entries (paged) |
| `GET /jobs/{name}/runs/{runId}/artifacts` · `GET /jobs/{name}/artifacts/latest` | Run Artifacts (R7 query surface) |
| `GET /signals?type=&since=&correlationId=` | Ledger query (the `/events` page's view, with correlation-chain filter) |
| `GET /jobs/packs` · `POST /jobs/packs/rescan` | Pack inventory (id, version, hash, types, state) · explicit rescan for ops without waiting on the watcher |

## 15. Template implementations (R9) — worked examples

### 15.1 `sql.template` — Templated SQL Job Type (built-in, P3)

Config = a SQL template + source Datasets + a sink. **Required parameters are derived from the template
text** — the cleanest demonstration of R3's "queried for required parameters through interfaces":

```java
@JobTypeMeta(id = "sql.template", title = "Templated SQL",
             emits = {"job.dataset.produced"})
public final class SqlTemplateJobType implements JobTypeProvider {

    @Override public JobTypeDescriptor descriptor() { return SqlTemplateSpec.DESCRIPTOR; }

    /** Scan the authored SQL for $name tokens → that IS the parameter contract. */
    @Override public List<ParameterDecl> parameters(JobConfig config) {
        String sql = (String) config.params().get("sql");
        return SqlParamScanner.scan(sql);   // $event_date → ParameterDecl("event_date", DATE, required)
    }

    @Override public Job create(JobInit init) { return new SqlTemplateJob(init); }
}
```

```java
final class SqlTemplateJob implements Job {
    private final JobInit init;
    SqlTemplateJob(JobInit init) { this.init = init; }

    @Override public String name() { return init.config().name(); }
    @Override public String type() { return "sql.template"; }

    @Override public JobResult run(JobContext ctx) throws Exception {
        String sql   = SqlParamScanner.substitute((String) ctx.config().get("sql"), ctx.params());
        String sink  = (String) ctx.config().get("sink_dataset");
        long t0 = System.nanoTime();
        try (var conn = ctx.services().duckDb().open()) {
            SourceStoreReader.registerViews(conn, ctx.config(), ctx.services().dataDir());
            var result = SandboxedSql.execute(conn, sql);          // com.gamma.sql sandbox — SELECT-only
            long rows  = PartitionWriter.write(conn, result.table(),
                             ctx.services().dataDir().resolve(sink),
                             Format.PARQUET, Compression.ZSTD, ctx.runId(), partitionCols(ctx));
            ctx.artifacts().dataset("output", sink, result.resultSetMeta(), rows, ctx.services().clock().instant());
            ctx.log().info("wrote derived table", "sink", sink, "rows", rows);
            ctx.signals().emit("job.dataset.produced", Severity.INFO,
                    Map.of("dataset", sink, "rows", rows));
        }
        return JobResult.ok(name(), Duration.ofNanos(System.nanoTime() - t0));
    }
}
```

Reuses: the sandboxed SQL layer (`com.gamma.sql`), `SourceStoreReader` + `PartitionWriter` (T32 pieces),
Result Set metadata straight from the DuckDB result schema. An authored instance:

```
name: daily_txn_rollup
type: sql.template
cron: "30 5 * * *"
enabled: true
params:
  sql: "SELECT account_id, count(*) AS tx_count, sum(amount) AS total FROM transactions WHERE event_date = $event_date GROUP BY account_id"
  sink_dataset: txn_rollup_daily
```

`parameters(config)` reports `event_date` (DATE, required); the author didn't bind it, so the platform
deduces it — the descriptor's declaration carries `deduce: $day(-1)` for nightly semantics, and the UI
form shows exactly that contract.

### 15.2 A bespoke Job Pack — fraud velocity screen (developer-authored, hot-deployed)

The full developer surface for "custom/bespoke logic" — one provider, one job, one services file:

```java
@JobTypeMeta(id = "fraud.velocity-screen", title = "Fraud velocity screen",
             emits = {"fraud.suspicious-activity", "job.dataset.produced"})
public final class VelocityScreenJobType implements JobTypeProvider {

    private static final List<ParameterDecl> PARAMS = List.of(
        new ParameterDecl("event_date", ParamType.DATE, true,  "$day(-1)", null,
                          "Business date of the transaction partition to screen"),
        new ParameterDecl("since",      ParamType.INSTANT, false, "$job.last_success_time", null,
                          "Record-time watermark: only rows loaded after the last successful screen"),
        new ParameterDecl("max_tx_per_account", ParamType.INTEGER, true, null, "25",
                          "Velocity threshold per account per day"));

    @Override public JobTypeDescriptor descriptor() {
        return new JobTypeDescriptor("fraud.velocity-screen", "Fraud velocity screen",
            "Flags accounts whose transaction velocity exceeds a threshold; writes a findings Dataset",
            VelocityScreenSpec.CONFIG, PARAMS,
            List.of("fraud.suspicious-activity", "job.dataset.produced"),
            List.of(ArtifactDecl.dataset("findings")));
    }

    @Override public Job create(JobInit init) { return new VelocityScreenJob(init); }
}
```

```java
final class VelocityScreenJob implements Job {
    private final JobInit init;
    VelocityScreenJob(JobInit init) { this.init = init; }

    @Override public String name() { return init.config().name(); }
    @Override public String type() { return "fraud.velocity-screen"; }

    @Override public JobResult run(JobContext ctx) throws Exception {
        LocalDate day  = ctx.params().date("event_date");
        Instant since  = ctx.params().instantOr("since", Instant.EPOCH);
        int threshold  = ctx.params().integer("max_tx_per_account");
        String source  = (String) ctx.config().get("transactions_dataset");
        ctx.log().info("screening", "dataset", source, "event_date", day, "since", since);

        long findings; ResultSetMeta meta; long t0 = System.nanoTime();
        try (var conn = ctx.services().duckDb().open()) {
            var screen = FraudSql.velocity(conn, source, day, since, threshold);   // developer's logic
            findings = PartitionWriter.write(conn, screen.table(),
                           ctx.services().dataDir().resolve("fraud_findings"),
                           Format.PARQUET, Compression.ZSTD, ctx.runId(), List.of("event_date"));
            meta = screen.resultSetMeta();
        }
        ctx.artifacts().dataset("findings", "fraud_findings", meta, findings, Instant.now());
        if (findings > 0) {
            ctx.signals().emit("fraud.suspicious-activity", Severity.WARNING, Map.of(
                "event_date", day.toString(), "findings", findings, "dataset", "fraud_findings"));
        }
        ctx.log().info("screen complete", "findings", findings);
        return JobResult.ok(name(), Duration.ofNanos(System.nanoTime() - t0));
    }
}
```

`META-INF/services/com.gamma.job.spi.JobTypeProvider`:

```
com.acme.fraud.VelocityScreenJobType
```

**Deploying**: build the shaded jar → copy into `-Djobs.packs.dir` → watcher settles, validates, registers
→ `job.pack.loaded` signal on the ledger → `GET /jobs/types` now lists `fraud.velocity-screen` with its
three declared parameters → author the two `*_job.toon` files (§13 + §8.2) → done. No restart.

### 15.3 End-to-end walkthrough — signal-chained composition

Jobs: `velocity_screen_daily` (§13) and `casefile_export` (§8.2, `on_signal: fraud.suspicious-activity`,
`when: $signal.findings > 0`, binds `event_date`/`findings_dataset` from the payload).

```
06:00:00  Scheduler cron fires velocity_screen_daily            (Trigger: cron)
06:00:00  Parameters resolve: event_date ← deduce $day(-1) = 2026-07-07
          since ← deduce $job.last_success_time = 2026-07-07T06:00:04Z
          max_tx_per_account ← config params = 40               (overrides declared default 25)
06:00:00  Signal  job.run.started    {job: velocity_screen_daily, run: R1, correlationId: C1}
06:00:03  RunLog  info "screening" {dataset: transactions, event_date: 2026-07-07, …}
06:00:19  Artifact recorded: dataset "findings" → fraud_findings (rows: 17, ResultSet: 5 cols, watermark)
06:00:19  Signal  fraud.suspicious-activity  WARNING {event_date: 2026-07-07, findings: 17,
          dataset: fraud_findings, correlationId: C1, chainDepth: 1}
06:00:19  Signal  job.run.completed  {run: R1, outcome: OK, artifacts: [findings…], correlationId: C1}
06:00:19  Trigger on-signal matches casefile_export; when: 17 > 0 ⇒ plan Run R2 (correlationId: C1)
06:00:19  Parameters resolve from bind: event_date ← $signal.event_date, findings_dataset ← $signal.dataset
06:00:25  R2 completes; export file recorded as a file Artifact; job.run.completed {run: R2, correlationId: C1}
          GET /signals?correlationId=C1 shows the whole chain; GET /jobs/velocity_screen_daily/runs/R1/artifacts
          shows the queryable result metadata any third Job (or Studio) can bind to.
```

The same pair re-runs idempotently: a manual re-trigger of R1 with the same `event_date` overwrites the
same partition (the `PartitionWriter` `OVERWRITE_OR_IGNORE` semantics), and `casefile_export` runs again
under a new correlation — or is folded by the `TriggerCoalescer` if the first export is still running.

## 16. Failure modes & edge cases

| Case | Behavior |
|---|---|
| Missing required parameter | Run `REJECTED` before user code; Run Log names the parameter; `job.run.rejected` signal (§7.2) |
| Job Type unavailable (pack removed/rejected) | Firings `REJECTED`, Job flagged `unavailable` in `GET /jobs`; recovers automatically when the pack returns (§12.2) |
| Signal cycle A→B→A | Chain cut at `maxChainDepth`, `job.chain.cut` WARNING with the chain (§8.4) |
| Signal storm | `TriggerCoalescer` folds matching firings under the non-overlap lock (§8.4) |
| Overlapping firings of one Job | Existing `LockingRunner` non-overlap: same-name Runs serialize (cron skip/queue semantics unchanged from today) |
| Crash mid-Run | On restart, ledger rows stuck in `RUNNING` are closed as `INTERRUPTED`; cron catch-up (`catchUpMissedFires`, T26) reschedules missed fires per `catch_up:` |
| Job code throws | Run `FAILED`, stack in Run Log, `job.run.failed` signal; optional per-Job `retry:` block (attempts/backoff) executed through the existing `RetryPolicy` |
| Run hangs | Per-Job `timeout:` — the virtual-thread Run is interrupted, Run `FAILED(timeout)`; DuckDB connection closed by the try-with-resources contract of `JobServices.duckDb()` |
| Half-copied pack jar | `WatchService` settle-delay + manifest validation; unreadable jar ⇒ `job.pack.rejected`, prior version (if any) keeps serving (§12.1) |
| Deleting a Dataset an active Run uses | Existing deletion fence (T25) extended: in-flight Runs' declared dataset refs join the producer/consumer topology the fence checks |
| Space scoping | Runs, signals, artifacts all carry `spaceId` (existing `JobService.spaceId()` + `EventLog` MDC routing); packs are host-wide, Jobs are space-scoped |

## 17. Phasing & effort

| Phase | Scope | Est. |
|---|---|---|
| **P0 — Registry & context** (behavior-preserving refactor) | `JobTypeRegistry` replaces the enum switch; built-ins become providers; `Job.run(JobContext)` bridge; `RunLog` (persist + API); resolved-parameter recording scaffold | ~500–700 LOC + tests |
| **P1 — Parameters, Signals, Artifacts** | `ParameterDecl`/resolver + `$`-context; `Signal` envelope + ledger over `EventLog` + `BatchEvent` adapters; `on_signal`/`bind`/`when`/`args` in `JobConfig`; `ArtifactRecorder` + `job_run_artifacts` + query routes; chain/storm protection | ~900–1300 LOC + tests |
| **P2 — Job Packs** | `JobPackManager` (scan/watch/validate/quiesce), pack routes, signature flag, audit | ~600–900 LOC + tests |
| **P3 — Templates & UI** | `sql.template` Job Type; descriptor-driven authoring form (Workbench → Jobs, closes `ia-vocabulary-reorg` Phase D); example fraud pack under `inspecto/examples/` | ~500–800 LOC + UI |

Each phase is independently shippable; P0 changes no observable behavior (the safety net is the existing
`JobServiceTest` suite staying green under `mvn -o clean test`). No new dependencies in any phase.

## 18. Requirements traceability

| Req | Design answer |
|---|---|
| R1 scheduler signal/trigger invocation | §5, §8.2 — existing Trigger kinds + `on-signal` |
| R2 parameterized signals/triggers | §8.1 payload, §8.2 `args:`/`bind:` |
| R3 parameter query interface + deduction | §6.1 `parameters(config)`, §7 resolver + `$today`/`$signal.event_date`/`$job.last_success_time` |
| R4 own configuration | §6.1 `configSpec` + `ConfigSafetyValidator`, §13 |
| R5 log events | §9 Run Log |
| R6 emit signals | §8.1 `SignalEmitter`, one ledger |
| R7 results + queryable metadata → composition | §10 Run Artifacts + `$upstream`, §11, §15.3 |
| R8 hot-deployable, interface/annotation discovery | §12 Job Packs, §6.1 ServiceLoader + `@JobTypeMeta` |
| R9 developer templates (SQL / bespoke / fraud) | §15.1–15.3 |

## 19. Open questions

1. **"Job Pack" naming** — collides with nothing canonical, but "Pack" is informally used by the
   intelligence module (`InspectoPack`). Alternatives: *Job Module*, *Job Extension*. Decide at glossary
   registration time (§3).
2. **Signal ledger store** — this design rides the `EventLog`/`EventStore` seam (the one-ledger rule of
   platform track R4, glossary §8). If ledger query volume outgrows it, a dedicated DuckDB signal table is the fallback;
   the `Signal` envelope is store-agnostic either way.
3. **Concurrency knob** — is the existing non-overlap-per-Job always right, or do we expose
   `concurrency: skip | queue | allow`? Default stays today's behavior; the knob is additive if wanted.
4. **Artifact → Catalog auto-registration** — default-on (visible outputs, glossary-friendly) or opt-in
   (quiet by default)? Leaning default-on for `dataset(...)` with an opt-out flag (§10).
5. **Pack signature verification default** in the Standard flavor launcher (`requireSignature=true`
   default-on there?) — security review call, ties to the SEC-7 track.
