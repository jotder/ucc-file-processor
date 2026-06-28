---
type: Reference
title: Cross-Cutting Gotchas
description: The non-obvious backend pitfalls that are expensive to rediscover — collected from PROJECT_NOTES §4.
resource: docs/PROJECT_NOTES.md
tags: [gotchas, pitfalls, toon, duckdb, mdc, deadlock]
timestamp: 2026-06-28T00:00:00Z
---

# Cross-Cutting Gotchas

* **TOON schema serialization** — `ConfigCodec.toToon(map)` does **not** emit tabular-array format. A schema
  whose `fields`/`rules` are Java-constructed `List<Map>` round-trips as nested maps and the parser throws
  *"Array length mismatch: declared N, found 0"*. In tests, write the schema as an inline TOON string
  (`fields[N]{name,selector,type}: …`), not via `toToon(schemaMap)`. See [TOON config](../config/toon-config.md).
* **DuckDB reserved words** — `day` is a keyword: alias it (`run_day`) in SQL; quote `"trigger"` too. Watch
  this whenever generating SQL with date/trigger columns. See [DuckDB](../engine/duckdb.md).
* **`BatchEvent.pipeline()` is the LOWERCASED pipeline name** (`cfg.identity().pipelineName()`). Any name
  matching against it (triggers, `runPipeline`, `pathFor`) must use the lowercased id — e.g.
  `runPipeline("up_stream")`, not `"UP_STREAM"`.
* **Synchronous bus + `ingestLock` ⇒ deadlock** — the [event bus](../control-plane/events-metrics.md) publishes
  synchronously on the publishing thread, and `ingestLock` is held during a cycle. An event-triggered run
  dispatched **inline** would deadlock — hand off to the off-bus virtual-thread pool (`triggerWorkers` /
  `JobService.submit`). See [jobs](../control-plane/jobs.md).
* **The per-space `space` MDC must reach EVERY worker thread on the execution path.** Singleton routing reads
  the MDC on the *current* thread, and MDC does **not** cross thread-pool boundaries. Each executor running
  ingest/commit work must `MDC.getCopyOfContextMap()` on the caller + `setContextMap` on the worker +
  `clear()` in `finally` — `MultiSourceProcessor.runAll`/`runConfigs` **and** `SourceProcessor`'s per-batch
  executor. Miss one and that space's metrics/events silently fall back to `"default"`. See
  [multi-space](../control-plane/multi-space.md).
* **Pipeline-internal paths resolve against the JVM CWD, not the space root.** A pipeline's `schema_file`,
  `grammar`, and `dirs.*` are `Paths.get(...)` in `PipelineConfigParser` with no rebasing to `spaces/<id>/`.
  Only the *space discovery* layer (`-Dspaces.root`) is space-relative. So configs under `spaces/<id>/config/`
  must use repo/bundle-root-relative paths, and `SpaceMigrator` cannot auto-fix absolute/author-relative ones.
* **`PartitionWriter` requires non-empty partition columns** (it emits `PARTITION_BY (...)`). The unpartitioned
  single-file `COPY` path is `PartitionSinkWriter.writeUnpartitioned()`. See [output & sinks](../engine/output-sinks.md).
* **Flow seed must be ≥ 1 `source_store`** — `FlowJobRunner.seedsOf` throws on zero; multi-source merge is the
  `transform.merge` path (the Phase-A "exactly one" rule was relaxed in Phase C). See
  [flow live execution](../flow-graph/live-execution.md).
