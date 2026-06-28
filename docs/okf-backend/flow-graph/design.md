---
type: Concept
title: Flow-Graph Design
description: The FlowGraph IR, PipelineLift, FlowValidator, FlowExecutor, and the FlowNodeType registry.
resource: inspecto/src/main/java/com/gamma/flow/FlowGraph.java
tags: [flow-graph, ir, validator, executor, registry]
timestamp: 2026-06-28T00:00:00Z
---

# Flow-Graph Design

Authoritative doc: `docs/flow-graph-design.md` (incl. the T-checklist).

* **IR** — `FlowGraph` (`inspecto/src/main/java/com/gamma/flow/FlowGraph.java`) is an immutable
  `record(name, active, nodes, edges)` consumed by the executor, validator, and visualiser. `FlowEdge` carries
  a `rel` (defaults to `"data"`) distinguishing record-flow from control edges (`success`/`failure`/
  `unmatched`/`gap`/`on_commit`). `FlowNode` carries `id`, `type`, `name`/`description`, a `cfg` map, and an
  optional `use` [component](../components/component-registry.md) reference.
* **Lift** — `PipelineLift.lift(PipelineConfig)` (`…/flow/PipelineLift.java`) converts a legacy
  `*_pipeline.toon` into a `FlowGraph` with no file rewrite: linear `acq → [dedup] → parse → [filter] → map →
  sink` for single-schema; a `parser` fan-out with `route:<table>` branches for selectors/segments.
* **Validator** — `FlowValidator.validateOrThrow(graph)` (`…/flow/FlowValidator.java`) separates hard
  ERRORs from WARNINGs over the IR alone: DAG over `data` edges (`CYCLE`), no same-graph `on_commit`
  (`ON_COMMIT_SAME_GRAPH`), no dangling endpoints, no duplicate ids, ≥1 entry node, and relationship wiring
  against each node type's `emits`/`accepts`.
* **Executor** — `FlowExecutor.execute(…)` (`…/flow/exec/FlowExecutor.java`) walks `data` edges
  topologically from a seed relation in DuckDB: `RowShaper` compiles each `transform.*` node to SQL; each
  `sink` delegates to an injected `SinkWriter`; source finalisation is gated by a `BranchCommitCoordinator`
  and run via `SourceFinalize`; a `ProvenanceCollector` records per-(node, rel) row counts, checked by
  `ConservationCheck`.
* **Node-type registry** — `FlowNodeTypes` (`…/flow/FlowNodeTypes.java`) is built at class-load from
  `BuiltinNodeType` enum values, then layered with `ServiceLoader<FlowNodeType>` providers (an edition can
  override a built-in). `catalog()` feeds the UI palette + the validator's wiring check.

Supporting: `FlowCodec` (graph ↔ TOON map), `FlowStore` (persists authored `*_flow.toon`), `FlowCompiler`
(round-trips a *lifted* graph back to a `PipelineConfig` map — authored plain-map nodes are not
round-trippable this way).
