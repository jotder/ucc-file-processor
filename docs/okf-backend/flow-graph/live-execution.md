---
type: Concept
title: Flow Live Execution
description: Running an authored flow as a JobType.PIPELINE job — source_store seeds, sink writing, conservation checks.
resource: inspecto/src/main/java/com/gamma/pipeline/exec/PipelineJobRunner.java
tags: [flow-graph, execution, job, source-store, conservation]
timestamp: 2026-06-28T00:00:00Z
---

# Flow Live Execution

An authored flow is **job-style**: it reads data at rest (`source_store`), runs `transform.*` nodes, and
writes to a sink store. It is hosted as a [`JobType.PIPELINE`](../control-plane/jobs.md) job on the existing
`JobService` scheduler (cron / `on_pipeline` / manual). Authoritative doc: `docs/flow-live-execution-plan.md`.

## `PipelineJobRunner.run()`

`PipelineJobRunner` (`inspecto/src/main/java/com/gamma/pipeline/exec/PipelineJobRunner.java`):

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

Config (`*_job.toon`): `type: pipeline`, `flow: <authored-flow-id>`, plus `cron:` / `on_pipeline:` / manual.
