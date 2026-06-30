---
type: Concept
title: Flow-Graph Design
description: The PipelineGraph IR, PipelineLift, PipelineValidator, PipelineExecutor, and the PipelineNodeType registry.
resource: inspecto/src/main/java/com/gamma/pipeline/PipelineGraph.java
tags: [flow-graph, ir, validator, executor, registry]
timestamp: 2026-06-28T00:00:00Z
---

# Flow-Graph Design

Authoritative doc: `docs/flow-graph-design.md` (incl. the T-checklist).

* **IR** — `PipelineGraph` (`inspecto/src/main/java/com/gamma/pipeline/PipelineGraph.java`) is an immutable
  `record(name, active, nodes, edges)` consumed by the executor, validator, and visualiser. `PipelineEdge` carries
  a `rel` (defaults to `"data"`) distinguishing record-flow from control edges (`success`/`failure`/
  `unmatched`/`gap`/`on_commit`). `PipelineNode` carries `id`, `type`, `name`/`description`, a `cfg` map, and an
  optional `use` [component](../components/component-registry.md) reference.
* **Lift** — `PipelineLift.lift(PipelineConfig)` (`…/pipeline/PipelineLift.java`) converts a legacy
  `*_pipeline.toon` into a `PipelineGraph` with no file rewrite: linear `acq → [dedup] → parse → [filter] → map →
  sink` for single-schema; a `parser` fan-out with `route:<table>` branches for selectors/segments.
* **Validator** — `PipelineValidator.validateOrThrow(graph)` (`…/pipeline/PipelineValidator.java`) separates hard
  ERRORs from WARNINGs over the IR alone: DAG over `data` edges (`CYCLE`), no same-graph `on_commit`
  (`ON_COMMIT_SAME_GRAPH`), no dangling endpoints, no duplicate ids, ≥1 entry node, and relationship wiring
  against each node type's `emits`/`accepts`.
* **Executor** — `PipelineExecutor.execute(…)` (`…/pipeline/exec/PipelineExecutor.java`) walks `data` edges
  topologically from a seed relation in DuckDB: `RowShaper` compiles each `transform.*` node to SQL; each
  `sink` delegates to an injected `SinkWriter`; source finalisation is gated by a `BranchCommitCoordinator`
  and run via `SourceFinalize`; a `ProvenanceCollector` records per-(node, rel) row counts, checked by
  `ConservationCheck`.
* **Node-type registry** — `PipelineNodeTypes` (`…/pipeline/PipelineNodeTypes.java`) is built at class-load from
  `BuiltinNodeType` enum values, then layered with `ServiceLoader<PipelineNodeType>` providers (an edition can
  override a built-in). `catalog()` feeds the UI palette + the validator's wiring check.

Supporting: `PipelineCodec` (graph ↔ TOON map), `PipelineStore` (persists authored `*_flow.toon`), `PipelineCompiler`
(round-trips a *lifted* graph back to a `PipelineConfig` map — authored plain-map nodes are not
round-trippable this way).
