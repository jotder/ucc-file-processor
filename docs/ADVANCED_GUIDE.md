# Inspecto — Advanced Operations & Internals Guide

> **Audience:** engineers and operators investigating production behaviour. This is the deep, "nitty-gritty"
> reference: how each component actually works, what it emits (events + metrics), what state it writes to disk,
> which `-D` flags govern it, and how to diagnose issues. For *config authoring* see
> [`configuration.md`](configuration.md); for the *design rationale* of the flow engine see
> [`flow-graph-design.md`](flow-graph-design.md); for *acquisition feature depth* see
> [`data_acquisition_framework.md`](data_acquisition_framework.md); for the *edition/build model* see
> [`EDITIONS.md`](EDITIONS.md). Short, focused fixes live in [`troubleshooting.md`](troubleshooting.md).

---

## 0. How to maintain this document (living-doc protocol)

**This file is the source of truth for "how the running system behaves." It MUST be kept current as code
changes — nobody can remember all of this.** When you implement or change anything below, update the relevant
section in the *same* change:

| If you change… | Update section |
|---|---|
| A `com.gamma.event.EventType` (add/emit/attrs) | §6 Event catalog + the component's "Events" line |
| A `MetricRegistry` metric (name/labels) | §7 Metrics catalog |
| A persisted artifact (CSV ledger, DuckDB table, dir layout) | §8 Persistence & state |
| A `System.getProperty(...)` flag | §9 Config flags |
| A `ControlApi` route | §10 API routes |
| A component's process/failure behaviour | §5 the component deep-dive + §11 a playbook if it has a new failure mode |

Keep reference sections as **tables** (scannable under pressure). Prefer correcting an existing row over
appending. When a behaviour is removed, delete its row — never leave stale entries. Line refs (`File.java:NN`)
drift; treat them as hints, confirm against code. **Last verified against code: 2026-06-18.**

---

## 1. What Inspecto is (one paragraph)

A framework-free Java 24 (build JDK 26) ETL / file-processing platform: it **acquires** files (local or remote),
**parses + validates + transforms** them with DuckDB into Hive-partitioned Parquet/CSV, and exposes an
**operational-intelligence** layer (events, metrics, alerts, managed cases/issues) over a small JDK-`HttpServer`
Control API + Angular UI. No Spring/Quarkus; DI is manual constructors; optional capability is `ServiceLoader`
SPI; editions are build flavors, never branches (see [`EDITIONS.md`](EDITIONS.md)).

---

## 2. Topology — modules, processes, ports

### Maven modules (dir ≠ artifactId; dirs renamed 2026-06-12)
| Dir | artifactId | Role |
|---|---|---|
| `inspecto/` | `file-processor` | Lean engine + control plane. No network/AI deps. The fat JAR. |
| `inspecto-agent/` | `file-processor-agent` | Assist skills (failure diagnosis etc.) on `agent-kernel`. Optional. |
| `inspecto-agent-hosted/` | `file-processor-agent-hosted` | Hosted model providers (langchain4j). Optional. |
| `inspecto-connectors/` | `file-processor-connectors` | SFTP/FTP/FTPS/DB-export connectors. ServiceLoader-discovered; optional. |
| `inspecto-ui/` | (npm) | Angular SPA (served as static files by the engine when `-Dui.dir` is set). |

### Processes / entry points
- **`com.gamma.service.SourceService`** — the long-running host: poll loop + Control API + (optional) UI on
  **`:8080`** (`-Dcontrol.port`). This is "the server" in production.
- **`com.gamma.inspector.SourceProcessor`** — one-shot ETL of a single config (CLI / embedded use).
- **Every JVM launch needs `--enable-native-access=ALL-UNNAMED`** (DuckDB JNI) — including tests.

### Editions
One SemVer version spans all editions; artifacts differ by classifier/build profile. The master/common core is
**auth-free**; Standard/Enterprise add an `Authenticator` SPI etc. as build flavors. Do not branch on edition in code.

---

## 3. Runtime architecture & the concurrency model

This is the single most important section for diagnosing "stuck", "deadlock", "duplicate run", and "why didn't
my downstream fire" problems.

### The poll loop (`SourceService.runAllOnce`)
One cycle, under a single **`ingestLock` (`ReentrantLock`)** held for the whole cycle:
1. **Config rebuild** — `ConfigRegistry.rebuild(registry)`: mtime-cached re-index of `*_pipeline.toon`. Re-parses
   only changed files (+ their referenced schema/grammar/segment files). Steady-state cycles do no parse I/O.
2. **Filter to runnable set** — skip pipelines that are *paused*, `active: false` (default false = opt-in), or
   not due this tick (`dueThisTick` evaluates the `FlowTrigger`: interval/cron/event/manual). Each survivor gets
   `cfg.forNewRun()` (cheap timestamp re-stamp, no re-parse).
3. **Mark running** — add names to the `running` set (`ConcurrentHashMap.newKeySet()`), backing the "under
   processing" signal of `/pipelines/{name}/pending`.
4. **Fan out** — `MultiSourceProcessor.runConfigs(...)`: a **virtual-thread pool** + `Semaphore(maxConcurrentRuns)`
   runs each config's `SourceProcessor.run`.
5. **Unmark running**, **sync status DB** (`syncStatus()` → `DbStatusStore` if enabled), **release `ingestLock`**.

Two more counters: `inspecto_poll_cycles_total`, `inspecto_active_runs`.

### Schedulers — there are two, deliberately (§3.8 of the design)
- **Loop scheduler** — the poll cycle above. Pipelines (ingest) live here *exclusively*. There is **no `ingest`
  job type** (removed T23); migrate any "ingest job" to an `active: true` pipeline.
- **Job scheduler** (`JobService`) — cron / event / manual for *downstream* work over data at rest
  (enrich / report / maintenance / **flow**). Home-grown `Scheduler` + `CronExpression`; Quartz was rejected.

### The bus is SYNCHRONOUS — the #1 deadlock trap
`BatchEventBus.subscribe(handler)` runs the handler **inline on the publishing thread**. The committing batch's
virtual thread publishes a `BatchEvent` *while `ingestLock` may be held*. Therefore **a subscriber must be fast
and must never run another ingest inline** — that would deadlock on `ingestLock`. Both real subscribers hand off:
- `JobService.onBatchEvent` → submits to its own vthread executor.
- `SourceService.onUpstreamCommit` (event-triggered flows) → hands off to **`triggerWorkers`** (a third vthread
  pool) via `TriggerCoalescer.signal()`.
If you add a bus subscriber that does real work, **hand off to an executor**; never block, never re-enter ingest.

### Thread pools at a glance
| Pool | Owner | Bound | Purpose |
|---|---|---|---|
| outer fan-out | `MultiSourceProcessor` | `Semaphore(maxConcurrentRuns)` | concurrent sources per cycle |
| inner fan-out | `SourceProcessor` | `Semaphore(processing.threads)` | concurrent batches per source |
| `triggerWorkers` | `SourceService` | vthread-per-task | event-triggered downstream flows (off-bus) |
| `workers` | `JobService` | vthread-per-task | job executions (off-bus); per-job non-overlap via `LockingRunner` |

---

## 4. Lifecycle of a file (end-to-end) + the commit ordering invariant

`SourceProcessor.run(cfg, onCommit)` → `collect()`:
1. **Stale-marker cleanup** — `MarkerManager.cleanupStaleMarkers`.
2. **Discover** — `connector.discover(ctx)` wrapped in `RetryPolicy`; remote sources gated by
   `CircuitBreaker.shared()` (trip → skip + `SOURCE_CIRCUIT_OPEN`).
3. **Stability gate** — `StabilityGate.shared().filter(...)`: hold back files not yet quiescent (size/age checks);
   emit `FILE_STABLE` for newly stable. Gauge `inspecto_files_waiting_stability`.
4. **Gap detection** — `GapDetector` + `GapTracker.shared()` over the *full* discovered listing → `SEQUENCE_GAP`
   (fire-once per gap), `inspecto_sequence_gaps_total`.
5. **Watermark filter** — `AcquisitionLedgers.shared().highWatermark(sourceId)` drops files with mtime **strictly
   <** the watermark *before* fetch (no bandwidth on old remote objects). `inspecto_watermark_skipped_total`.
6. **Materialize / fetch** (remote) — per-file `fetchAndVerify` (retry + `IntegrityChecker`); corrupt downloads →
   `QuarantineManager` (`REASON_CORRUPT_DOWNLOAD`); source post-action (`DELETE`/`MOVE`/`RENAME`) via
   `connector.post()` → `FILE_ARCHIVED`. Events `FILE_DISCOVERED`/`FILE_FETCHED`/`FILE_VALIDATED`/`FILE_FETCH_FAILED`.
7. **Dedup** — PATH mode → `MarkerManager.isAlreadyProcessed`; content mode → `AcquisitionLedger.find` +
   `DuplicatePolicy.decide`. `inspecto_duplicates_skipped_total`.
8. **Batch planning** — `BatchPlanner.plan()` groups survivors by schema into `Batch`es.
9. **Batch processing** — `BatchProcessor.process(batch, cfg, audit)` (inner vthread pool).

### Commit ordering invariant (`BatchProcessor.commit`) — crash-safety
Order matters; a crash between any two steps must leave the batch re-runnable, never half-committed-as-done:
1. **DuckLake register** (optional, non-fatal).
2. **Manifest write** (`ManifestStore`) — the reprocess anchor.
3. **Backup originals** out of the inbox.
4. **Marker files LAST** (`MarkerManager` `*.processed`) — only after 1–3 are durable. Crash before this ⇒ no
   sentinel ⇒ the still-present inbox file is re-picked next cycle.
5. **Fingerprint ledger LAST** (`AcquisitionLedger.record`) — content dedup; same stranding-safety rationale.
6. **DB-export watermark LAST** (`recordDbWatermark`) — advances only after durability ⇒ resumable DB export.
All outputs are `OVERWRITE_OR_IGNORE` ⇒ the whole sequence is idempotent on rerun.

---

## 5. Component deep-dives

Each sub-section: **Responsibility · Process · Events · Metrics · State · Config · Failure modes**.

### 5.1 Acquisition (`com.gamma.acquire`, `com.gamma.inspector`, `inspecto-connectors`)
- **Responsibility:** discover + fetch + validate + dedup source files into the poll staging tree.
- **Process:** §4 steps 2–7. Connectors are `SourceConnector` SPI (`local`, `sftp`, `ftp`, `ftps`, `db`),
  ServiceLoader-discovered; remote ones live in `inspecto-connectors`. Reusable `*_connection.toon` profiles
  resolved via `ConnectionRegistry`/`SecretResolver` (secret schemes incl. `SYS:<key>`).
- **Events:** `FILE_DISCOVERED`, `FILE_STABLE`, `FILE_FETCHED`, `FILE_VALIDATED`, `FILE_FETCH_FAILED`,
  `FILE_CHANGED`, `FILE_ARCHIVED`, `SEQUENCE_GAP`, `SOURCE_CIRCUIT_OPEN`.
- **Metrics:** `inspecto_files_discovered_total`, `_files_downloaded_total`, `_downloads_failed_total`,
  `_bytes_transferred_total`, `_fetch_seconds`, `_active_connections`, `_files_waiting_stability`,
  `_watermark_skipped_total`, `_duplicates_skipped_total`, `_sequence_gaps_total`, `_post_actions_failed_total`.
- **State:** acquisition ledger (in-mem or `inspecto-acquisition.db` tables `inspecto_acquisition_ledger` +
  `inspecto_acquisition_db_watermark`); quarantine dir; markers dir.
- **Config:** `source.*` in the pipeline TOON (connection, incremental.watermark, fetch.parallel_fetch,
  rate_limit, retry, circuit_breaker, post_action, guarantee); `-Dacquire.ledger.backend`.
- **Failure modes:** circuit open (repeated discover failures), corrupt download (quarantined), watermark skipping
  legitimate files (needs a content `duplicate.mode`), stability gate holding a slow-growing file. See §11.

### 5.2 ETL batch pipeline (`com.gamma.etl`, `com.gamma.inspector`)
- **Responsibility:** turn a planned `Batch` into committed partitioned output with full audit + lineage.
- **Process:** `BatchProcessor.process` → a `BatchIngestStrategy` (CSV streaming, fixed-width, plugin segments,
  selector multi-schema) → DuckDB transform → `PartitionWriter` (Hive partitions, `OVERWRITE_OR_IGNORE`,
  excludes internal `__src_id`) → `commit()` ordering invariant (§4).
- **Events:** `BATCH_COMMITTED`, `BATCH_FAILED` (attrs: `batchId`, `outputRows`, `durationMs`, `rejectedCount`,
  `partitions`, and on failure `error`/`offendingFile`/`errorRows`). Published on the **synchronous** bus.
- **Metrics:** `inspecto_batches_total{status}`, `_batch_duration_seconds`, `_output_rows_total`,
  `_partitions_written_total`, `_rejected_files_total`; gauges `_committed_batches`, `_paused`,
  `_quarantine_files`, `_inbox_oldest_seconds`.
- **State:** `database/` output tree; `BatchAuditWriter` CSVs (`_status_/_batches_/_lineage_`); manifests dir;
  `CommitLog`; backup dir; quarantine + per-file error CSVs (`errors/`).
- **Config:** `processing.*`, `schema`/`grammar`/`segments`, `csv_settings`, `dirs.*`, `duplicate.mode`.
- **Failure modes:** field/schema mismatch → quarantine (`field_mismatch`); unreadable → `unreadable`;
  `year=NULL` partitions (see [`troubleshooting.md`](troubleshooting.md)); a `BATCH_FAILED` with `offendingFile`.

### 5.3 Flow engine (`com.gamma.flow`, `com.gamma.flow.exec`)
- **Responsibility:** NiFi-style pipeline-as-graph. Two faces: (a) **read-only projection** of lifted pipelines
  for visualisation; (b) **authored flows** (`*_flow.toon`) that are CRUD-able, dry-runnable, and (T32) executable.
- **Process:** `PipelineLift` lifts a legacy `PipelineConfig` → `FlowGraph` (lossless). `FlowValidator` rejects
  broken graphs (cycles, dangling, illegal emit/accept, same-graph `on_commit`). `FlowExecutor` does a Kahn topo
  walk: each `transform.*` runs through `RowShaper` (filter/validate/route/dedup/split/map/select/derive/merge →
  multiple named relations) and each sink is a **branch** committed via `BranchCommitCoordinator` +
  `BranchCommitLog` (idempotent multi-branch commit; replay skips committed branches). `FlowDryRun` runs a bounded
  sample on a throwaway DuckDB (no commit). Component registry (`ComponentStore`/`ComponentRegistry`) holds
  reusable `grammar`/`schema`/`transform`/`sink` components referenced via `use:`.
- **Live execution (T32 — flows as jobs):** an authored flow is **job-style** (reads one or more `source_store`s,
  writes a sink `store`). `FlowJobRunner` (a `JobType.FLOW` job) seeds **each** `source_store` as its own view
  (`SourceStoreReader`) — a `transform.merge` joins/unions them (multi-source, Phase C) — drives `FlowExecutor`, and
  writes sinks via `PartitionSinkWriter` (unpartitioned single-file COPY when a sink declares no `partitions`;
  `sink.view` writes no bytes — instead the job registers a durable `ViewDefinition` under `<write-root>/views/`
  (store + flow + source_store lineage) for a KPI/report/alert API to bind to). Idempotent re-run via a stable `batch_id`.
- **Events:** none of its own yet (flow runs publish a `BatchEvent` via `FlowJobRunner` like any job; flow area is
  otherwise un-instrumented — see §7 Gaps).
- **State:** `<write-root>/flows/<id>.toon`, `<write-root>/registry/<typeDir>/<id>.toon`; per-run branch-commit log
  under the jobs audit dir.
- **Config:** authored flows + a `type: flow` `*_job.toon` (`flow:` id, optional `data_dir`/`batch_id`).
- **Failure modes:** flow job writes nothing on idempotent replay (same `batch_id` → "0 file(s)"); no
  `source_store` declared (rejected; ≥1 required, multiple supported since Phase C); fail-closed if no `-Dassist.write.root`.

### 5.4 Jobs (`com.gamma.job`) + the deletion fence
- **Responsibility:** scheduled/triggered downstream work. Types: `ENRICH`, `REPORT`, `MAINTENANCE`, `FLOW`.
- **Process:** `JobService` builds a `Job` per enabled `*_job.toon`. Triggers: cron (armed on the borrowed
  `Scheduler`), event (`on_pipeline` → bus, off-bus handoff), manual (`POST /jobs/{name}/trigger`). Per-job
  non-overlap via `LockingRunner` (a concurrent fire while in-flight records `SKIPPED`). **Catch-up (T26):** on
  startup an enabled `catch_up: true` cron job whose last audited run missed a fire runs once.
- **FLOW chaining (T32 Phase B):** a `FLOW` job is a first-class participant — cron / `on_pipeline` / manual fire it
  like any job, and on success `FlowJobRunner` publishes a `BatchEvent(jobName)` so downstream `on_pipeline` jobs
  chain off it. **Guidance:** when a flow reads a store a pipeline writes, trigger it with `on_pipeline: <producer>`
  rather than a time cron, so it runs only after the producer's commit is durable (avoids a half-written read).
- **Deletion fence (T25 × T32):** before a `MAINTENANCE` job that declares `store:` deletes, `fenceDelete`
  consults the guard. `SourceService.checkDeletion` reports a conflict when a target store has an **active**
  producer/consumer — built from lifted pipelines **and** authored flows, with the active set = running pipelines
  **∪ in-flight FLOW jobs** (`JobService.runningFlows()`). Emits `STORE_DELETE_CONFLICT`. *Note:* the fence is made
  *aware of* flow jobs; a flow job does **not** call `guard.check` itself (that would false-positive on normal
  concurrent read/append).
- **Events:** `STORE_DELETE_CONFLICT` (+ the lifecycle constants `JOB_STARTED/SUCCEEDED/FAILED` are declared but
  not yet emitted — runs are recorded to audit + the run store, not the event log).
- **Metrics:** `inspecto_jobs_total{job,type,status}`, `inspecto_job_duration_seconds{job}`.
- **State:** `jobs_audit/jobs_runs.csv`; optional `inspecto_job_runs` DuckDB table (`-Djobs.backend=duckdb`).
- **Config:** `*_job.toon` (`name/type/cron/on_pipeline/enabled/catch_up` + type params); `-Djobs.*`, `-Ddata.dir`.
- **Failure modes:** job not firing (cron/event/catch-up — see §11); `SKIPPED` (previous run in flight); flow job
  fail-closed without write root.

### 5.5 Operational Intelligence — events, alerts, objects
- **Events (`com.gamma.event`):** `EventLog.global()` singleton; `emit()` appends + counts
  (`inspecto_events_total`) + notifies subscribers (inline). Backends: in-memory ring (default, cap 8192) or
  durable Parquet (`-Devents.backend=parquet`, root `-Devents.dir`). Queried via `EventQuery` (same `matches`
  drives in-mem + SQL). SLF4J records are captured as `LOG` events via an appender.
- **Alerts (`com.gamma.alert`):** `AlertRule` (`*_alert.toon`: metric ∈ {error_rate, failed_batches,
  rejected_files, duration_ms}, comparator, threshold, window `Ns/m/h/d` or `Nb`, severity, onPipeline).
  `AlertService` evaluates on every terminal `BatchEvent` over the ledger window, with a per-rule cooldown; on
  breach emits `ALERT_FIRED` and (if `ObjectService` wired) opens a managed `ALERT` object (dup-suppressed).
- **Objects (`com.gamma.ops`):** managed Cases/Issues/Alerts with a workflow lifecycle, comments, attachments,
  RCA, and correlation links/graph. `EventObjectBridge` promotes `SEQUENCE_GAP` events → `ALERT` objects
  independently of alert rules. Correlation id is the join key between the object engine and the event timeline.
- **Events:** `ALERT_FIRED`, `OBJECT_OPENED`, `OBJECT_ACTIVITY`, `OBJECT_SLA_BREACH`, `OBJECT_LINKED`,
  `OBJECT_NOTE`.
- **State:** event ring or Parquet (`inspecto-events/`); objects/links/notes in-mem or DuckDB
  (`-Dobjects.backend=db`, three separate db files — DuckDB single-writer).

### 5.6 Control API + UI (`com.gamma.control.ControlApi`)
- **Responsibility:** ~80 JDK-`HttpServer` routes (auth-free in the common core), vthread executor, CORS locked to
  `-Dcontrol.cors`, optional static UI from `-Dui.dir`. Write endpoints are **fail-closed**: 503 unless
  `-Dassist.write.root` is set (the path-jail root for connections/flows/registry/config writes).
- **Reference:** §10. Health: `/health`, `/ready`. Scrape: `/metrics`.

### 5.7 SQL sandbox + assist agent
- **SQL sandbox (`com.gamma.sql`):** bounded DuckDB for KPI/oracle queries — `-Dassist.sql.memory_limit` (1GB),
  `assist.sql.threads` (2), `assist.sql.timeout_seconds` (30).
- **Assist agent (optional modules):** failure diagnosis + skills under `/assist/*` (503 if the agent module is
  absent). Hosted model settings are masked.

---

## 6. Reference — Event catalog (`com.gamma.event.EventType`)

`EventType` is a constants class; `Event.type` is a free-form string (these are conventions, not a closed enum).
`Event` fields: `eventId, ts(ms), level, type, source, pipeline, correlationId, message, attributes(Map)`.

| Type | When | Key attributes | Emitter |
|---|---|---|---|
| `SERVICE_STARTED` | host fully started | pipelines, pollSeconds, maxConcurrentRuns | SourceService |
| `PIPELINE_REGISTERED` | config loaded+activated | configPath, activePipelines | SourceService |
| `PIPELINE_PAUSED` / `PIPELINE_RESUMED` | via API | — | SourceService |
| `BATCH_COMMITTED` | batch success | batchId, outputRows, durationMs, rejectedCount, partitions | SourceService/bus |
| `BATCH_FAILED` | batch failure | + error, offendingFile, errorRows | SourceService/bus |
| `FILE_DISCOVERED` | connector listed a candidate | file | SourceProcessor |
| `FILE_STABLE` | passed readiness gate | file | SourceProcessor |
| `FILE_FETCHED` | bytes retrieved | file, bytes | SourceProcessor |
| `FILE_VALIDATED` | integrity ok | file | SourceProcessor |
| `FILE_FETCH_FAILED` | fetch/integrity failed | file | SourceProcessor |
| `FILE_CHANGED` | known path, changed content | file | SourceProcessor |
| `FILE_ARCHIVED` | source post-action done | file, action | SourceProcessor |
| `SEQUENCE_GAP` | expected file missing | expected, sequence, unit | SourceProcessor → also `EventObjectBridge` → ALERT object |
| `SOURCE_CIRCUIT_OPEN` | breaker tripped | source | SourceProcessor |
| `STORE_DELETE_CONFLICT` | delete races active flow | store, activeProducers, activeConsumers | SourceService |
| `ALERT_FIRED` | alert-rule breach | rule, metric, value, severity | AlertService |
| `OBJECT_OPENED` | managed object created | objectId, objectType, status, severity | ObjectService |
| `OBJECT_ACTIVITY` | workflow transition | objectId, objectType, from, to, action, actor | ObjectService |
| `OBJECT_SLA_BREACH` | issue past dueAt | objectId, ..., assignee, dueAt, overdueMs | ObjectService |
| `OBJECT_LINKED` | objects correlated | objectId, from, to, relationship, actor | ObjectService |
| `OBJECT_NOTE` | comment/attachment added | objectId, noteId, noteKind, author | ObjectService |
| `LOG` | captured SLF4J record | (log fields) | EventStoreAppender |

**Declared but not yet emitted (reserved vocabulary):** `FILE_RECEIVED`, `FILE_QUARANTINED`, `CONFIG_VALIDATED`,
`JOB_STARTED`, `JOB_SUCCEEDED`, `JOB_FAILED`, `ENRICHMENT_RUN`. Don't rely on these in alerting yet.

---

## 7. Reference — Metrics catalog (`/metrics`, Prometheus)

`MetricRegistry.global()`; counters/gauges/histograms keyed by name+labels. Histograms use second-scale buckets.
`/metrics` = full Prometheus text; `/metrics/acquisition` = JSON subset (the 9 acquisition metrics) for the UI.

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `inspecto_files_discovered_total` | counter | pipeline | files listed by discovery |
| `inspecto_files_downloaded_total` | counter | pipeline | fetched + validated |
| `inspecto_downloads_failed_total` | counter | pipeline | fetch/integrity failures |
| `inspecto_post_actions_failed_total` | counter | pipeline | source post-action failures |
| `inspecto_bytes_transferred_total` | counter | pipeline | bytes fetched (cumulative) |
| `inspecto_fetch_seconds` | histogram | pipeline | per-file fetch time |
| `inspecto_active_connections` | gauge | pipeline | open connector sessions now |
| `inspecto_files_waiting_stability` | gauge | pipeline | files held by the stability gate |
| `inspecto_watermark_skipped_total` | counter | pipeline | files skipped by the high-watermark |
| `inspecto_duplicates_skipped_total` | counter | pipeline | content-duplicate rejects |
| `inspecto_sequence_gaps_total` | counter | pipeline | newly detected missing files |
| `inspecto_batches_total` | counter | pipeline, status | terminal batches by outcome |
| `inspecto_batch_duration_seconds` | histogram | pipeline | batch wall time |
| `inspecto_output_rows_total` | counter | pipeline | rows written by committed batches |
| `inspecto_partitions_written_total` | counter | pipeline | partitions written |
| `inspecto_rejected_files_total` | counter | pipeline | files quarantined within a batch |
| `inspecto_committed_batches` | gauge | pipeline | total durable batches |
| `inspecto_paused` | gauge | pipeline | 1 if operator-paused |
| `inspecto_quarantine_files` | gauge | pipeline | files currently in quarantine |
| `inspecto_inbox_oldest_seconds` | gauge | pipeline | age of oldest unprocessed inbox file (lag) |
| `inspecto_poll_cycles_total` | counter | — | poll cycles run |
| `inspecto_active_runs` | gauge | — | source runs active at cycle start |
| `inspecto_jobs_total` | counter | job, type, status | job executions |
| `inspecto_job_duration_seconds` | histogram | job | job wall time |
| `inspecto_enrichment_recomputes_total` | counter | job, trigger | enrichment recomputes |
| `inspecto_enrichment_duration_seconds` | histogram | job | enrichment wall time |
| `inspecto_enrichment_failures_total` | counter | job | enrichment failures |
| `inspecto_events_total` | counter | level, type | events appended to the log |

**Gaps (not instrumented):** the **flow engine** (dry-run, registry, flow-job internals beyond the generic job
metrics), **connectors** (counters are pushed through `SourceProcessor`, not the connector), and **HTTP** (no
per-route latency/count). Add metrics here when you instrument these — and update this table.

---

## 8. Reference — Persistence & state (where to look on disk / in DuckDB)

### DuckDB stores (all opt-in; default to in-memory/file)
| Store | Table(s) | Key columns | Default URL | Enable flag |
|---|---|---|---|---|
| Job runs (`DbJobRunStore`) | `inspecto_job_runs` | run_id, job, type, "trigger", start/end_time, status, duration_ms, message | `jdbc:duckdb:jobs_report.duckdb` | `-Djobs.backend=duckdb` |
| Status (`DbStatusStore`) | `inspecto_status_commits/_batches/_files/_lineage/_quarantine` | pipeline, batch_id, seq, payload(JSON) | `jdbc:duckdb:inspecto-status.db` | `-Dstatus.backend=db` |
| Acquisition ledger (`DbAcquisitionLedger`) | `inspecto_acquisition_ledger` (PK source_id,relative_path); `inspecto_acquisition_db_watermark` (PK source_key) | source_id, relative_path, size, checksum, last_modified, processed_at, status; watermark_value, advanced_at | `jdbc:duckdb:inspecto-acquisition.db` | `-Dacquire.ledger.backend=db` |
| Objects/links/notes | (per impl) | — | `inspecto-ops.db` / `-ops-links.db` / `-ops-notes.db` | `-Dobjects.backend=db` |
| Events (`ParquetEventStore`) | Parquet files (not a table) | event_id, ts_ms, type, source, pipeline, correlation_id, message, attributes, level + level/year/month/day partitions | dir `inspecto-events/` | `-Devents.backend=parquet` |

### CSV ledgers
| File | Columns (header) | Writer |
|---|---|---|
| `<jobs.audit.dir>/jobs_runs.csv` | run_id,job,type,trigger,start_time,end_time,status,duration_ms,message | JobService |
| `<status_dir>/<pipeline>_status_<ts>.csv` | start_time,end_time,filename,status,parsed_rows,error_rows,output_paths,output_sizes_bytes,duration_ms,error,batch_id | BatchAuditWriter |
| `<status_dir>/<pipeline>_batches_<ts>.csv` | batch_id,pipeline,schema_name,output_table,start/end_time,status,member_count,rejected_count,total_input_rows,total_output_rows,output_file_count,total_output_bytes,duration_ms,error | BatchAuditWriter |
| `<status_dir>/<pipeline>_lineage_<ts>.csv` | batch_id,src_id,input_file,output_file,partition,row_count | BatchAuditWriter |
| `<output.database>_audit/<job>_enrich_runs.csv` | run_id,job,trigger,reason,scope,input_partition_count,start/end_time,status,output_partition_count,output_file_count,total_output_rows,total_output_bytes,duration_ms,error | EnrichmentAuditWriter |
| `<output.database>_audit/<job>_enrich_lineage.csv` | run_id,job,partition,output_file,bytes | EnrichmentAuditWriter |

### Per-pipeline on-disk layout (`PipelineConfig.Dirs`)
| Dir | Holds | Owner |
|---|---|---|
| `poll` | input drop zone / remote materialisation | SourceProcessor |
| `database` | partitioned Parquet/CSV output (`year=/month=/day=`) | PartitionWriter |
| `backup` | copies of originals after commit | FileBackup |
| `temp` | streaming scratch + DuckDB temp | CsvBatchStrategy |
| `errors` | per-file field-validation error CSVs | CsvIngester |
| `quarantine` | rejects under reason subdirs `field_mismatch/`, `unreadable/`, `corrupt_download/` (mirrors poll rel-path) | QuarantineManager |
| `markers` | `*.processed` sentinels (mirror poll structure) + `.last_cleanup` | MarkerManager |
| `manifests_dir` | batch manifests (reprocess anchor) | BatchManifest |
| `commit_log` | fsync'd one-line-per-commit log | CommitLog |
| `status_dir` | the three audit CSVs above | BatchAuditWriter |
| `log_dir` | per-pipeline logs | logging |

### Write-root artifacts (jailed under `-Dassist.write.root`, 503 if unset)
- `<wr>/flows/<id>.toon` (authored flows) · `<wr>/registry/{grammars,schemas,transforms,sinks}/<id>.toon`
  (components) · `<wr>/<id>_connection.toon` (connection profiles, flat) · `<wr>/views/<store>_view.toon`
  (`sink.view` logical-store definitions: store + flow + source_store lineage) · branch-commit logs under the jobs audit dir.

---

## 9. Reference — Runtime config flags (`-D…`)

| Flag | Default | Meaning |
|---|---|---|
| `control.port` | `8080` | Control API HTTP port |
| `control.cors` | (unset) | CORS allowed origin; unset = no CORS header |
| `ui.dir` | (unset) | static Angular SPA dir; unset = no UI served |
| `assist.write.root` | (unset) | path-jail root for write endpoints; unset = 503 on writes |
| `data.dir` | `database` | data root for flow jobs (`<dataDir>/<store>`); per-job `data_dir` overrides |
| `jobs.backend` | `none` | `duckdb`/`jdbc:…` enables `DbJobRunStore` |
| `jobs.db.url` | `jdbc:duckdb:jobs_report.duckdb` | job-run DB URL |
| `jobs.audit.dir` | `jobs_audit` | dir for `jobs_runs.csv` |
| `status.backend` | `file` | `db` → `DbStatusStore` |
| `status.db.url` | `jdbc:duckdb:inspecto-status.db` | status DB URL (migrates legacy `ucc-status.db`) |
| `status.db.user` / `status.db.password` | (unset) | status DB creds |
| `events.backend` | `memory` | `parquet` → durable rolling Parquet |
| `events.dir` | `inspecto-events` | Parquet event root |
| `events.views.file` | (unset) | persisted saved-views JSON; null = in-memory |
| `objects.backend` | `memory` | `db` enables object/link/note DuckDB stores |
| `objects.db.url` | `inspecto-ops.db` | object store URL |
| `objects.links.db.url` / `objects.notes.db.url` | `…-ops-links.db` / `…-ops-notes.db` | separate (single-writer) |
| `objects.db.user` / `objects.db.password` | (unset) | object-tier creds |
| `acquire.ledger.backend` | `memory` | `db` → `DbAcquisitionLedger` |
| `acquire.ledger.db.url` | `jdbc:duckdb:inspecto-acquisition.db` | acquisition ledger URL |
| `acquire.ledger.db.user` / `.password` | (unset) | ledger creds |
| `assist.sql.memory_limit` | `1GB` | SQL sandbox memory cap |
| `assist.sql.threads` | `2` | SQL sandbox thread cap |
| `assist.sql.timeout_seconds` | `30` | SQL sandbox per-query timeout |
| `assist.safety.roots` | `""` (→ CWD) | comma-separated allowed config-safety path roots |

Secrets: `SecretResolver` resolves `SYS:<key>` via `System.getProperty(key)` — any property can be a secret source.

---

## 10. Reference — Control API routes (grouped)

Auth-free in the common core. **503 = write-root gated** (set `-Dassist.write.root`).

- **Health/metrics:** `GET /health`, `GET /ready`, `GET /metrics` (Prometheus), `GET /metrics/acquisition` (JSON).
- **Config:** `POST /validate`, `GET /config/spec/{type}`, `POST /config/write` *(503)*.
- **Pipelines:** `GET /pipelines`, `POST /pipelines` *(503)*, `POST /pipelines/{n}/trigger|pause|resume|reprocess`,
  `GET /pipelines/{n}/commits|batches|files|lineage|quarantine|pending|report`, `POST /trigger` (all).
- **Status/report:** `GET /status`, `GET /report`.
- **Jobs:** `GET /jobs`, `GET /jobs/metrics|runs|failures`, `GET /jobs/{n}/runs`, `POST /jobs/{n}/trigger`.
- **Enrichment:** `GET /enrichment`, `GET /enrichment/{job}/runs|lineage|report`.
- **Sources:** `GET /sources` (incl. current DB watermark).
- **Connections:** `GET /connections`, `GET /connections/{id}`, `POST /connections/{id}/test`,
  `POST /connections` *(503)*, `PUT/DELETE /connections/{id}` *(503; DELETE 409 if in use)*.
- **Events:** `GET /events`, `/events/search`, `/events/{id}`, `/events/export`, `GET/POST /events/views`,
  `POST /events/views/{name}/delete`.
- **Alerts:** `GET /alerts`, `/alerts/rules`, `POST /alerts/evaluate` (503 if no rules).
- **Objects:** `GET/POST /objects`, `GET /objects/{id}`, `POST /objects/{id}/ack|resolve|transition|links|comments|attachments|rca`,
  `GET /objects/{id}/links|graph|comments|attachments`, `GET /rca/templates`.
- **Flows:** `GET /flows`, `/flows/node-types`, `/flows/combined`, `/flows/{n}/graph`; `GET /flows/authored`,
  `POST /flows/authored` *(503)*, `GET /flows/authored/{n}`, `PUT/DELETE /flows/authored/{n}` *(503)*,
  `POST /flows/authored/{n}/nodes|edges|dry-run` *(503)*.
- **Components:** `GET /components/{type}[/{id}]`, `POST/PUT/DELETE` *(503; DELETE 409 if referenced)*,
  `POST /components/{transform|grammar|schema|sink}/{id}/test` *(503)*.
- **Catalog:** `GET /catalog`, `/catalog/kpis`, `/catalog/graph`, `/catalog/tables/{id}`.
- **Assist:** `GET /assist/diagnoses|settings|metrics`, `POST /assist/settings|settings/test|{intent}` (503 if absent).

---

## 11. Troubleshooting playbooks (symptom → investigate → fix)

> General first move: `GET /events/search?pipeline=<p>&from=…` for the timeline, `GET /metrics` for rates,
> and the pipeline's audit CSVs / `GET /pipelines/{n}/batches|files|quarantine` for per-file detail.

**Files not being picked up.**
Check: is the pipeline `active: true`? paused (`inspecto_paused`)? due this tick (interval/cron)? Is there a
`*.processed` marker already (PATH dedup) or a ledger fingerprint (content dedup)? `inspecto_files_waiting_stability`
> 0 means the stability gate is holding them. `inspecto_watermark_skipped_total` rising means the high-watermark
dropped them. Fix: arm `active`, resume, clear the stale marker, or relax the watermark/stability window.

**Files stuck "waiting stability".** A slow-growing or still-being-written file never quiesces. Check the
stability window/size-check config; confirm the upstream finished writing. Gauge `inspecto_files_waiting_stability`.

**Duplicates skipped / a file won't reprocess.** Content dedup ledger or PATH marker already has it
(`inspecto_duplicates_skipped_total`). To force reprocess: `POST /pipelines/{n}/reprocess` with the `batchId`, or
remove the marker/ledger entry. Remember markers/ledger are written **LAST** in commit — a crashed batch leaves no
marker and *will* re-pick the file.

**Output lands in `year=NULL/month=NULL/day=NULL`.** Partition columns weren't derived. See
[`troubleshooting.md`](troubleshooting.md) (partition extraction) — usually a schema/derive mis-config.

**A batch failed.** `GET /pipelines/{n}/batches` (status FAILED) or `events/search?type=BATCH_FAILED` → attrs
`error`, `offendingFile`, `errorRows`. Per-file: `GET /pipelines/{n}/files` + the `errors/` CSV. Quarantined
inputs: `GET /pipelines/{n}/quarantine` (reason subdir tells you field_mismatch vs unreadable vs corrupt_download).

**Sequence gap / missing file.** `SEQUENCE_GAP` event (attrs expected/sequence/unit), `inspecto_sequence_gaps_total`;
also auto-promoted to an `ALERT` object via `EventObjectBridge` (find it in `/objects?type=ALERT`).

**Source circuit open.** `SOURCE_CIRCUIT_OPEN` event (attr source) after repeated discover failures; the source is
skipped until the breaker half-opens. Check connectivity (`POST /connections/{id}/test`), then it self-recovers.

**A job isn't firing.** Cron: is it enabled + a valid `cron`? `GET /jobs` shows `nextFire`. Event: does
`on_pipeline` exactly match the upstream's `BatchEvent.pipeline()` (**lowercased** for pipelines)? Catch-up only
runs for `catch_up: true` cron jobs with a prior audited run. Manual: `POST /jobs/{n}/trigger`. A `SKIPPED` run
means the previous run is still in flight (`LockingRunner`).

**A flow job writes nothing ("0 file(s)").** Idempotent replay: the same `batch_id` already committed all branches
(`BranchCommitLog`). Use a fresh `batch_id` (default = per-run timestamp) to recompute. Also: `sink.view` writes no
bytes in Phase A.

**Flow job fails to build / run.** `IllegalStateException` at build = no `-Dassist.write.root` (flow store
fail-closed). At run: unknown `flow` id, or a flow that declares **no** `source_store` (≥1 required; multiple are
unioned/joined via `transform.merge` since Phase C).

**`STORE_DELETE_CONFLICT`.** A `MAINTENANCE` delete-job's store has an active producer/consumer — a running
pipeline **or** an in-flight FLOW job (T32). Event attrs `activeProducers`/`activeConsumers` name them. Fix: delete
in a quiet window, or make slices disjoint. (Non-blocking — it warns/alerts, doesn't stop the delete.)

**Alerts not firing.** Any `*_alert.toon` armed? (`GET /alerts/rules`; `POST /alerts/evaluate` returns 503 if
none.) Alerts evaluate on terminal `BatchEvent`s; a per-rule cooldown suppresses re-fires within the window.

**Write endpoint returns 503.** `-Dassist.write.root` is unset. This gates all config/connection/flow/component
writes by design (fail-closed).

**Events disappear after restart / not queryable.** Default backend is the in-memory ring (cap 8192, lost on
restart). For durable, queryable events set `-Devents.backend=parquet -Devents.dir=…`.

**`/metrics` looks empty for a pipeline.** Most pipeline metrics only appear after the first cycle/commit; gauges
are computed at scrape time by `MetricsService`'s collector. Confirm the service started (`SERVICE_STARTED`).

**Deadlock / cycle hangs.** Almost always a bus subscriber doing real work inline (the bus is synchronous and
`ingestLock` is held). Any subscriber that ingests/triggers MUST hand off to an executor (see §3).

---

## 12. Known gotchas & invariants (don't re-learn these the hard way)

- **Synchronous bus + `ingestLock` ⇒ deadlock** if a subscriber runs ingest inline. Always hand off to a vthread
  pool (`triggerWorkers` / job `workers`).
- **`BatchEvent.pipeline()` is the LOWERCASED pipeline name.** Any name matching (event triggers, `on_pipeline`)
  must account for this.
- **Commit writes markers + ledger + watermark LAST** (after backup/manifest) so a crash never strands a file as
  "done". Preserve this ordering in any commit-path change.
- **`PartitionWriter` always partitions** (emits `PARTITION_BY (...)`, non-empty cols required). The flow
  `PartitionSinkWriter` owns the unpartitioned single-file case; don't push a `(year,month,day)` default onto a
  store that lacks those columns.
- **DuckDB reserved words** must be quoted in SQL: `day`, `trigger` (seen in `DbStatusStore`/`DbJobRunStore`).
- **`ConfigCodec.toToon` cannot round-trip a schema loaded as Java Maps** (nested lists-of-maps aren't tabular).
  Write test schemas as inline TOON strings. (Component CRUD's simple maps *do* round-trip.)
- **No `#` comments in any `.toon` file** — the parser rejects them.
- **Every JVM launch needs `--enable-native-access=ALL-UNNAMED`** (DuckDB JNI), tests included.
- **`active:` defaults to false** — a new pipeline does nothing until armed.
- **DuckDB is single-writer** — that's why objects/links/notes use three separate db files.
- **Editions are build flavors, never branches or `if (edition==…)`.** Add capability via a `ServiceLoader` SPI.
- **`InMemoryAcquisitionLedger` shows as a binary diff** (NUL separator in `key()`) — pre-existing & harmless.

---

## 13. Cross-references
- Config authoring & all TOON keys → [`configuration.md`](configuration.md)
- Acquisition feature depth (connectors, watermark, retry/circuit/post-actions, bastion) → [`data_acquisition_framework.md`](data_acquisition_framework.md)
- Flow engine design (IR, lift, validator, executor, T32 live-exec) → [`flow-graph-design.md`](flow-graph-design.md), [`flow-live-execution-plan.md`](flow-live-execution-plan.md)
- Editions / build flavors → [`EDITIONS.md`](EDITIONS.md) · Branch & release policy → [`BRANCHING.md`](BRANCHING.md)
- Architecture overview → [`architecture.md`](architecture.md) · Operator console → [`operator-console.md`](operator-console.md)
- Short fixes (DuckDB/PG view quirks, partition extraction) → [`troubleshooting.md`](troubleshooting.md)

---

*Living document — keep it current (see §0). Last verified against code: 2026-06-18.*
