# Flow Graph — Pipeline-as-Graph Design

> **Status (2026-06-16): DESIGN / proposal — not yet built.** This document is the agreed design for re-modelling
> the Inspecto "pipeline" as an explicit, NiFi-style **graph of typed processor nodes** wired by data and control
> edges, authored from reusable, referenced components and visualised/edited from the UI. It supersedes the
> implicit `source → parse → transform → sink` monolith of [`PipelineConfig`](../inspecto/src/main/java/com/gamma/etl/PipelineConfig.java)
> as the *authoring + topology + visualisation* layer, **without rewriting the batch execution engine**.
>
> **Decisions locked (2026-06-16):**
> 1. **Runtime = topology over the existing batch engine.** The graph is an authoring + routing + visualisation
>    layer; the poll-cycle/batch runtime ([`MultiSourceProcessor`](../inspecto/src/main/java/com/gamma/inspector/MultiSourceProcessor.java),
>    [`SourceProcessor`](../inspecto/src/main/java/com/gamma/inspector/SourceProcessor.java)) is reused, not replaced.
>    No inter-node queues, no streaming runtime in v1. A "FlowFile" = a batch / record-set.
> 2. **Source of truth = TOON files + a referenced component registry** (`use:` references). Git-friendly,
>    human-editable, program-editable. The API is designed so a DB-backed store *could* back it later without
>    changing endpoints, but files are the truth now.
> 3. **"Test a component" = validate + bounded sample dry-run** through the *real* node logic (in-memory /
>    scratch only; never touches production output).
>
> The full finalised decision set is in **[§9](#9-decisions-finalised-2026-06-16)**; what v1 deliberately does
> **not** do (with reasons + workarounds) is in **[§12](#12-boundaries-exceptions--known-limitations-v1)**.

---

## 1. Motivation

`PipelineConfig` is a single monolithic document that hard-codes one linear path: acquire from one source, parse
with one grammar, transform once, write one output. That model:

- **does not scale to many nodes** — a real deployment is dozens of sources, several parsers/grammars, fan-out to
  multiple sinks, side-paths for quarantine/gap/enrichment. Expressed as monoliths, this is unreadable and
  duplicative.
- **duplicates configuration** — the same connection, grammar or schema is copy-pasted across pipelines.
- **buries routing in flags** — "on failure quarantine", "on gap alert", "on commit enrich" are scattered boolean
  knobs, not a topology you can see.
- **is hard to visualise and to build incrementally** — there is no first-class object to render as a graph, and
  no way to configure-and-test one node at a time.

The fix is to make the pipeline an explicit **graph**: typed nodes (processors) connected by typed edges
(relationships), authored from **reusable referenced components**.

## 2. Two graphs — keep them distinct

Inspecto already has **one** graph and is gaining a **second**:

| | Lineage graph (exists) | Flow graph (this design) |
|---|---|---|
| Owner | [`MetadataGraphService`](../inspecto/src/main/java/com/gamma/catalog/MetadataGraphService.java) | new `com.gamma.flow` |
| Nature | **derived** projection of configs | **authored** topology |
| Answers | "where did this column come from" | "what runs, in what order, where do failures/events go" |
| Node kinds | `SOURCE`, `RAW_SCHEMA`, `EVENT_TABLE`, `TRANSFORMED_TABLE`, `COLUMN`, `KPI`, `REPORT` | `acquisition`, `adapter`, `parser`, `transform.*`, `merge`, `enrichment`, `sink`, `alert`/`gap`/`event` |
| Edges | `PRODUCES`/`HAS_COLUMN`/`FEEDS`/`COMPUTED_FROM` | `data` + control: `success`/`failure`/`unmatched`/`gap`/`on_commit` |
| UI | rendered today via G6 (`graph-view.component.ts`) | rendered via the **same** G6 component, new projection |

The flow graph **compiles down into** the lineage graph (a `sink` writing an event table yields the
`EVENT_TABLE` node, etc.), so the catalog keeps working unchanged and the UI gets a second projection from one
renderer.

> There is also a **third, parallel concern — the data plane (provenance)**: not a graph of configs but the
> *quantities* of records that actually flowed through the pipe for a concrete file/run. It shares the flow
> graph's topology and is painted onto its edges. See [§11](#11-the-data-plane--provenance-overlay-the-second-parallel-system).

## 3. Node + relationship taxonomy

### 3.1 Node types

Each node is a thin declaration over machinery that **already exists** — the graph is a new shape on top of the
current engine, not new engine code:

| Node `type` | Category | NiFi analogy | Backed by today |
|---|---|---|---|
| `acquisition` | `SOURCE` | source processor (**file collector**) | `source:` block, `SourceConnector` SPI (`com.gamma.acquire`), connection profiles, stability / dedup / watermark / fetch / retry / circuit-breaker / post-action |
| `adapter` | `SOURCE` | streaming/push source → micro-batch (**stream collector**) | **new** — extends `SourceConnector` SPI; **windows a stream into intermediate files by time/count/size** and **lands them** (§3.6). The single bridge for non-file/event sources |
| `parser` | `PARSE` | record reader | `csv_settings` / external grammar, `SchemaSelector`, fixed-width frontend, plugin ingesters (segments) |
| `transform.*` | `TRANSFORM` | update / route / split processors — **a chainable family, see §3.4** | [`DataTransformer`](../inspecto/src/main/java/com/gamma/etl/DataTransformer.java) + [`TransformCompiler`](../inspecto/src/main/java/com/gamma/etl/TransformCompiler.java) (SQL-expr registry), `CsvSettings` row-filters |
| `transform.merge` | `TRANSFORM` | join/union (fan-in) processor | **new** — SQL over predecessor outputs as relations (§3.4); generalises `EnrichmentConfig` join-against-reference |
| `enrichment` | `TRANSFORM` | join/lookup processor | [`EnrichmentConfig`](../inspecto/src/main/java/com/gamma/enrich/EnrichmentConfig.java) (references, triggers, stage-2 join) |
| `sink.persistent` | `SINK` | put processor — **data rests** | `Output` (CSV / Parquet / DuckLake), DB export, future push targets |
| `sink.materialized` | `SINK` | put processor — **incremental rollup** | **new (node-level)** — a managed/temp table **upserted per batch** (batch-level incremental summary); a node-local implementation, nothing to do with the pipeline topology |
| `sink.view` | `SINK` | put processor — **non-persistent** | **new (node-level)** — a **conceptual** store with no file/table attached; a job / KPI / report / alert API consumes it. Definition is **required but abstract** to the user (see below) |
| `alert` | `CONTROL` | reporting task | `AlertService` (`*_alert.toon` rules) |
| `gap` | `CONTROL` | reporting task | `GapDetector` → `SEQUENCE_GAP` event |
| `event` | `CONTROL` | notification | `EventLog` / `EventStore` |

New node types are added by registering a `FlowNodeType` provider (ServiceLoader), mirroring how
`SourceConnector` and `DescriptionProvider` are discovered — so editions/plugins can contribute nodes.

**Sink is a family, not one node (decided 2026-06-17).** A sink's *materialisation behaviour* is a
**node-level** concern (orthogonal to the pipeline, which stays pure topology): `sink.persistent` rests
the batch as a Parquet file / DuckDB table; `sink.materialized` keeps a managed/temp table it upserts
each batch (a running rollup/summary); `sink.view` persists nothing — it is a logical store a downstream
consumer binds to. All three are category `SINK`, so [`FlowStores`](../inspecto/src/main/java/com/gamma/flow/FlowStores.java)
superimposes them over a shared store **uniformly by store name**, regardless of whether bytes rest
(`producedStores(...).restsOnDisk()` is the persistence flag the deletion fence and the visualiser read).
A legacy `*_pipeline.toon` only ever writes a resting store, so the lift always emits `sink.persistent`;
`materialized`/`view` are **authored-only** new capability.

**`sink.view` definition — required, but abstract to the user.** A view is *required* to declare what it
exposes, but the user expresses it declaratively — *"expose business object X, drawn from store Z"* (its
`store` as producer + a `source_store`/selection as consumer) — **never raw SQL**. The engine concretises
it later (a DuckDB view / on-demand compute). Because a view declares both a produced `store` and a
consumed `source_store`, it is simultaneously a producer and a consumer in `FlowStores`, with no new
machinery — and a view named after a **business object/concept** is the natural bridge to the lineage
graph's `KPI`/`REPORT`/`EVENT_TABLE` nodes (§2).

**Node `category`, `name` and `description`.** Every `FlowNodeType` declares a `category`
(`SOURCE`/`PARSE`/`TRANSFORM`/`SINK`/`CONTROL`) plus a UI `label`/`description` — the **built-in
processor definitions are not deferred**: [`FlowNodeTypes.catalog()`](../inspecto/src/main/java/com/gamma/flow/FlowNodeTypes.java)
feeds the editor palette so the UI can visualise a flow now (§6). Every `FlowNode` additionally carries a
**user-given `name` and `description`** (authored flows set them; they may name a business object or
concept — e.g. the `sink.view` above). Lifted legacy nodes get derived defaults (a lifted sink is named
after the store it produces).

### 3.2 Edges carry a relationship

An edge is `{ from: <nodeId>, rel: <relationship>, to: <nodeId> }`. `rel` defaults to `data`.

- **`data`** — the record-set (batch) flows downstream. This is the normal acquire→parse→transform→sink chain.
- **control relationships** — route a batch on an outcome, making side-paths first-class instead of buried flags:
  - `success` / `failure` — terminal batch outcome (failure → quarantine / dead-letter node).
  - `unmatched` — parser could not match a schema/column-count (→ quarantine or a fallback parser).
  - `gap` — sequence-gap detected (→ `gap`/`alert` node).
  - `on_commit` — batch committed (→ `enrichment` / downstream flow trigger). **Cross-flow only:** an `on_commit`
    edge targets a *different* flow's entry (a trigger), never an ancestor in the same graph — otherwise it would
    form an execution cycle the data-edge-only DAG check (D10) cannot see. The validator must reject an `on_commit`
    edge whose target is reachable within the same `FlowGraph` ([§13 R5](#13-open-risks--corrections-2026-06-16-review)).
- **named data relationships (content-based routing)** — a `transform.route` node emits **operator-defined**
  relationships (e.g. `emea` / `apac` / `other`), each an outgoing `data` edge. This is how records *split* by
  content (NiFi's RouteOnAttribute/RouteOnContent). Routing has two modes (§3.4): **`case`** (default, exclusive —
  exactly one branch per record) or **`clone`** (opt-in, multi-match — a record may leave on several branches);
  an optional `default:` branch catches no-match. A `transform.filter` emits `kept` (default `data`) + `dropped`;
  `transform.validate` emits `valid` + `invalid`. These are the points where the data plane (§11) records a split —
  and, for `clone`, an amplification (§3.5).

A node type declares which relationships it **emits** and which it **accepts**, so the editor and the validator
can reject illegal wiring (e.g. a `sink` does not emit `unmatched`, and every `route` branch must be wired or
explicitly defaulted).

### 3.3 Execution semantics (batch, not streaming)

- A node with **no inbound `data` edge** is a **trigger** (typically `acquisition`); a poll cycle starts there.
- Per cycle, the executor performs a **topological walk** of `data` edges, handing each node its upstream
  batch(es), and routes outcomes along control edges. **This is new scheduling code.** Today's
  [`MultiSourceProcessor.runConfigs`](../inspecto/src/main/java/com/gamma/inspector/MultiSourceProcessor.java)
  fan-out is **per source/config** — one virtual thread per `PipelineConfig`, each fully isolated — so it
  parallelises *sources*, not *branches*; there is no intra-pipeline branch concept to reuse. The intra-flow
  topological walk (and any branch-level parallelism) is built fresh; only the virtual-thread-pool + permit
  pattern is reused, not the branch topology. See [§13 R3](#13-open-risks--corrections-2026-06-16-review).
- **Gating:** `active:` (already implemented, top-level, default `false`) arms the whole flow; a future per-node
  `enabled:` mirrors NiFi start/stop of a single processor.
- No inter-node queues in v1 — a batch is processed to completion through its reachable subgraph within the cycle,
  exactly as today. Back-pressure still exists, but it is **admission-based at the source**, not queue-based (§3.5).

### 3.4 Transform is a chainable family of record operators

`transform` is **not one node** — it is a family of single-purpose, **field-level** record operators that chain
freely (`parse → filter → derive → route → …`), so a flow can carry **many** transformations, in any order, each
independently authored and testable. Every operator works over the batch's **record fields (columns)** and
**compiles to a DuckDB SQL fragment**.

> **Scope honesty (review 2026-06-16).** The existing
> [`TransformCompiler`](../inspecto/src/main/java/com/gamma/etl/TransformCompiler.java) registry is **column-scalar
> only** (`DIRECT`/`EXPR`/`CONCAT_DT`/`FILENAME_DATE`), and
> [`DataTransformer`](../inspecto/src/main/java/com/gamma/etl/DataTransformer.java) assembles exactly one fixed
> shape — `CREATE TABLE dest AS SELECT <cols> FROM <one source>`. So only **`transform.derive` and
> `transform.select`** are true "registry additions." The **row-shaping operators** (`filter`=`WHERE`,
> `route`=multi-output, `validate`=two-output, `dedup`=`QUALIFY`, `split`=`UNNEST`, `merge`=multi-input join) need
> **new SQL-assembly machinery and a new node-output contract** — a node that emits *multiple named relations*,
> which `DataTransformer` cannot do today (it returns one table). The chain-fusion below is likewise new. This is
> real Phase-3 engine work, not a one-line registry entry; see [§13 R1](#13-open-risks--corrections-2026-06-16-review).

| Operator | Does | Compiles to | Emits |
|---|---|---|---|
| `transform.filter` | keep/drop records by a field predicate | `WHERE …` | `data` (kept) + `dropped` |
| `transform.route` | content-based routing, **`mode: case` (default, exclusive) or `clone` (multi-match)** | `case` → `CASE WHEN … ELSE`; `clone` → independent per-branch `WHERE` | one `data` edge **per named route** (+ optional `default`) |
| `transform.merge` | fan-in: join/union predecessors | SQL (`JOIN`/`UNION ALL`) over upstream outputs as relations | `data` |
| `transform.derive` | add/compute/rename/cast fields | scalar column exprs (`EXPR`, `CONCAT_DT`, casts) | `data` |
| `transform.select` | project / drop columns | column list | `data` |
| `transform.validate` | assert field constraints | `CHECK`-style predicate | `valid` + `invalid` |
| `transform.dedup` | drop duplicate records by key | `QUALIFY ROW_NUMBER() …` | `data` (+ `duplicate`) |
| `transform.split` | one record → many (explode) | `UNNEST` / join | `data` |

Example — multiple transforms plus a router that fans into three sinks:

```yaml
nodes:
  - id: keep_valid
    type: transform.filter
    where: "amount > 0 AND status <> 'TEST'"
  - id: add_dt
    type: transform.derive
    fields:
      - { name: event_dt, type: EXPR, expr: "strptime(raw_dt, '%Y%m%d')" }
  - id: by_region
    type: transform.route
    mode: case            # case = exclusive (default) | clone = multi-match fan-out
    routes:
      - { rel: emea,  where: "region IN ('DE','FR','UK')" }
      - { rel: apac,  where: "region IN ('IN','SG','JP')" }
      - { rel: other, default: true }
edges:
  - { from: parse,      to: keep_valid }
  - { from: keep_valid, to: add_dt }            # 'dropped' branch optional → quarantine
  - { from: add_dt,     to: by_region }
  - { from: by_region, rel: emea,  to: sink_emea }
  - { from: by_region, rel: apac,  to: sink_apac }
  - { from: by_region, rel: other, to: sink_other }
```

**Fusion vs. materialisation.** Because operators compile to SQL, a *linear* run of `filter`/`derive`/`select`
is **fused into one SQL pass** (no per-operator copy) for efficiency. A boundary is materialised only where the
graph needs it: a `route`/`split` (records actually diverge) or any edge the data plane (§11) must count. So
"many transforms" stays cheap — the node count is a *logical* authoring/visualisation unit, not N physical passes.

**Router modes.** `transform.route` supports two modes (decided 2026-06-16):
- **`case`** (default) — compiles to a `CASE WHEN … THEN <branch> … ELSE <default>` chain; each record goes to
  **exactly one** branch. Record-conserving (`in = Σ branches + default`), the least-surprising "switch" mental model.
- **`clone`** (opt-in) — independent per-branch `WHERE`; a record may match and leave on **several** branches
  (amplifies — see §3.7 / §11.4). Use for genuine fan-out (same record to multiple destinations).

**Fan-in (`transform.merge`).** A `merge` node takes **multiple inbound `data` edges**; the executor materialises
each predecessor's output as a named relation and the node body is a SQL query referencing them by upstream id —
i.e. *predecessors-as-source-relations*:

```yaml
- id: joined
  type: transform.merge
  inputs: [parse_a, parse_b]
  sql: "SELECT a.*, b.region FROM parse_a a LEFT JOIN parse_b b USING (cust_id)"
```

`UNION` of homogeneous predecessors and **join against a reference/lookup side** (the `EnrichmentConfig` model) are
clean in the batch engine and are the v1 scope. A true **live × live keyed join** (correlating two independently
arriving feeds) needs a barrier + windowing (which cycle of A joins which of B) and is **deferred** — it is the one
fan-in case that fights the batch model.

Each operator supports the **dry-run preview** (§7.2): `POST /components/transform/{id}/test {sampleRows}` returns
the filtered/derived/routed/merged output (with per-branch counts for `route`), so a chain is built and tested one
operator at a time.

### 3.5 Flow control & back-pressure

The runtime is **pull-based and batch-atomic**, so back-pressure works differently than NiFi's push-based queue
saturation — and largely comes for free. **There are no persistent inter-node queues in v1:** a batch flows through
its reachable subgraph synchronously and commits/fails atomically, so there is no in-memory buffer *between* nodes
to overflow. The "queue" that absorbs overload is the **durable source itself** (inbox / remote listing), bounded
by disk/remote capacity — not RAM. Back-pressure is therefore **admission control + bounded resources + adaptive
throttling**, and the levers already exist:

| Pressure source | Bounded by (today) |
|---|---|
| Input faster than processed | Files accumulate **durably at the source**; each cycle admits a bounded set (`batch.max_files`/`max_bytes` + a per-cycle intake cap). Unadmitted work waits on disk, never in memory. |
| Cycle overrun | The poll scheduler is **fixed-delay + non-overlapping** (`ingestLock`) — a slow cycle delays the next, never runs concurrently. Slow downstream ⇒ fewer cycles ⇒ inbox grows ⇒ visible lag, not memory blowup. |
| Worker pressure | Semaphores: `sources.max × processing.threads × duckdb_threads` is a fixed ceiling; a shared global permit budget caps the whole graph. |
| Per-batch memory / spill | DuckDB `memory_limit`, `max_temp_directory_size`, auto-chunking bound peak memory regardless of batch size. |
| Slow / failing branch (sink) | Per-source `CircuitBreaker` trips and stops feeding it (records dead-letter / stay at source); per-sink `RateLimiter` (token bucket, already used for fetch) caps egress. |

**Adaptive throttling (lag-driven).** The engine already exposes queue depth — pending count (`inboxStatus`) and
`oldestInboxAgeSeconds`. When lag crosses a threshold, the admission cap is reduced (pull less) and/or a soft
circuit trips, surfaced as an event/alert. That is back-pressure expressed as adaptive admission, not blocking.
The thresholds ship as a **conservative default** and are **configurable** (decided 2026-06-16) — never hard-coded.
Default policy (overridable per flow): halve the per-cycle admission cap when `oldestInboxAge > 3 × pollInterval`
**or** `pending > 10 × perCycleCap`; restore once both fall below half their trip points (hysteresis avoids flapping).

**Fan-out accounting (`route` clone, `split`).** Cloning/splitting multiplies downstream volume, so the per-batch
byte/row budget is charged the **amplified** volume; if it would exceed, the batch chunks (existing mechanism) and
branches process **sequentially**, bounding peak memory to one branch rather than the sum. The clone is logical
(SQL `CASE`/per-branch `WHERE` over one materialised relation) — bytes duplicate only as each branch sinks.

**Trade-off + escalation path.** The cost of "no queues in v1" is that node scheduling is **not decoupled** — a
fast parser cannot run ahead of a slow sink within a flow. In a batch engine that is acceptable. If decoupled
scheduling (a streaming feel) ever becomes a real requirement, the **phase-2** escalation is true NiFi-style
**per-edge back-pressure**: give each `data` edge a bounded, **spill-to-disk** hand-off queue with a high-water
mark that de-schedules the upstream node when full. This is deliberately **out of v1** (the locked "topology over
the batch engine" decision); add it only when needed.

### 3.6 Node scheduling & triggers (timer / cron / event / manual)

**Entry nodes are scheduled; everything downstream is data-driven.** An entry node (no inbound `data` edge —
typically `acquisition`) carries a **trigger**; downstream nodes run when their upstream batch arrives in the
synchronous walk (§3.3). So in v1 scheduling is **per entry trigger, per flow** — not per node — matching NiFi's
"source processors are timer/cron/event driven, the rest run on data." (Per-node independent scheduling is the
phase-2 decoupled/queue model.)

Trigger types — each already present in the engine in some form, unified here:

- **`schedule`** — `every: 60s` (interval, via `Scheduler.everySeconds` — today's poll) **or** `cron: "0 */5 * * * *"`
  (via the existing `CronExpression` / `Scheduler.cron`). **Absent trigger ⇒ the service poll interval**, so a
  lifted legacy pipeline behaves exactly as today.
- **`event`** — generalises what [`JobConfig`](../inspecto/src/main/java/com/gamma/job/JobConfig.java) (cron/event/
  manual) and `EnrichmentConfig.triggers.onPipeline` already do over the `BatchEventBus` / `EventLog`:
  - internal: `on: commit, from: flows/<id>` (an upstream flow committed — an `on_commit` edge is the graph form
    of this) or `on: <EVENT_TYPE>` (a domain event, e.g. `SEQUENCE_GAP`).
  - external (**phased**): file-arrival push / watch (connector notification / `WatchService`), webhook / API push,
    message queue (Kafka — future, per the acquisition backlog). **These are realised by an `adapter` node, not a
    streaming runtime** (see below).
- **`manual`** — `POST /flows/{id}/trigger` (the existing pipeline-trigger endpoint).

**The `adapter` node — streaming/push → batch landing.** Rather than build a streaming runtime, a non-file or event
source is consumed by an `adapter` entry node that **windows** incoming records (`max_records` / `max_bytes` /
`max_age`, whichever first) and **lands a file** in the inbox/staging — from which the normal `acquisition → parser
→ …` flow runs unchanged. So the entire streaming problem reduces to "make a file", and nothing downstream knows
the difference. **Land-then-ack** for durability: the micro-batch is written to a temp file, fsync'd, atomically
renamed into the inbox, and **only then** is the stream offset / message acked (the same fetch-then-commit ordering
the acquisition ledger already uses ⇒ at-least-once, downstream fingerprint dedup if needed). Back-pressure is
inherent: if downstream lags, landed files queue durably in the inbox and the adapter pauses its consumer (e.g.
Kafka lag / withhold ack) — it never buffers unboundedly in RAM, staying inside the §3.5 model. The window flush
policy *is* the event-coalescing described above.

```yaml
nodes:
  - id: acq
    type: acquisition
    use: connections/sftp-prod
    trigger: { type: schedule, cron: "0 */5 * * * *" }   # absent ⇒ default poll interval
  - id: acq_push
    type: acquisition
    trigger: { type: event, on: file_arrival, coalesce: 30s }
```

**Coalescing under the back-pressure budget.** An event does **not** spawn a run directly — it marks the entry
"work available", and the scheduler admits it under the *same* non-overlapping `ingestLock` + concurrency + lag
budget as a timer tick (§3.5). A `coalesce:` / debounce window collapses event storms (1,000 file-arrival events ⇒
**one** admitted run that drains the pending set, not 1,000 overlapping runs). Event-driven flows thus stay inside
the pull/admission back-pressure model instead of bypassing it. A flow never overlaps itself.

### 3.7 Commit units & clone failure (decided)

The committable unit is **`(batch, branch)`**. **This is a new dimension, not a key extension (review 2026-06-16):**
today nothing carries a branch/sink id — [`CommitLog`](../inspecto/src/main/java/com/gamma/etl/CommitLog.java) is
keyed on `batch_id` alone, the acquisition ledger on `(sourceId, relPath)` per file, and
[`BatchProcessor.commit`](../inspecto/src/main/java/com/gamma/inspector/BatchProcessor.java) writes **one** output
set then finalises the file through a single crash-ordered sequence (register → manifest → backup → markers →
ledger → watermark, "markers LAST"). Supporting `(batch, branch)` therefore means (a) adding a branch dimension to
the commit log, the ledger `PROCESSED` state, markers and the manifest; (b) introducing a **partial-commit** state
that none of those model today; and (c) **splitting `commit()`** into a per-branch part (register/manifest) and a
source-finalisation part (backup/markers/ledger/watermark/post-action) gated on *all branches done* — a refactor of
the most crash-sensitive path in the engine, whose ordering invariant must be preserved. Tracked as
[§13 R2](#13-open-risks--corrections-2026-06-16-review). For a `route` **clone** where one branch's sink fails, the
v1 behaviour is:

- healthy branches **stay committed** — no rollback, no re-write;
- the failed branch **retries / circuit-breaks independently** and dead-letters;
- the **source file is finalised** (marked / deleted, post-action, watermark advanced) only when **all** its
  branches have committed — so "fully collected" remains one well-defined state;
- sink writes are **idempotent** (deterministic partition filenames overwrite on retry — already how partitioned
  parquet/duckdb output behaves), so a re-attempted branch never double-writes.

This yields exactly-once **per `(file, branch)`** and isolates a flaky destination without coupling independent
sinks. Cross-branch **all-or-nothing** (transactional multi-sink) is **explicitly out of scope** — it would force
re-writing healthy sinks on any failure — and is recorded as a known limitation (§12, B9); it can be added later as
an opt-in if a real use case needs it.

### 3.8 Pipelines vs Jobs — two drivers over one shared store (formalised 2026-06-17)

A **pipeline** and a **job** are both processing definitions, but they own **different halves of the data's life**
and are run by **different schedulers**. Conflating them is the confusion this section removes. A pipeline is
*passive* — it never runs itself; something *drives* it.

| | Pipeline (`*_pipeline.toon`) | Job (`*_job.toon`) |
|---|---|---|
| Is | the **ETL itself** — the flow graph: acquire → parse → **enrich / filter / route** → sync → store | a **downstream hop over the output** — often **ad-hoc / bespoke plugin** logic consuming pipeline-produced data (`enrich` / `report` / derive) |
| Operates on | source / sync feeds — data **in motion** (lands it in the store) | the store the pipeline already landed — data **at rest** |
| Driven by | the **poll loop only** ([`SourceService.runAllOnce`](../inspecto/src/main/java/com/gamma/service/SourceService.java), `Scheduler.everySeconds`), gated by `active:` — NiFi-style, its own schedule | the **job scheduler only** (cron / event / manual via [`JobService`](../inspecto/src/main/java/com/gamma/job/JobService.java)) |
| Schedule grain | all `active` pipelines share the one global poll interval | per-job `cron`, or fires on an upstream `on_commit` |
| Role | **producer** | **consumer** — *"a job works on the data the pipeline provides"* |

**Clean rules (decided 2026-06-17):**
1. **A pipeline is the ETL and is poll-driven, period.** The whole acquire → parse → enrich/filter/route → sync →
   store chain (the flow graph itself) runs on the loop schedule when `active: true`; it is **not** driven by the job
   scheduler. (This is why `active:` gates only the poll path, [SourceService.java:487](../inspecto/src/main/java/com/gamma/service/SourceService.java#L487).)
2. **A job is job-driven, period**, and operates on **stored data at rest, not the inbox** — a downstream consumer of
   pipeline output, and the natural home for **ad-hoc / bespoke plugin** logic (`enrich`/`report`/derive via the
   plugin SPI, §12 B6), never a re-acquisition.
   - *Note — `enrich`/`filter`/`route` appear on both sides by design:* in a **pipeline** they are **in-motion**
     flow operators (§3.4, compiled into the ETL pass); as a **job** they are **at-rest** operators over the already
     stored data. The dividing line is **in-motion (pipeline) vs at-rest (job)**, not the operation's name.
3. The two **share the data store** but touch **different slices at different times** — *like two SQL statements over
   one table.* Concurrent **append/read** on disjoint, partitioned slices is safe.
4. **The one hazard is deletion.** A job (or a `maintenance` task) that **deletes or overwrites** a slice another
   driver is reading/writing is the only real race. Deletes must be **fenced** — slice-disjoint, scheduled in a quiet
   window, or ordered strictly after the producer's commit. `maintenance` jobs (the deleters) **stay standalone**
   (§3.1, decided 2026-06-17) and own this fence; they have no dataflow shape.
   - *The fence is keyed by sink kind (§3.1).* Only a store that **rests on disk** can be a deletion hazard, so the
     fence reads `FlowStores.producedStores(...).restsOnDisk()`: a `sink.persistent` or `sink.materialized` store is
     fenced; a `sink.view` persists nothing, so it has **no storage to delete** and never participates in the hazard
     (a consumer of a view re-derives on demand). A `materialized` store adds a wrinkle — its per-batch upsert is the
     producer rewriting *its own* slice, so a reader must tolerate the rollup advancing, but cross-driver deletion
     still only applies to the resting kinds.

**Two schedulers, one responsibility each (decided 2026-06-17):**
- **The loop scheduler** — fixed-delay or no-delay ([`Scheduler.everySeconds`](../inspecto/src/main/java/com/gamma/service/Scheduler.java),
  the poll-all cycle) — drives **pipeline nodes only**, over every `active` flow. **Ingest / acquisition lives here
  exclusively.**
- **The custom-function scheduler** — cron / event / manual ([`JobService`](../inspecto/src/main/java/com/gamma/job/JobService.java)) —
  drives **jobs only** = bespoke plugin functions over stored data. **It never ingests.**

This **removes the `ingest` job type**: a job can no longer re-run a pipeline. **Ingestion is the pipeline's sole
responsibility** (loop scheduler); jobs are strictly downstream custom functions (custom-function scheduler). This
is the resolution of [§13 R6](#13-open-risks--corrections-2026-06-16-review) / [T23](#14-things-to-do-implementation-checklist) —
**deprecate `JobType.INGEST`, do not gate it.** In the flow-graph model (§3.6) the two schedulers are simply which
trigger an entry node carries: `schedule:{every}` → loop scheduler; `cron`/`event`/`manual` → custom-function scheduler.

**Scheduler implementation = the existing `Scheduler` + `JobService`, not Quartz (decided 2026-06-17).** Both
schedulers are the home-grown [`Scheduler`](../inspecto/src/main/java/com/gamma/service/Scheduler.java) (fixed-delay
`everySeconds` + a drift-free self-re-arming `cron` over [`CronExpression`](../inspecto/src/main/java/com/gamma/service/CronExpression.java)),
with [`JobService`](../inspecto/src/main/java/com/gamma/job/JobService.java) adding event/manual triggers, per-job
non-overlap (`LockingRunner` → `SKIPPED`), and **job-execution reporting that already exists**: a durable
`jobs_runs.csv` (`CsvLedger<JobRun>`) + per-job in-memory history + `inspecto_jobs_total` /
`inspecto_job_duration_seconds` metrics + the Control API `jobs()` / `runsFor()` surface. **Quartz is rejected** —
its value-adds (JDBC schedule persistence, clustering, misfire) are either **redundant** (schedules are
config-driven `*_job.toon`, re-armed deterministically at startup — the file *is* the durable schedule) or
**premature** (single-node; distribution is a future edition), and Quartz gives no execution reporting regardless.
Two small, **no-new-dep** extensions are tracked instead: **misfire/catch-up** ([T26](#14-things-to-do-implementation-checklist))
and a **DuckDB-backed Jobs reporting pane** ([T27](#14-things-to-do-implementation-checklist)).

**Combined visualisation — they are one graph, not two.** Pipeline and job meet at the **same store/table**: the
pipeline's `sink(store)` node *is* the job's `source(store)` node. That is exactly the lineage-graph join (§2 — the
flow graph compiles down into `PRODUCES`/`FEEDS`), so the combined view renders as **pipeline-flow → shared table
node → job-flow(s)**, with the `on_commit` edge drawn as the producer→consumer trigger. This is the realisation of
"it should be combined and visualised." **The join is derived, not hand-wired (decided 2026-06-17, T4):** each
`sink` declares the store it produces and each job/enrichment declares the store it consumes, and
[`com.gamma.flow.FlowStores`](../inspecto/src/main/java/com/gamma/flow/FlowStores.java)`.superimpose(...)` matches
them by name — so analysing the configs/metadata alone reconstructs how a job is superimposed over a pipeline's
output, with no `on_pipeline` name-coupling.

**In the flow-graph model the duality disappears** into the entry-node trigger (§3.6): a pipeline is a flow whose
entry `acquisition` node carries a `schedule`/poll trigger; a job is a downstream flow whose entry node reads the
shared store under an `event:{on_commit}` (or its own `cron`) trigger. One scheduler, one graph, the store as the
join — which is why this is formalised here rather than as a second mechanism. See the reconciliation item
[§13 R6](#13-open-risks--corrections-2026-06-16-review) (today's `ingest` job re-runs full acquisition and must be
reframed) and tasks [§14 T23–T25](#14-things-to-do-implementation-checklist).

## 4. Configuration model

Two layers. **Reference, never inline** — this is what kills duplication and keeps both humans and programs sane.

### 4.1 Component registry (reusable, named)

One concern per file, addressed by a typed **in-file identity** `(<type>/<name>)` — *not* the filename — reusing
`ConfigRegistry`'s existing identity-vs-filename reconciliation (decided; §9). Connections and grammars already work
this way; this generalises the pattern:

```
registry/
  connections/   sftp-prod.toon          # already exists as *_connection.toon
  grammars/      pipe-delimited.toon      # already exists (processing.grammar)
  schemas/       cdr-v3.toon              # already exists as *_schema.toon
  transforms/    daily-partition.toon     # new: extracted DataTransformer settings
  sinks/         lake-parquet.toon        # new: extracted Output settings
```

- A component is referenced by name (`use: schemas/cdr-v3`), resolving to its current on-disk content. **v1 has no
  version pinning** — editing a shared component takes effect for every flow that references it on the next reload
  (that *is* the dedup feature; to "pin", copy as a new name). `@version` pinning is a documented future (§12, B5).
- Components are validated independently (each type owns its validator; build on
  [`ConfigValidator`](../inspecto/src/main/java/com/gamma/etl/ConfigValidator.java) and the
  [`config/spec`](../inspecto/src/main/java/com/gamma/config/spec) cross-field rules).
- "What references this?" is answerable by scanning flows for `use:` — drives safe-delete (mirrors the existing
  `connectionInUse` 409 guard).

### 4.2 Flow document (`*_flow.toon`) — thin topology

Nodes reference registry components by id and override only what is local to this flow:

```yaml
name: cdr_ingest
active: true
nodes:
  - id: acq
    type: acquisition
    use: connections/sftp-prod          # reference → deduped
    discovery: { include: ["glob:**/*.cdr.gz"] }   # local override
  - id: parse
    type: parser
    use: grammars/pipe-delimited
    schema: schemas/cdr-v3
  - id: xform
    type: transform
    use: transforms/daily-partition
  - id: lake
    type: sink
    use: sinks/lake-parquet
  - id: quarantine
    type: sink
    use: sinks/quarantine-local
  - id: gap_alert
    type: alert
    use: alerts/missing-cdr
edges:
  - { from: acq,   to: parse }                    # data (default rel)
  - { from: parse, to: xform }
  - { from: xform, to: lake }
  - { from: acq,   rel: failure, to: quarantine } # control
  - { from: parse, rel: unmatched, to: quarantine }
  - { from: acq,   rel: gap, to: gap_alert }
```

Properties this buys:

- **Dedup via `use:`** — one `connections/sftp-prod` feeds N flows; edit once. This extends the
  `referencedFiles()` mtime-watch added on 2026-06-16: a flow's fingerprint includes every component it
  references, so editing a shared grammar reloads exactly the flows that use it and nothing else.
- **Human-editable** — a flow is a short topology, not a 500-line monolith.
- **Program-editable** — `nodes`/`edges` are flat, addressable structures; the UI/API mutate one node without
  rewriting the file (atomic `ConfigCodec.toToon`, write-root-gated, secret-mask-preserving — the existing
  connections-CRUD pattern).
- **Stable identity** — `use:` addresses a component's in-file `<type>/<name>`, not its path, so a component can be
  renamed/relocated on disk without breaking references (version pinning is a future, §12 B5).

## 5. The Flow IR and legacy lift (backward compatibility — non-negotiable)

A large installed base of `*_pipeline.toon` (and ~80 test fixtures) must keep running unchanged. The mechanism:

- **`FlowGraph` IR** — an internal `record FlowGraph(String name, boolean active, List<FlowNode> nodes,
  List<FlowEdge> edges)`; `FlowNode(id, type, Map<String,Object> config, String use)`,
  `FlowEdge(from, rel, to)`. This is the single object the executor, the validator, and the visualiser consume.
- **Legacy lift** — at load time a `*_pipeline.toon` is *auto-lifted* into a `FlowGraph`, plus the control edges its
  existing flags imply (post-action → **success-side finalizer on `acquisition`** with `on_unsupported` → `failure`
  (§15 G8), gap-detection → `gap` edge, enrichment trigger → `on_commit` edge). **No file rewrite** — the lift is internal. **The lift is not always 4-node-linear (review 2026-06-16):**
  a single pipeline already fans into **N schemas** via `segments`/`selector`
  ([`MetadataGraphService.rebuildStructural`](../inspecto/src/main/java/com/gamma/catalog/MetadataGraphService.java)
  handles segments/selector/single today), and a plugin ingester emits multiple segment tables — so many shipped
  configs lift to a **fan-out** (one `acquisition` → N `parser`/`transform`/`sink` paths), not a line. The 4-node
  shape is only the single-schema case.
- **Enrichment crosses the file boundary.** Stage-2 enrichment is a **separate** config type
  ([`EnrichmentConfig`](../inspecto/src/main/java/com/gamma/enrich/EnrichmentConfig.java)), discovered and triggered
  independently (`on_commit`/schedule/event). So an authored flow that contains an `enrichment` node + `on_commit`
  edge spans **two of today's files**; lifting a `*_pipeline.toon` alone will **not** pull its enrichment in. The
  lift must either (a) join pipeline + enrichment configs that reference each other into one `FlowGraph`, or (b)
  represent them as two flows linked by an `on_commit` edge — decide this in Phase 1 ([§13 R4](#13-open-risks--corrections-2026-06-16-review)).
- **Compile-back** — the executor compiles a `FlowGraph` (lifted or authored) back to the *exact* primitives
  `SourceProcessor` runs today, so old configs are byte-for-byte equivalent in behaviour. Parity is proven by
  running the existing suite against the lifted path.
- **Coexistence** — [`ConfigRegistry`](../inspecto/src/main/java/com/gamma/service/ConfigRegistry.java) (now
  mtime-cached) indexes both `*_pipeline.toon` and `*_flow.toon`; `MetadataGraphService` projects both.

Net: the graph model is a **new layer over the existing engine**, never a rewrite of the engine.

## 6. Visualisation (reuse the G6 component already shipped)

The UI already renders the catalog graph with G6 (`inspecto-ui/.../graph-view.component.ts`, reused in
object-detail). **Decided 2026-06-17: visualisation is pulled forward** — the built-in processor definitions and
the pipeline topology must be visualisable *now*, not deferred to a late phase, so the read-only projection lands
right after the Phase-1 IR (the authoring/CRUD + dry-run in §7 stay later). Two inputs make it possible:

- **The node-type catalog** — [`FlowNodeTypes.catalog()`](../inspecto/src/main/java/com/gamma/flow/FlowNodeTypes.java)
  exposes every type's `category` / `label` / `description` / ports (`accepts`+`emits`+named routes) → the **editor
  palette** (grouped by `NodeCategory`). This is the "in-built processor definition" the UI needs.
- **The flow projection** — `GET /flows/{id}/graph` → `{ nodes: [{id,type,category,name,description,status}], edges:
  [{from,to,rel}] }` → the **same** G6 renderer, new data source. Edge style = relationship (solid `data`, dashed
  control), matching the design diagram; sink kind (`persistent`/`materialized`/`view`) styles the node (a `view`
  rendered as a dashed/logical store, no disk glyph).
- **Node inspector panel** — clicking a node opens its effective config (grammar, schema, connection, transform),
  resolved through the `use:` reference. This is the "visualise the pipeline with parser grammar, events,
  connections, transformation" requirement.
- **Live overlay** — reuse the catalog's `OverlaySource` seam for per-node last-run status / counts.
- **Data-plane overlay** — selecting a file paints that file's record counts onto the edges (a Sankey over the
  topology). This is the provenance plane of [§11](#11-the-data-plane--provenance-overlay-the-second-parallel-system),
  rendered on the same G6 canvas.

## 7. Create + test components individually (the build-and-test UX)

The NiFi feel: build a node, **test it in isolation**, wire it, **test the flow**. Each maps to an endpoint and
builds on the existing `POST /connections/{id}/test` template.

### 7.1 CRUD (registry + topology)

- `GET|POST|PUT|DELETE /components/{type}/{id}` — `type ∈ connection|grammar|schema|transform|sink|alert`.
  (Connections CRUD exists; generalise the write-root-gated, atomic, secret-masking pattern.)
- `GET|POST|PUT|DELETE /flows/{id}` and `POST /flows/{id}/nodes`, `POST /flows/{id}/edges` — topology edits.

### 7.2 Dry-run / preview (validate + bounded sample — never touches prod)

Each node type exposes a `preview(sample) → result` method alongside `run()`, so testing reuses the **production
node logic** (no divergent test path). In-memory or scratch output only.

- `POST /components/connection/{id}/test` → connectivity (exists).
- `POST /components/grammar/{id}/test` `{sampleText|sampleFile}` → parsed rows + detected columns.
- `POST /components/schema/{id}/test` `{sampleRows}` → cast results + rejects (which rows/cols failed).
- `POST /components/transform/{id}/test` `{sampleRows}` → transformed + partitioned preview.
- `POST /components/sink/{id}/validate` → writable / credentials / schema-compat (scratch write, then discard).
- `POST /flows/{id}/dry-run` `{sampleFile, fromNode?, toNode?}` → runs a **bounded sample** through a sub-path of
  the graph and returns per-node output + per-edge record counts. This is "test the pipeline incrementally": pick
  any sub-chain and watch records flow.

## 8. Phased roadmap (each shippable, risk-ordered)

1. **Flow IR + legacy lift (backend, invisible).** Define `FlowGraph`/`FlowNode`/`FlowEdge`; lift
   `*_pipeline.toon` into it; compile back to today's execution. *No behaviour change.* (Phase-1 gate = capability
   coverage (T1) + the **lossless round-trip** (T5a); literal execution-through-lift parity is T5b, gated on the
   Phase-3 executor — see §14.) **Acceptance bar
   (review 2026-06-16):** the lift must encode *every* shipped capability — multi-schema `segments`/`selector`
   fan-out, plugin-ingester segment tables, CSV row-filters, post-action, gap, watermark, dedup, **and** the
   pipeline↔enrichment file boundary — proven by the **full existing suite (incl. the plugin-ingester and
   multi-schema fixtures) running green through the lifted path**, against a written capability-coverage checklist
   ([§14 T1](#14-things-to-do-implementation-checklist)). This is the gate; "4-node linear" is only the
   single-schema case (§5).
2. **Component registry + `use:` references.** Extract `transforms/` and `sinks/` component types; generalise the
   connection/grammar/schema reference resolution; extend the `referencedFiles()` mtime-watch to all referenced
   components. **Dedup lands here.**
3. **`*_flow.toon` authoring + topological executor** with `success`/`failure`/`unmatched`/`gap`/`on_commit`
   routing (replaces buried flags with wired edges), **entry-node triggers** (schedule/cron/event/manual, §3.6)
   with event-coalescing, and **`(batch, branch)` commit semantics** (§3.7). Per-node `enabled:`. **This is the
   heavy phase (review 2026-06-16):** it carries the new branch-aware executor (R3), the multi-output node-output
   contract + row-shaping SQL assembly for `filter`/`route`/`validate`/`dedup`/`split`/`merge` (R1), and the
   `commit()` split into per-branch + source-finalisation with a partial-commit state (R2). Sequence R1→R2→R3 as
   sub-steps; do **not** estimate it as "wire up edges."
4. **Flow-graph API + G6 visualisation + node inspector** (read-only first).
5. **Per-component dry-run/test endpoints**, then **component + flow CRUD from the UI** (build-and-test UX).

Phases 1–2 are pure backend and de-risk everything; the UI (4–5) rides on infrastructure that already exists
(G6 renderer, connections CRUD, `OverlaySource`, `ConfigCodec`).

## 9. Decisions (finalised 2026-06-16)

Everything below is **decided** for v1 — no longer open. Each is revisitable at build time, but the design assumes
these answers; what we *won't* do in v1 is collected separately in §12 (boundaries).

| # | Decision |
|---|---|
| D1 | **Runtime** = topology over the existing batch engine; FlowFile = a batch (§3.3). |
| D2 | **Source of truth** = TOON files + a `use:`-referenced component registry (§4); a DB store may back the API later. |
| D3 | **`route`** has `mode: case` (default, exclusive, record-conserving) and `mode: clone` (opt-in, multi-match fan-out) (§3.4). |
| D4 | **Fan-in** = `transform.merge`, SQL over predecessors-as-relations; v1 = `UNION` + join-against-reference (§3.4). |
| D5 | **External/streaming sources** = an `adapter` node that windows records and lands a file (land-then-ack) (§3.6). |
| D6 | **Triggers** on entry nodes = `schedule`/`cron`/`event`/`manual` + event coalescing; downstream is data-driven (§3.6). |
| D7 | **Back-pressure** = pull/admission + bounded resources + adaptive throttle (conservative configurable default); no inter-node queues (§3.5). |
| D8 | **Clone failure** = `(batch, branch)` commit; source finalised only when all branches commit; idempotent sinks (§3.7). |
| D9 | **Component identity** = in-file `<type>/<name>` (not filename), reusing `ConfigRegistry` reconciliation; **no version pinning in v1** (§4.1). |
| D10 | **Validity** = flows are DAGs over `data` edges; the validator rejects cycles (control edges to terminal nodes are not part of the DAG walk). |
| D11 | **Test** = validate + bounded sample dry-run through real node logic, scratch-only (§7.2). |
| D12 | **Backward compat** = legacy `*_pipeline.toon` auto-lifts to the IR and compiles back to today's execution; parity-gated by the existing suite (§5). |

## 10. Naming

- Package: `com.gamma.flow` (IR, executor, lift, node-type SPI).
- Files: `*_flow.toon` (topology), `registry/<type>/<name>.toon` (components).
- API base: `/flows`, `/components/{type}`.
- This sits beside, and feeds, the existing `com.gamma.catalog` lineage graph.

## 11. The data plane — provenance overlay (the second parallel system)

There are **two parallel systems**, and conflating them is the trap:

- **The pipe (control / structure plane)** — the `FlowGraph`: nodes + edges, design-time, slowly-changing.
  *"What can run and how is it wired."* (§3–6.)
- **The data (provenance plane)** — for a concrete unit of data, a run-time trace of how many records entered and
  left each node and which edge (relationship) they took. *"What actually happened to this file."*

They **share the same topology**: the data plane is the pipe with **quantities painted on the edges**. Selecting a
file and watching its records fan down the paths (and leak off into quarantine / rejects) is the provenance plane
rendered as a Sankey over the structure plane. (This is exactly the flow-canvas vs. data-provenance split in NiFi.)

### 11.1 The unit of data and its key

A file fans out: `file → batches → output partitions`, and records **split** at every operator that diverges —
`transform.route` (named branches), `transform.filter` (`dropped`), `transform.validate` (`invalid`),
`transform.dedup` (`duplicate`) — as well as the control edges (`unmatched`, `failure`, `gap`). So a file's
provenance is an **aggregation per `(node, outgoing-edge)`**, not a 1:1 record. The trace is threaded by a stable
**provenance / correlation id** (the engine already uses a correlation id as the join key between the event engine
and the object engine). Each `transform.*` operator reports `recordsIn`/`recordsOut`-per-relationship, which is
exactly what the edge-weighted Sankey paints.

### 11.2 What already exists (≈ half-built)

- [`LineageRow`](../inspecto/src/main/java/com/gamma/etl/LineageRow.java)`(batchId, srcId, inputFile, outputFile,
  partition, rowCount)` — the many-to-many **count matrix** "N rows from this input file landed in that output
  partition," collected by `LineageCollector` at the transform→write boundary (`BatchIngestStrategy.writeAndTrace`)
  and persisted to the lineage CSV.
- Acquisition-boundary **events** (`FILE_DISCOVERED / FETCHED / VALIDATED / FETCH_FAILED`) + metrics.
- **Batch audit** per-batch row counts; `IngestProgress` per-file in-flight position.

### 11.3 The gap (to make "select a file → counts per path" real)

1. **A per-edge counter at every node boundary** (not only transform→write): `recordsIn` / `recordsOut` /
   `diverted`, tagged with the relationship taken. Today the inner matrix is rich but the routing **splits**
   (the unmatched, the rejected) are not first-class quantities.
2. **A unified provenance key** joining acquisition events + lineage matrix + enrichment for one file across the
   whole graph (a `provenance` table / reuse the event store, keyed by correlation id + runId).
3. **A graph-shaped query + overlay** — `GET /provenance?file=<id>` (or `/flows/{id}/runs/{runId}/trace`) returns
   counts mapped onto `FlowGraph` edges; the UI paints them on the G6 canvas as edge weights/labels.

### 11.4 Conservation invariant (free observability win)

At a non-amplifying node (incl. `route` in **`case`** mode): `recordsIn = recordsOut + diverted + dropped`. At a
**`route` clone / `split`** node `recordsOut` can exceed `recordsIn`, so conservation is stated over *records
accounted for*:
`recordsIn = matched (on ≥1 branch) + unmatched (dropped)`, while the per-branch sum is tracked separately as an
**amplification factor** (≥ 1 at clone points, expected). An imbalance in *accounted-for* records is **silent data
loss** — an invariant the current flag-based model cannot express, and a strong reason to make per-edge counts
first-class. Surface imbalances as an event/alert; surface an unexpected amplification factor likewise.

### 11.5 Phasing note

The data plane slots in **after** the structure plane: it needs node/edge ids (phase 1 IR) to attach counts to.
Realistically it is a phase **4.5 / 6** concern — the `LineageRow` matrix is reusable immediately, the per-edge
routing counters and the unified key are the new work. It is deliberately *not* required for phases 1–3.

## 12. Boundaries, exceptions & known limitations (v1)

These are **deliberate non-goals for v1**, stated plainly so users and implementers are not surprised. Each notes
*why* and the *workaround / escalation*. None blocks the phased roadmap; several have a clear future path.

| # | Limitation (NOT in v1) | Why | Workaround / escalation |
|---|---|---|---|
| B1 | **No decoupled per-node scheduling** — within a flow a fast node cannot run ahead of a slow one. | Synchronous subgraph walk per cycle (D1). | Acceptable for batch; phase-2 per-edge queues (B2) decouple it. |
| B2 | **No inter-node queues / no per-edge back-pressure.** | "Topology over batch engine" (D1); the durable inbox is the only queue. | Phase-2: bounded **spill-to-disk** hand-off queues with high-water de-scheduling (§3.5). |
| B3 | **No live × live keyed join** (correlating two independently arriving feeds by key). | Needs a barrier + windowing that fights the batch/poll model. | Use `UNION` or join-against-reference (§3.4); or land both feeds and join in a later cycle. |
| B4 | **Adapter ingestion is at-least-once, not exactly-once.** | Land-then-ack: a crash between land and ack can re-land a window. | Downstream fingerprint dedup (`source.duplicate`) makes it effectively-once. |
| B5 | **No component version pinning** — `use:` always resolves to current on-disk content. | Keeps v1 simple; on-disk is the truth (D9). | To pin, copy a component under a new name. Future: `@version` / content-hash. |
| B6 | **Transform operators must be SQL-expressible** (DuckDB). | The operator set compiles to SQL (§3.4). | Arbitrary imperative per-record logic uses the existing **plugin ingester/transform SPI**, not the declarative operators. |
| B7 | **Flows are DAGs over `data` edges — no loops/cycles.** | Deterministic topological execution; cycles ⇒ non-termination (D10). | Iterative/recursive processing is out of scope; model as successive flows chained by `on_commit`. |
| B8 | **Per-flow trigger granularity, not per-node.** | Entry nodes are scheduled; the rest are data-driven (D6). | Split into multiple flows linked by `on_commit` if a mid-graph node needs its own schedule. |
| B9 | **No cross-branch (multi-sink) transactional commit.** | `(batch, branch)` commit isolates destinations (D8). | A clone may briefly have some branches committed, others retrying. All-or-nothing is a future opt-in. |
| B10 | **Full data-plane provenance ("select file → counts per path") is phase 4.5/6, not day-one.** | Needs the IR's node/edge ids + new per-edge counters (§11.3). | The `LineageRow` transform→write matrix is available immediately; routing-split counts come later. |
| B11 | **UI is read-first.** Visualisation lands in phase 4; create/test/CRUD-from-UI in phase 5. | Risk-ordered roadmap (§8). | Author flows/components as files until the editor ships. |
| B12 | **Multi-tenant scoping / RBAC of flows is out of scope here.** | The core is auth-free; security is an *edition* concern (future `inspecto-security`). | Editions layer authz over the `/flows` + `/components` API later; this design leaves the seam. |

**Key assumption that gates phase 1:** the legacy lift (D12) must represent *every* shipped `*_pipeline.toon`
capability in the IR. If a real config uses something the IR cannot express, that gap must be closed (or explicitly
excepted) before phase 1 is "done" — the existing test suite passing against the lifted path is the gate.

## 13. Open risks & corrections (2026-06-16 review)

A grounded review against the current engine found that several "thin shape over existing machinery" claims are
stronger than the code supports. **None changes a §9 decision or a §12 boundary** — they re-scope *cost and
sequencing* so the phased roadmap stays honest. Each correction is also threaded back into the relevant section
above.

| # | Sev | Claim as written | Reality in the code today | Correction / action |
|---|-----|------------------|---------------------------|---------------------|
| R1 | High | A new `transform.*` operator is "a registry addition, not engine surgery" (§3.4). | [`TransformCompiler`](../inspecto/src/main/java/com/gamma/etl/TransformCompiler.java) is **column-scalar only** (`DIRECT`/`EXPR`/`CONCAT_DT`/`FILENAME_DATE`); [`DataTransformer`](../inspecto/src/main/java/com/gamma/etl/DataTransformer.java) emits one fixed `SELECT … FROM <one source>` and returns **one** table. No `WHERE`/`CASE`-route/`QUALIFY`/`UNNEST`/multi-input exist. | Only `derive`/`select` are registry additions. `filter`/`route`/`validate`/`dedup`/`split`/`merge` need new SQL-assembly + a **multi-named-relation node-output contract** + chain-fusion. Re-scoped in §3.4 + §8 Phase 3. |
| R2 | High | `(batch, branch)` commit is "today's commit-log / ledger key extended" (§3.7). | [`CommitLog`](../inspecto/src/main/java/com/gamma/etl/CommitLog.java) keyed on `batch_id` only; ledger on `(sourceId, relPath)`; [`BatchProcessor.commit`](../inspecto/src/main/java/com/gamma/inspector/BatchProcessor.java) writes one output set and finalises the file in one crash-ordered sequence ("markers LAST"). No branch dimension, no partial-commit state. | New branch dimension across commit-log/ledger/markers/manifest **+** a partial-commit state **+** a `commit()` split (per-branch vs source-finalisation) preserving the ordering invariant. Re-scoped in §3.7 + §8 Phase 3. |
| R3 | Med | The topological walk "reuses `MultiSourceProcessor`'s virtual-thread fan-out for independent branches" (§3.3). | [`runConfigs`](../inspecto/src/main/java/com/gamma/inspector/MultiSourceProcessor.java) fans out **per `PipelineConfig`** (one isolated vthread per source). No intra-pipeline branch concept. | Branch scheduling is new; only the pool/permit pattern is reused. Corrected in §3.3. |
| R4 | Med-High | A `*_pipeline.toon` "auto-lifts into a 4-node linear `FlowGraph`" (§5). | A pipeline already fans into N schemas via `segments`/`selector` ([`rebuildStructural`](../inspecto/src/main/java/com/gamma/catalog/MetadataGraphService.java)); plugin ingesters emit multiple segment tables; **enrichment is a separate config file** ([`EnrichmentConfig`](../inspecto/src/main/java/com/gamma/enrich/EnrichmentConfig.java)). | Lift is a fan-out for multi-schema configs; decide pipeline↔enrichment join vs two-flows-linked-by-`on_commit`. Corrected in §5; gates Phase 1 (§8). |
| R5 | Low | "DAG over `data` edges; control edges excluded from the cycle walk" (D10) vs. `on_commit` feeding a downstream flow (§3.6). | An `on_commit` edge re-entering the same graph is a cycle the data-edge-only check won't catch. | `on_commit` is **cross-flow only**; validator rejects same-graph targets. Clarified in §3.2. |
| R6 | Med | The `ingest` job type is "what runs when" over a pipeline (§3.6) — implying a job can drive a pipeline. | [`IngestJob.run`](../inspecto/src/main/java/com/gamma/job/IngestJob.java) calls `MultiSourceProcessor.runAll(List.of(config),…)` — a **full pipeline re-run incl. acquisition**, over the *same inbox* the poll loop works, on a separate scheduler with no shared lock. This violates the §3.8 clean model (the pipeline *is* the ETL and owns ingest, poll-driven only). | **Decided 2026-06-17: remove the `ingest` job type.** Ingest/acquisition is pipeline-exclusive (loop scheduler); jobs are custom functions over stored data (custom-function scheduler), never a re-acquisition. Delete `IngestJob` + `JobType.INGEST`; migrate any `ingest` job to an `active:true` pipeline. See T23. |

**Credit (genuinely de-risked, no action):** the `MetadataGraphService` projection seam already iterates
`pipelines()` + `enrichments()` and understands multi-schema, so the §6 flow projection is additive; the
`ConfigRegistry` mtime-cache + `referencedFiles()` + `active:` gate already shipped, so Phase-2 dedup/reload builds
on real infrastructure; the `CommitLog` fsync + "markers LAST" ordering is a sound invariant worth preserving
(which is *why* R2 is expensive). The §11.4 conservation invariant is a real win but **not free** — it needs the
per-edge counters that don't exist yet (`LineageRow` is transform→write only), so it stays a Phase-4.5/6 item.

## 14. Things to do (implementation checklist)

Actionable, phase-aligned, derived from §8 + the §13 corrections. `[ ]` = not started.

### Phase 1 — Flow IR + legacy lift (the de-risker; gate before anything else)
- [x] **T1 (the gate, done 2026-06-17).** Capability-coverage checklist enumerating every `*_pipeline.toon` feature
  (single/`selector`/`segments` schemas, plugin ingester, CSV row-filters, partitions, post-action, gap, watermark,
  dedup modes, enrichment trigger) mapped to its IR representation — **the gate passed**; result is §15 (G1–G9, F1).
- [x] **T2 (done 2026-06-17).** `FlowGraph`/`FlowNode`/`FlowEdge` records + `FlowRel` + the `FlowNodeType`
  ServiceLoader SPI + `BuiltinNodeType`/`FlowNodeTypes` registry (`com.gamma.flow`). *(Revised 2026-06-17 — see T28.)*
- [x] **T3 (done 2026-06-17).** `PipelineLift`: single-schema → linear; **multi-schema/`segments`/`selector` →
  fan-out** (R4); edges implied from existing flags (post-action→success-side finalizer (§15 G8), gap→`gap`); adds
  `transform.filter` (G1) + distinct dedup nodes (G2); route metadata (G3); carries typed sub-records verbatim.
- [x] **T4 (decided 2026-06-17).** Pipeline↔enrichment/job boundary = **two separate flows, linked by declared
  data-store name** (not `on_pipeline` name-coupling): each `sink` declares the store it produces
  (`FlowStores.CONFIG_STORE`), each job/enrichment declares the store it consumes (`CONFIG_SOURCE_STORE`), and the
  producer→consumer topology is **derived** by matching store names (`FlowStores.superimpose`). So querying the
  configs/metadata alone reveals how a job is superimposed over a pipeline's output (the shared store is the join —
  §3.8). Done: `FlowStores` + lift now stamps every `sink` with its `store`; tests green. The job/enrichment **lift**
  itself (its own `FlowGraph` with a `source_store` entry) lands with the job-model work (T23/T27).
- [x] **T5a — lossless round-trip gate (done 2026-06-17).** `FlowCompiler.compile(FlowGraph)` recovers every
  engine input by grouping the lifted nodes by role; a `lift → compile` round-trip returns the **identical** typed
  objects (`assertSame`), proving the IR loses nothing. Full inspecto suite green (672 run, 0 failures) — Phase 1 is
  purely additive (only new `com.gamma.flow` + docs).
- [~] **T5b — execution-through-lift parity (single-schema done 2026-06-17; other shapes incremental).** Approach
  (decided): **compile-back-to-config**, not executor-driven — `FlowCompiler.toConfigMap(FlowGraph, schemaDir)`
  reverses the lift to a `PipelineConfig.fromMap`-shaped raw map (writing the stored schema map to a temp `.toon`);
  `FlowExecutionParityTest` runs a pipeline directly and via `lift → toConfigMap → fromMap → run`, asserting
  byte-identical **data output** (the `database/` partitions; the run-timestamped status/audit CSVs are excluded —
  the rebuilt config disables status, which doesn't affect data output). **Single-schema shape green.** Remaining
  (grow `toConfigMap`): **selector multi-schema, plugin segments, fixed-width text+binary, row-filter** — each adds
  its branch to the inverse + a parity case. (Why compile-back not executor-driven: the Phase-3 `FlowExecutor` is an
  additive *authored-operator* engine that runs on a seed relation and doesn't consume legacy lifted config, so
  driving the legacy suite through it would mean rebuilding the engine as a graph executor — out of scope.)

#### Phase-1 model refinement (2026-06-17 — sink family, categories, node identity; UI-ready)
- [x] **T28 (done 2026-06-17).** **Sink is a family + node taxonomy carries categories.** Added `NodeCategory`
  (`SOURCE`/`PARSE`/`TRANSFORM`/`SINK`/`CONTROL`) + `label`/`description` on `FlowNodeType`; split `sink` into
  **`sink.persistent`/`sink.materialized`/`sink.view`** (all category `SINK`); `FlowStores`/`FlowCompiler` now detect
  sinks **by category** (not the literal string) so subtypes + plugin sinks are uniform; `FlowStores.producedStores()`
  exposes `restsOnDisk()` (false for `sink.view`) for the deletion fence (§3.8) and viz. `PipelineLift` emits
  `sink.persistent`. (§3.1)
- [x] **T29 (done 2026-06-17).** **User node identity.** `FlowNode` carries a user-given `name` + `description`
  (may name a business object/concept); lifted nodes get derived defaults (sink name = its store). (§3.1)
- [x] **T30 (done 2026-06-17).** **Built-in processor definitions not deferred** — `FlowNodeTypes.catalog()` exposes
  every type's category/label/description/ports for the UI palette, so the read-first flow visualisation can land now.
- [x] **T31 (done 2026-06-17).** **Read-only flow visualisation** (pulled T16/T17 forward, §6). Backend:
  `com.gamma.flow.FlowProjection` (catalog / graph / summary — structural only) + read-only `GET /flows`,
  `GET /flows/node-types`, `GET /flows/([^/]+)/graph` in `ControlApi` (lift on demand). Frontend (`inspecto-ui`):
  `FlowsService` + a `flows/` pane (signals/OnPush) rendering lifted flows in the shared G6 `graph-view` component
  (category palette + node inspector), lazy route + nav item. Verified live against the shipped `voucher_unknown_etl`
  + `subscriber_etl` configs. Authoring/CRUD + per-node dry-run stay in Phase 5 (T18/T19).

### Phase 2 — Component registry + `use:` references (dedup lands here)
- [x] **T6 (done 2026-06-17).** `com.gamma.flow.ComponentRegistry.scan(registryRoot)` indexes
  `registry/<typeDir>/<name>.toon` by in-file identity `<type>/<name>` (type from dir; name from in-file
  `name:`/`id:`, else filename stem — reusing `ConfigRegistry`'s identity-vs-filename reconciliation, divergence
  logged). `resolve(use)` + `effectiveConfig(node)` = component content overlaid by the node's local overrides
  (the dedup). Types: connection/grammar/schema/**transform**/**sink** (latter two new). **Additive flow-layer only**
  — the legacy `*_pipeline.toon` loader is untouched (decided). 3 tests.
- [x] **T7 (primitive done; reload-loop → Phase 3).** `ComponentRegistry.referencedPaths(FlowGraph)` returns the
  component files a graph's `use:` refs resolve to — the set a flow cache folds into its mtime fingerprint (the
  `referencedFiles()` pattern). The edit-once **reload loop** wires in with flow-from-disk loading (Phase 3); no flow
  cache exists to drive it yet.
- [x] **T8 (scan/guard done; HTTP endpoint → Phase 5).** `com.gamma.flow.FlowReferences.referencedBy(ref, graphs)` /
  `isReferenced(...)` answer "what references this component?" by scanning `use:` — the safe-delete guard
  generalising `connectionInUse`. The `DELETE /components/{type}/{id}` 409-on-in-use endpoint lands with component
  CRUD (Phase 5, T19). 2 tests.

### Phase 3 — `*_flow.toon` authoring + topological executor (the heavy phase — sequence R1→R2→R3)
- [x] **T9 (R1a) — declarative contract enforced; runtime multi-output → T10/T12.** The *descriptor* of the
  node-output contract already shipped with T28–T30 (`FlowNodeType.emits()`/`accepts()`/`emitsNamedRoutes()`,
  populated on every `BuiltinNodeType` — e.g. `validate`→emits `{data,invalid}`, `parser`→`{data,unmatched}`+routes,
  `filter`→`{data,dropped}`), but it was **advisory**. T9 makes it **enforceable**: `FlowValidator` now checks
  **emit-side** (ERROR `ILLEGAL_EMIT` — an outbound rel must be in the source's `emits()`, or a `route:*` when it
  `emitsNamedRoutes()`) and **accept-side** (ERROR `ILLEGAL_ACCEPT` — a `data` edge's target must `accept` `data`;
  control/split outcomes routed to a handler are governed by the emitter, so handlers needn't list every inbound
  outcome — this is what lets the lift's `parser --unmatched--> quarantine sink` stay legal), plus a `UNKNOWN_TYPE`
  warning for unregistered types. Proven by `aRealLiftedPipelineValidatesClean` (every lifted edge honours the
  contract). The *runtime* production of multiple named relations (a node actually emitting `data`+`invalid` row-sets)
  is the SQL-assembly/executor work — **T10/T12**. 5 new tests (FlowValidatorTest now 13); suite 700 green.
- [x] **T10 (R1b, done 2026-06-17).** `com.gamma.flow.exec.RowShaper`: compiles a `transform.*` node to SQL over a
  DuckDB input relation, emitting **multiple named relations** — `filter`(`WHERE`→data/dropped), `validate`(→data/
  invalid), `route`(`case` first-match+default / `clone` overlapping → `route:<key>`), `dedup`(`QUALIFY`→data/
  duplicate), `split`(`UNNEST`), `map`/`select`/`derive` projection, `merge`(`UNION ALL BY NAME` / N-way join), plus
  `fuse()` chain-fusion of a linear filter+projection run into one `SELECT`. Reuses the `TransformCompiler` trust
  model. 9 tests vs embedded DuckDB. **Additive** — touches neither commit nor scheduling.
- [x] **T11 (R2, done 2026-06-17).** `com.gamma.flow.exec.BranchCommitLog` (durable, fsync-per-record, `(batch_id,
  branch)` + phase `BRANCH`/`SOURCE` = the **partial-commit state**, same contract as `CommitLog`) +
  `BranchCommitCoordinator` (commit per-branch, then source-finalisation — backup → **markers LAST** → ledger/
  watermark — gated on *all branches committed*, run **exactly once**). Idempotent + crash-safe: a replay skips
  committed branches and finalises without re-committing. A single-branch flow = today's sequence (legacy
  `BatchProcessor.commit` untouched; this drives the new executor path — T5b parity stays future). 3 tests.
- [x] **T12 (R3, done 2026-06-17).** `com.gamma.flow.exec.FlowExecutor`: `validateOrThrow` (T14) → Kahn topological
  walk (cross-flow `on_commit` excluded) → run each transform via `RowShaper` → **pull-model routing** of each
  produced relation along its edge → at sinks drive the `BranchCommitCoordinator` (each sink = a branch). Sequential
  first cut (independent-branch parallelism over the vthread pool = follow-up). Additive; starts from the parse
  stage's seed relation. 2 tests (route fan-out + idempotent replay). **Remaining Phase-3: T13 triggers, T5b parity.**
- [x] **T13 (done 2026-06-17 — model + mechanism; live-scheduler wiring is the follow-up).** Entry-node
  **triggers** (§3.6): `com.gamma.flow.FlowTrigger` parses `schedule`(every/cron) / `event`(on/from/coalesce) /
  `manual` / absent⇒DEFAULT_POLL and classifies the driving scheduler (LOOP/EVENT/MANUAL, §3.8). **Event coalescing**:
  `com.gamma.flow.exec.TriggerCoalescer` collapses an event storm into one non-overlapping run (current run + at most
  one follow-up; lost-wakeup-free) — the in-process form of the `ingestLock` debounce. **`adapter` land-then-ack**:
  `AdapterWindow` (max_records/max_bytes/max_age flush policy) + `FileLander` (temp→fsync→atomic rename→ack-LAST =
  at-least-once). **Per-node `enabled:`**: `FlowNode.enabled()` + `FlowExecutor` bypasses a disabled node (downstream
  reachable only through it goes inert; a disabled sink is not a branch). Additive; **wiring the trigger classes into
  the live `SourceService` poll/`Scheduler` loop + the stream-consumer runtime are the follow-up** (with T23's
  two-scheduler split). 16 tests (FlowTrigger 7 · AdapterWindow 3 · TriggerCoalescer 3 · FileLander 2 · executor +1).
- [x] **T14 (R5) — structural checks done; emit/accept-rel wiring → T9.** `com.gamma.flow.FlowValidator.validate(g)`
  returns a typed `Result` (`Issue`{`Severity` ERROR/WARNING, stable `code`, message}) so the executor + future
  authoring API can *reject* a broken graph (vs `ConfigValidator`'s warning-only model). Checks: DAG over `data`
  edges (DFS back-edge → `CYCLE`, names the path; control/split/`route:*` edges excluded, matching the walk),
  dangling endpoints (`DANGLING_FROM`/`DANGLING_TO`, `on_commit` `to` exempt = cross-flow), **same-graph
  `on_commit` rejected** (`ON_COMMIT_SAME_GRAPH`, R5), duplicate ids, no-entry-node, empty-graph (warning).
  `validateOrThrow` for the execution path. **Deferred:** validating a rel against a node type's emitted/accepted
  relations needs the node-output contract (T9) — there is a seam for it. 8 tests. Additive, full suite 695 green.
- [ ] **T15.** Adaptive back-pressure defaults (§3.5) as configurable, not hard-coded.

### Phase 4 — Flow-graph API + G6 visualisation (read-first)
- [ ] **T16.** `GET /flows/{id}/graph` projection → reuse the G6 renderer (solid `data` / dashed control edges).
- [ ] **T17.** Node inspector panel (effective config resolved through `use:`); live last-run overlay via `OverlaySource`.

### Phase 5 — Per-component dry-run/test + CRUD-from-UI (build-and-test UX)
- [ ] **T18.** `preview(sample)` per node type (reuse production logic, scratch-only); the `/components/{type}/{id}/test`
  + `/flows/{id}/dry-run` endpoints (§7.2).
- [ ] **T19.** Component + flow CRUD from the UI (generalise the write-root-gated, atomic, secret-masking connections pattern).

### Phase 4.5 / 6 — Data plane (provenance overlay; not required for 1–3)
- [ ] **T20.** Per-edge counters at every node boundary (`recordsIn`/`recordsOut`/`diverted` tagged by relationship) — §11.3.
- [ ] **T21.** Unified provenance key (correlation id + runId) joining acquisition events + lineage matrix + enrichment.
- [ ] **T22.** `GET /provenance?file=…` graph-shaped query + Sankey overlay on the G6 canvas; surface conservation-imbalance
  (§11.4) and unexpected amplification as events/alerts.

### Model — pipelines vs jobs (formalised §3.8; spans phases 1/3/4)
- [ ] **T23 (R6, decided).** **Remove the `ingest` job type** (`JobType.INGEST` + `IngestJob`) — ingest is
  pipeline-exclusive. Implement the **two-scheduler split** (§3.8): the **loop scheduler** drives pipeline nodes
  only; the **custom-function scheduler** ([`JobService`](../inspecto/src/main/java/com/gamma/job/JobService.java))
  drives jobs only (custom plugin functions over stored data). Migrate existing `ingest` jobs to `active:true`
  pipelines; drop `INGEST` from `JobType` and from `JobService.build`.
- [ ] **T24.** **Combined pipeline+job visualisation** (§3.8): join the pipeline `sink(store)` node and the job
  `source(store)` node at the shared table in the flow/lineage projection (§6), drawing `on_commit` as the
  producer→consumer edge — one topology, not two.
- [ ] **T25.** **Deletion fence on the shared store** (§3.8 rule 4): keep `maintenance`/delete jobs standalone;
  guarantee they are slice-disjoint or quiet-windowed, and surface a conflict warning/alert when a delete targets a
  slice with an active reader/writer. (Append/read on disjoint slices needs no fence.)
- [ ] **T26.** **Misfire / catch-up** (the one real Quartz gap, §3.8): on startup compare `lastRunOf(job)` against
  the cron schedule; if a fire was missed and the job is `catch_up: true`, submit one run immediately. A few lines
  over `CronExpression.next()` + the existing history — **no new dep, no Quartz.**
- [ ] **T27.** **Job-execution reporting**: project `JobRun` into a **DuckDB** table (mirror `DbStatusStore` /
  `EventStore`); expose query endpoints (success rate, p50/p95 duration, failure trends over time); build a **Jobs
  pane** in `inspecto-ui` reusing the shipped **Events/Activity viewer** template (ag-Grid + filter toolbar +
  live-tail + CSV export + detail dialog). The run data is already captured (`jobs_runs.csv`); this is the projection + UI.

## 15. Phase-1 capability inventory — gate result (T1, 2026-06-17)

The T1 capability sweep (full read of [`PipelineConfig`](../inspecto/src/main/java/com/gamma/etl/PipelineConfig.java)
+ `SchemaSelector`/`PartitionDef`/`TransformCompiler`/`ConfigValidator`/`config/spec` + the 5 shipped configs and
their referenced schema/grammar files) **passes the gate**: the `FlowGraph`/`FlowNode`/`FlowEdge` IR **can represent
every capability** a `*_pipeline.toon` expresses — **provided** the encodings below are adopted. It is **not** a
"4-node linear / thin reuse" lift (confirms §13).

**Structural fact that shapes the lift:** a `*_pipeline.toon` is **not self-contained**. The pipeline file holds
acquisition + parse + batch-write controls; the **transform vocabulary, field selectors and partitioning live in the
referenced `*_schema.toon`** (`raw.fields[]`, `mapping.rules[]`, `partitions[]`/`partitionKey`); **enrichment / alert
/ gap-consumer logic live in separate files**. The lift must read the pipeline file **and** its referenced schema
file(s) to populate the `parser`/`transform`/`sink` nodes.

**IR encodings adopted (deltas from §3 as written):**
| # | Capability | Encoding decision |
|---|---|---|
| G1 | CSV row-filters anchored on a **column index** (`filter_target_column` + include/exclude prefixes/regex) | a dedicated **`transform.filter`** node placed **between `parser` and `transform.map`** (index-anchored, pre-naming — cannot live in the name-keyed map). |
| G2 | **Two independent dedup subsystems** — `processing.duplicate_check` (marker/`MarkerManager`) **and** `source.duplicate` (fingerprint ledger) | keep **distinct**: `transform.dedup.marker` + `transform.dedup.fingerprint` (do **not** flatten into one "dedup" — they are different subsystems and can both be active). |
| G3 | `schemas[]` selector = stateful **file_pattern + column-count probe** (priority = declaration order) | a `parser` **dispatcher** emitting `route:<key>` edges, each carrying `{priority, file_pattern, column_count}`; `unmatched` → quarantine. Preserves first-match-wins + the max-column probe heuristic. |
| G4 | `incremental.watermark` is **derived** from the fingerprint ledger | `acquisition.config.incremental` **+** a validator cross-field rule (watermark set ⇒ a content-based `duplicate.mode`), mirroring today's warn. |
| G5 | **Plugin-ingester `segments`** (FQCN emits N named segment streams → N tables) — **highest risk** | `parser` with `use: ingester/<fqcn>` + `config.ingester_config`, emitting `route:<segment-key>` → per-segment `sink`. Fan-out is opaque to the graph (plugin owns keys at runtime) — the Phase-1 parity test must cover it. |
| G6 | `guarantee` is declarative with a runtime fallback (degrades to best-effort if no ledger) | `acquisition.config.guarantee` + carry the `requiresLedger()` warn; advisory, not a hard property. |
| G7 | Gap detection → `SEQUENCE_GAP` events | `acquisition` emits `gap` edge → a `gap` reporting node (no `data` out); sequence template → `gap.config.sequence`. Matches §3 as written. |
| **G8** | `post_action` RETAIN/DELETE/MOVE/RENAME/TAG (+ templated `archive_path`, `on_unsupported`) | a **success-side finalizer** on the `acquisition` node, **not** the `failure` edge — **§5/§8 wording corrected**. `on_unsupported` governs the failure branch; date-template resolves at run time; capability-checked vs the connector SPI (not fully lift-time validatable). |
| G9 | **Pipeline↔enrichment/alert/job file boundary** (they point *back* via `triggers.on_pipeline` = a name string) — risk | **DECIDED (T4, 2026-06-17): two separate flows linked by declared data-store name**, not `on_pipeline`. Each `sink` declares the store it produces, each consumer declares the store it reads; the topology is **derived by matching store names** (`com.gamma.flow.FlowStores.superimpose`) — config/metadata reveals the superimposition. Lifting a pipeline alone is correct; the consumer is a separate flow joined at the store. |
| F1 | **Dead top-level keys** `version:` / `search:` / `copy_tars:` / `backup:` (present in `adjustment_pipeline.toon`, **never parsed** by `fromMap`) | the lift **drops** them — do not faithfully reproduce keys that do nothing today. |

**Lift recipe (condensed; exact accessors in the T1 inventory):** load via `PipelineConfig.load` (resolves+validates
schemas); set `FlowGraph.name`←`identity().pipelineName()`, `active`←`active()`; build the `acquisition` node from
`source()` (+ `stability/duplicate/incremental/guarantee/fetch/retry/circuitBreaker/postAction`, `use: connection/…`
when `hasConnection()`); add `gap` node + edge if `gapDetection().active()`; parser base from `csv()` (+ `use:
grammar/…` when external, + `fixedwidth`); insert `transform.filter` when `csv().hasRowFilters()`; add the dedup
nodes per G2; sink base from `output()`+`dirs()`+batch caps — type **`sink.persistent`** (legacy only ever rests a
store; `sink.materialized`/`sink.view` are authored-only, §3.1) and **named after the store** it produces. **Then
branch on `schemas()`** (exactly one non-null):
**single** → one linear `parser→transform.map→sink`; **selector** → dispatcher + N route branches (G3); **segments**
→ plugin parser + N `route:<segment>` sinks (G5). **Cross-file suffix:** resolve `EnrichmentConfig`/`*_alert.toon`/
`job` whose `on_pipeline` == this pipeline (G9). Re-run the `ConfigValidator` warns on the lifted graph + the new
cross-field rules (G4, G6, `on_commit` acyclicity). Drop F1 keys.

**Parity gate (T5) must specifically cover the four non-linear / non-thin shapes:** the **voucher** multi-schema
selector, the **plugin-ingester segments** path, the **fixed-width** (text + binary) path, and the **row-filter**
path — these are where "lift = thin shim" breaks.

---

**Last Updated**: 2026-06-17
**Status**: design finalised (decisions §9, boundaries §12); **reviewed 2026-06-16 against the engine — corrections
+ re-scoping in §13, implementation checklist in §14; pipeline-vs-job execution model formalised 2026-06-17 in §3.8
(R6 / T23–T25).** **Phase 1 (Flow IR + legacy lift) is BUILT and green** (T1/T2/T3/T4/T5a done; full inspecto suite
675/0/1). **Refined 2026-06-17 (T28–T30, §3.1): sink is a family (`persistent`/`materialized`/`view`), node types
carry a `category`, nodes carry user `name`/`description`, built-in processor definitions exposed for the UI.** **T31 (read-only flow
visualisation — `FlowProjection` + `/flows` API + the `inspecto-ui` Flows pane) DONE 2026-06-17.**
Next: Phase 2 (component registry + `use:` refs, T6–T8).
