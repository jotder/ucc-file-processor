---
type: Concept
title: Pipeline Live Execution
description: Running an authored Pipeline as a JobType.PIPELINE job — source_store seeds, sink writing, conservation checks.
resource: inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java
tags: [pipeline-graph, execution, job, source-store, conservation]
timestamp: 2026-07-07T00:00:00Z
---

# Pipeline Live Execution

An authored Pipeline is **job-style**: it reads data at rest (`source_store`), runs `transform.*` nodes, and
writes to a sink store. It is hosted as a [`JobType.PIPELINE`](../control-plane/jobs.md) job on the existing
`JobService` scheduler (cron / `on_pipeline` / manual). Design of record (T32 plan, shipped 2026-06-18/19):
[`flow-live-execution-plan.md`](../../../archived-documents/plans-archive/flow-live-execution-plan.md).

## `PipelineJobRunner.run()`

`PipelineJobRunner` (`inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java`):

1. Load the `PipelineGraph` from `PipelineStore`.
2. `seedsOf(g)` — find every node with a non-blank `source_store` cfg key; **throws if none** (zero seeds is
   a hard error). The Phase-A MVP allowed exactly one seed; Phase C relaxed it to **≥1** (a downstream
   `transform.merge` joins/unions multiple sources).
3. For each seed, `SourceStoreReader.registerView(…)` registers a DuckDB view over the at-rest Parquet/CSV.
4. `PipelineExecutor.execute(…)` walks the graph (see [design](design.md)).
5. `PartitionSinkWriter` (the `SinkWriter` impl) delegates each sink write to
   [`PartitionWriter`](../engine/output-sinks.md).
6. Optionally collect provenance (`DbProvenanceStore`) and run `ConservationCheck` → emit
   `FLOW_CONSERVATION_IMBALANCE` events when records are lost at a non-amplifying node.
7. Advance `PipelineWatermarkStore` per source (opt-in incremental mode); register `sink.view` outputs as durable
   `ViewDefinition`s.
8. Publish a `BatchEvent`, return a `JobResult`.

Config (`*_job.toon`): `type: pipeline`, `flow: <authored-pipeline-id>` (the `flow:` key name is verbatim
legacy), plus `cron:` / `on_pipeline:` / manual.

## Config-less ad-hoc run (2026-07-18)

`POST /pipelines/authored/{id}/trigger` fires an authored Pipeline **once, with no `*_job.toon`**:
`JobService.triggerFlowRun(flowId, actor)` builds a synthetic, **never-registered** `type: pipeline` config
and runs it through the exact registered-job lifecycle — deletion-fence `runningFlows()` tracking, per-flow-id
non-overlap (a re-fire while running records `SKIPPED`), the durable run ledger, and `GET /jobs/runs/{runId}`
polling — so `GET /jobs` stays config-only while `GET /jobs/{flowId}/runs` serves the ad-hoc history (runs are
recorded under the flow id; the chain `BatchEvent` is likewise published under the flow id, so downstream
`on_pipeline:` consumers key on it, not on a job name). Response mirrors `POST /jobs/{name}/trigger`:
`202 {runId, pipeline, status}` + `Location`. **Deliberately `…/trigger`, not `…/run`** — `POST …/run?to={nodeId}`
is the editor's scratch-only run-to-here contract (`pipelines.service.ts`, mock-only today) and must never fire
a production run. Prefer a persisted `*_job.toon` when a run needs `data_dir`/`batch_id` overrides, a schedule,
or chaining; the ad-hoc route takes no params.
