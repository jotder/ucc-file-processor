# Plan — Live execution of authored job-flows (T32)

> **Status: PHASES A + B + C IMPLEMENTED (2026-06-18/19).** All three phases built and tested (inspecto 802/0/1):
> `JobType.FLOW`, `FlowJobRunner`, `PartitionSinkWriter`, `SourceStoreReader`, `JobService.build()` wiring,
> `SourceService.openFlowStore()`, the deletion fence (§7.5); a FLOW job is a first-class scheduled/chained/reported
> job (Phase B); and Phase C: **multi-`source_store`** (each seeded as its own view, joined/unioned by
> `transform.merge`), **`sink.view`** logical stores (registered as a `ViewDefinition` under `<write-root>/views/`),
> **opt-in incremental** re-run (`incremental_column` → watermark-filtered read + append), and **actor attribution**
> (`trigger(name, actor)` → `manual:<actor>`). Open questions in §7 resolved inline. Tracked as **T32** in
> [`flow-graph-design.md`](flow-graph-design.md) §14.

## 1. The problem & the key constraint

Authored flows (created via the T19a UI, persisted by `com.gamma.flow.FlowStore` under
`<write-root>/flows/*_flow.toon`) are today **CRUD-able and dry-runnable only** — nothing runs them
for real. `com.gamma.flow.exec.FlowExecutor.execute(...)` exists (T12) and does the topological
transform→sink walk + branch-aware commit, but in tests its **seed table is hand-built**
(`CREATE TABLE parsed AS VALUES …`); nothing reads a real store into that seed, writes the sinks to a
real store, or schedules the run.

**Constraint that decides the architecture (from T23, §3.8):** *ingest is pipeline-exclusive.* An
authored flow is therefore **job-style** — it declares a `source_store` (data already at rest), runs
`transform.*` nodes, and writes to a sink `store`. It is **not** a re-acquisition. This means:

- The "compile authored flow → `PipelineConfig` → run via the poll loop" shortcut **does not apply**:
  `FlowCompiler.toConfigMap` only round-trips **lifted** graphs (typed `cfg` sub-records), not
  UI-authored ones (plain-map `cfg`), and the legacy pipeline shape can't express a rich multi-node
  transform DAG. Confirmed in [`FlowCompiler.java`](../inspecto/src/main/java/com/gamma/flow/FlowCompiler.java:127).
- The right path is to **drive `FlowExecutor` directly**, hosted as a **`Job`** on the existing
  `JobService` scheduler (cron / event / manual) — which already gives us scheduling, audit, the
  deletion fence (T25), `DbJobRunStore` reporting (T27), and batch-event chaining for free.

## 2. Decision

**Run authored flows as a new job type, `JobType.FLOW`, implemented by a new `FlowJobRunner implements
com.gamma.job.Job`, scheduled and audited by the existing `JobService`.** Reuse `FlowExecutor` +
`BranchCommitCoordinator` for execution/commit, the `EnrichmentEngine` store-read pattern for the seed,
and `PartitionWriter` for the sink write. No new scheduler, no engine fork.

This mirrors `EnrichJob` (the closest existing analog: a custom function over committed data) almost
exactly — same read-view → transform → partitioned-write → publish-event → record-run shape.

## 3. Run sequence (`FlowJobRunner.run()` → `JobResult`)

```
1. open ephemeral DuckDB           DuckDbUtil.openConnection(tempDbFile)
2. seed each source_store as a view  for store in FlowStores.consumed(graph):
     CREATE VIEW <store> AS SELECT * FROM <SqlViews.reader(format, "<dirs.database>/<store>/**/*.<ext>", hive=true)>
3. execute the flow                FlowExecutor.execute(conn, graph, seedNodeId, seedView,
                                       batchId, coordinator, sinkWriter, sourceFinalize)
     - sinkWriter   = PartitionSinkWriter: PartitionWriter.write(conn, inputTable,
                        "<dirs.database>/<sink.store>", format, compression, baseName, partitionCols)
     - sourceFinalize = () -> {}        // no acquisition/markers for a job (no-op is valid, FlowExecutorTest:77)
     - coordinator  = new BranchCommitCoordinator(new BranchCommitLog("<auditDir>/<flow>_branch_commit.csv"))
4. publish chain event             bus.publish(new BatchEvent(flowName, runId, "SUCCESS", parts, rows, ms, 0))
5. return                          JobResult.ok(flowName, ms)   // JobService records audit + DbJobRunStore
```

Idempotent re-run is free: `BranchCommitCoordinator` skips branches already in `BranchCommitLog` for
the `batchId` (T11). `PartitionWriter` uses `OVERWRITE_OR_IGNORE`, matching `EnrichmentEngine`'s
full-recompute semantics for the MVP.

## 4. Components — reuse vs create

| Component | Status | Reference |
|---|---|---|
| `FlowExecutor.execute(conn, g, seedNodeId, seedTable, batchId, coordinator, sinkWriter, sourceFinalize)` | **reuse** | [FlowExecutor.java:72](../inspecto/src/main/java/com/gamma/flow/exec/FlowExecutor.java) |
| `BranchCommitCoordinator` + `BranchCommitLog` (idempotent multi-branch commit) | **reuse** | [BranchCommitCoordinator.java:47](../inspecto/src/main/java/com/gamma/flow/exec/BranchCommitCoordinator.java) |
| `SqlViews.reader(format, glob, hive)` / `SqlViews.ext(format)` (store→view) | **reuse** | [SqlViews.java:20](../inspecto/src/main/java/com/gamma/sql/SqlViews.java) |
| `PartitionWriter.write(conn, table, dir, format, compression, base, partCols)` | **reuse** | [PartitionWriter.java:83](../inspecto/src/main/java/com/gamma/etl/PartitionWriter.java) |
| `JobService` (cron/event/manual scheduling, audit, deletion fence, `DbJobRunStore`) | **reuse** | [JobService.java:181](../inspecto/src/main/java/com/gamma/job/JobService.java) |
| `FlowStore` (load authored `FlowGraph` by id) | **reuse** | [FlowStore.java](../inspecto/src/main/java/com/gamma/flow/FlowStore.java) |
| `Job` interface + `JobConfig` + `JobResult` + `BatchEvent` | **reuse** | [Job.java](../inspecto/src/main/java/com/gamma/job/Job.java) |
| **`JobType.FLOW`** (new enum value + `from()` parse "flow") | **create** | [JobType.java:13](../inspecto/src/main/java/com/gamma/job/JobType.java) |
| **`FlowJobRunner implements Job`** (orchestrates the §3 sequence) | **create** | new in `com.gamma.flow.exec` |
| **`PartitionSinkWriter`** (a `FlowExecutor.SinkWriter` over `PartitionWriter`; resolves store→dir, format/partitions from the sink node `cfg`) | **create** | new in `com.gamma.flow.exec` |
| **`SourceStoreReader`** (helper: register a `source_store` as a DuckDB view via `SqlViews.reader`) | **create** | new in `com.gamma.flow.exec` |
| **`JobService.build()` case `FLOW -> new FlowJobRunner(c, bus, flowsRoot, dataDir)`** | **edit** | [JobService.java:182](../inspecto/src/main/java/com/gamma/job/JobService.java) |

## 5. Config shape (`*_job.toon`)

```
name: nightly_rollup
type: flow                 # new JobType
flow: events_rollup        # authored flow id (FlowStore.get) — or flow_file: events_rollup_flow.toon
cron: "0 2 * * *"          # OR  on_pipeline: events_etl   (event)  OR  manual (trigger API)
enabled: true
```

`JobConfig` already parses `name/type/cron/on_pipeline/enabled/catch_up` + arbitrary params
([JobConfig.java:93](../inspecto/src/main/java/com/gamma/job/JobConfig.java)); `flow`/`flow_file` are
just params `FlowJobRunner` reads. Scheduling lives in the **JobConfig** (consistent with all jobs), not
the FlowGraph entry-node `trigger:` (that is for the pipeline poll loop). Activation = `JobConfig.enabled()`.

## 6. Phasing

- **Phase A (core run path) — ✅ DONE (2026-06-18).** `JobType.FLOW`, `FlowJobRunner`, `PartitionSinkWriter`,
  `SourceStoreReader`; `JobService.build()` wiring (fail-closed without an authored-flow store) +
  `SourceService.openFlowStore()`. Manual run via the existing `POST /jobs/{name}/trigger` over a `type: flow`
  `*_job.toon`. Persistent + materialized sinks (unpartitioned single-file write when a sink declares no
  `partitions`). Full-recompute commit. Tested: `FlowJobRunnerTest`(4) + `JobServiceTest`(+2). The dedicated
  `POST /flows/authored/{id}/run` was **not** built — the job-config path covers Phase A; revisit if ad-hoc
  (config-less) runs are wanted.
- **Phase B (scheduling + chaining) — ✅ DONE (2026-06-18).** No production change was needed — `JobService` arms
  cron and dispatches `on_pipeline` events type-agnostically, and `FlowJobRunner` already publishes a chain
  `BatchEvent(jobName)` on success. Phase B is the proof that `FLOW` participates fully, via 4 deterministic tests
  in `JobServiceTest`: `cronFiresAFlowJob` (cron), `onPipelineEventFiresAFlowJob` (a pipeline commit triggers the
  flow), `aFlowJobSuccessChainsADownstreamJob` (the flow's success fires a downstream `on_pipeline` job), and
  `flowRunsAreProjectedIntoTheReportingStoreAsTypeFlow` (the run reaches `DbJobRunStore`, typed `FLOW`).
- **Phase C (semantics polish) — ✅ DONE (2026-06-18/19), one commit per slice.**
  - **Multi-`source_store`** (`93146d8`): `FlowExecutor` gained a `Map<nodeId,seedView>` execute overload; a flow
    job seeds each `source_store` as its own view, a `transform.merge` joins/unions them. `unionsTwoSourceStores`.
  - **`sink.view`** (`6d8d54e`): no bytes — the job registers a durable `ViewDefinition`/`ViewStore` under
    `<write-root>/views/<store>_view.toon` (store + flow + source_store lineage) for a binding API to concretise.
    `registersASinkViewDefinitionWithoutWritingBytes`. (`derived_sql` left for a follow-up — the multi-statement
    `RowShaper` chain isn't a single SELECT.)
  - **Incremental** (`dff1ac0`): opt-in `incremental_column` job param + `FlowWatermarkStore` (file-based, keyed by
    flow+store, advanced after commit) → reads only rows past the watermark and appends (run-unique sink base).
    Single-source. `incrementalReadsOnlyNewRowsPastTheWatermark`.
  - **Actor attribution**: `JobService.trigger(name, actor)` + `POST /jobs/{name}/trigger?actor=` → the run records
    `manual:<actor>` (cron/event/catch-up already self-attribute). `manualTriggerAttributesTheActor`.

## 7. Open questions / risks

1. **Sink subtype semantics.** `sink.persistent`/`sink.materialized` → `PartitionWriter` (Parquet/CSV).
   `sink.view` writes no bytes — needs a catalog/DuckDB-view registration path.
   **[Phase A] `PartitionSinkWriter` skips `*.view` sinks; persistent/materialized write Parquet/CSV,
   unpartitioned single-file when no `partitions` declared (the legacy `PartitionWriter` always partitions).
   [Phase C DONE] a `sink.view` registers a durable `ViewDefinition` (`<write-root>/views/<store>_view.toon`:
   store + flow + source_store lineage) instead of bytes; a KPI/report/alert API concretises it by running the flow.**
2. **Schema of the source store.** `read_parquet` infers types; `transform.*` SQL casts as needed
   (same trust model as enrichment). CSV stores need the format/options — resolve via the store metadata
   or the consuming node `cfg`. **[Phase A] format read from the source node's `format` cfg (default `PARQUET`);
   `SourceStoreReader` uses the shared `SqlViews.reader(format, glob, hive=true)`.**
3. **`batchId` strategy** for idempotency: per-run timestamp (always recompute, simplest, MVP) vs a
   source-watermark/content id (true incremental, Phase C). MVP overwrites, matching `EnrichmentEngine`.
   **[Phase A] `batch_id` job param when set (stable ⇒ idempotent replay skips committed branches via the
   `BranchCommitLog`); default = per-run timestamp. [Phase C DONE] opt-in true incremental via `incremental_column`
   + `FlowWatermarkStore`: reads only rows past the stored watermark and appends (run-unique sink base), single-source.**
4. **Where data/audit dirs come from.** A job has no `PipelineConfig`; `FlowJobRunner` needs the data
   root (`dirs.database` equivalent) + audit dir injected from `JobService`/`SourceService` config.
   **[Phase A RESOLVED] data root = `-Ddata.dir` (default `database`), overridable per-job via the `data_dir`
   param; the branch-commit log lives under the jobs audit dir (`-Djobs.audit.dir`, default `jobs_audit`),
   both threaded `SourceService → JobService → FlowJobRunner`.**
5. **Deletion fence (T25). [DONE 2026-06-18.]** `JobService` fences a `maintenance` job whose store is being
   deleted. A `FLOW` job is now also covered — but **not** by the originally-sketched "mirror the maintenance
   `store:` path in `fenceDelete`": that would have a flow job call `guard.check(itsOwnStores)`, asking "is a
   store I read/write produced by a running pipeline?" — which fires `STORE_DELETE_CONFLICT` on *normal* concurrent
   read/append (exactly the safe case the fence must NOT flag). The fence's real direction is "a **deleting** job
   checks its targets against running producers/consumers", so the correct fix made the fence *aware of* flow jobs:
   (a) `SourceService.checkDeletion` now adds the authored flows (`flowStore.list()`) to the producer/consumer
   topology, and (b) `JobService` tracks in-flight `FLOW` runs (`runningFlows()`), which `checkDeletion` unions
   into the active set. So deleting a store an active flow job reads/writes now surfaces a conflict, while idle
   authored flows never do. Tested: `JobServiceTest.flowJobRunsEndToEndAndIsTrackedWhileRunning` (deterministic via
   the synchronous bus) + the existing `DeletionFenceTest` consumer-conflict case (pure logic, flow-agnostic).
6. **Concurrency with the producing pipeline.** A flow reading `store X` while a pipeline writes `X`:
   rely on the `on_commit`/event trigger (run after the producer commits) rather than a time cron, to
   avoid reading a half-written store. **[Phase B DONE 2026-06-18] `on_pipeline` exercised for FLOW
   (`onPipelineEventFiresAFlowJob`). Guidance: prefer `on_pipeline: <producer>` over a time `cron` whenever a flow
   reads a store a pipeline writes — the event fires only after the producer's commit is durable.**

## 8. Test plan

- **`FlowJobRunnerTest`** (unit/integration, `--enable-native-access=ALL-UNNAMED`): seed a temp store dir
  with a small Parquet dataset; author a `source_store → transform.filter → sink.persistent` flow; run;
  assert the output Parquet exists with the expected rows (cross-check against `FlowDryRun` counts on the
  same sample).
- **Commit idempotency:** run twice with the same `batchId`; assert the second run skips committed
  branches (no double write) via `BranchCommitLog`.
- **`JobService` integration:** a `type: flow` `*_job.toon` loads, `build()` produces a `FlowJobRunner`,
  `trigger(name)` runs it and records a run row (`DbJobRunStore`); an `on_pipeline` event fires it.
- **Multi-branch:** a flow with `transform.route` → two sinks; assert both stores written and both
  branches logged.
- Keep the existing suite green (`mvn -o clean test`).

## 9. Effort

~600–900 LOC across `FlowJobRunner`, `PartitionSinkWriter`, `SourceStoreReader`, the `JobType`/`JobService`
edits, and tests. No new dependencies. Phase A is the bulk and is independently shippable; B is mostly
exercising existing `JobService` paths; C is incremental.
