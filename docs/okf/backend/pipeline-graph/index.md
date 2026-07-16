# Pipeline Graph

The authored-**Pipeline** subsystem: an immutable graph IR, a lift from legacy configs, a validator, and an
executor — layered on top of the [engine](../engine). Authored Pipelines run as
[`JobType.PIPELINE`](live-execution.md) jobs (`type: pipeline` in job config).

# Concepts

* [Design](design.md) - the `PipelineGraph` IR, `PipelineLift`, `PipelineValidator`, `PipelineExecutor`, and the node-type registry.
* [Live execution](live-execution.md) - running an authored Pipeline end-to-end (`PipelineJobRunner`, `source_store` seeds, conservation checks).
* [Pipeline graph design (full design)](pipeline-graph-design.md) - the authoritative deep design incl. §14 backlog (moved from `docs/flow-graph-design.md`).
