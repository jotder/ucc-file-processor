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

| Node `type` | NiFi analogy | Backed by today |
|---|---|---|
| `acquisition` | source processor | `source:` block, `SourceConnector` SPI (`com.gamma.acquire`), connection profiles, stability / dedup / watermark / fetch / retry / circuit-breaker / post-action |
| `adapter` | streaming/push source → micro-batch | **new** — extends `SourceConnector` SPI; windows a stream and **lands a file** (§3.6). The single bridge for non-file/event sources |
| `parser` | record reader | `csv_settings` / external grammar, `SchemaSelector`, fixed-width frontend, plugin ingesters (segments) |
| `transform.*` | update / route / split processors — **a chainable family, see §3.4** | [`DataTransformer`](../inspecto/src/main/java/com/gamma/etl/DataTransformer.java) + [`TransformCompiler`](../inspecto/src/main/java/com/gamma/etl/TransformCompiler.java) (SQL-expr registry), `CsvSettings` row-filters |
| `merge` | join/union (fan-in) processor | **new** — SQL over predecessor outputs as relations (§3.4); generalises `EnrichmentConfig` join-against-reference |
| `enrichment` | join/lookup processor | [`EnrichmentConfig`](../inspecto/src/main/java/com/gamma/enrich/EnrichmentConfig.java) (references, triggers, stage-2 join) |
| `sink` | put processor | `Output` (CSV / Parquet / DuckLake), DB export, future push targets |
| `alert` | reporting task | `AlertService` (`*_alert.toon` rules) |
| `gap` | reporting task | `GapDetector` → `SEQUENCE_GAP` event |
| `event` | notification | `EventLog` / `EventStore` |

New node types are added by registering a `FlowNodeType` provider (ServiceLoader), mirroring how
`SourceConnector` and `DescriptionProvider` are discovered — so editions/plugins can contribute nodes.

### 3.2 Edges carry a relationship

An edge is `{ from: <nodeId>, rel: <relationship>, to: <nodeId> }`. `rel` defaults to `data`.

- **`data`** — the record-set (batch) flows downstream. This is the normal acquire→parse→transform→sink chain.
- **control relationships** — route a batch on an outcome, making side-paths first-class instead of buried flags:
  - `success` / `failure` — terminal batch outcome (failure → quarantine / dead-letter node).
  - `unmatched` — parser could not match a schema/column-count (→ quarantine or a fallback parser).
  - `gap` — sequence-gap detected (→ `gap`/`alert` node).
  - `on_commit` — batch committed (→ `enrichment` / downstream flow trigger).
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
  batch(es), and routes outcomes along control edges. This reuses `MultiSourceProcessor`'s virtual-thread fan-out
  for independent branches.
- **Gating:** `active:` (already implemented, top-level, default `false`) arms the whole flow; a future per-node
  `enabled:` mirrors NiFi start/stop of a single processor.
- No inter-node queues in v1 — a batch is processed to completion through its reachable subgraph within the cycle,
  exactly as today. Back-pressure still exists, but it is **admission-based at the source**, not queue-based (§3.5).

### 3.4 Transform is a chainable family of record operators

`transform` is **not one node** — it is a family of single-purpose, **field-level** record operators that chain
freely (`parse → filter → derive → route → …`), so a flow can carry **many** transformations, in any order, each
independently authored and testable. Every operator works over the batch's **record fields (columns)** and
**compiles to a DuckDB SQL fragment** — `TransformCompiler` already models transform types as SQL-expression
functions in a registry, so a new operator is a registry addition, not engine surgery.

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

The committable unit is **`(batch, branch)`** — today's commit-log / ledger key extended with the destination
branch/sink id. For a `route` **clone** where one branch's sink fails, the v1 behaviour is:

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
- **Legacy lift** — at load time a `*_pipeline.toon` is *auto-lifted* into a 4-node linear `FlowGraph`
  (`acquisition → parser → transform → sink`), plus the control edges its existing flags imply (post-action →
  `failure` edge, gap-detection → `gap` edge, enrichment trigger → `on_commit` edge). **No file rewrite** — the
  lift is internal.
- **Compile-back** — the executor compiles a `FlowGraph` (lifted or authored) back to the *exact* primitives
  `SourceProcessor` runs today, so old configs are byte-for-byte equivalent in behaviour. Parity is proven by
  running the existing suite against the lifted path.
- **Coexistence** — [`ConfigRegistry`](../inspecto/src/main/java/com/gamma/service/ConfigRegistry.java) (now
  mtime-cached) indexes both `*_pipeline.toon` and `*_flow.toon`; `MetadataGraphService` projects both.

Net: the graph model is a **new layer over the existing engine**, never a rewrite of the engine.

## 6. Visualisation (reuse the G6 component already shipped)

The UI already renders the catalog graph with G6 (`inspecto-ui/.../graph-view.component.ts`, reused in
object-detail). Add a **flow projection**:

- `GET /flows/{id}/graph` → `{ nodes: [{id,type,status}], edges: [{from,to,rel}] }` → the **same** G6 renderer,
  new data source. Edge style = relationship (solid `data`, dashed control), matching the design diagram.
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
   `*_pipeline.toon` into it; compile back to today's execution. Prove parity against the existing suite.
   *No behaviour change.*
2. **Component registry + `use:` references.** Extract `transforms/` and `sinks/` component types; generalise the
   connection/grammar/schema reference resolution; extend the `referencedFiles()` mtime-watch to all referenced
   components. **Dedup lands here.**
3. **`*_flow.toon` authoring + topological executor** with `success`/`failure`/`unmatched`/`gap`/`on_commit`
   routing (replaces buried flags with wired edges), **entry-node triggers** (schedule/cron/event/manual, §3.6)
   with event-coalescing, and **`(batch, branch)` commit semantics** (§3.7). Per-node `enabled:`.
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

---

**Last Updated**: 2026-06-16
**Status**: design finalised (decisions §9, boundaries §12); phase 1 (Flow IR + legacy lift) is the next
implementation step.
