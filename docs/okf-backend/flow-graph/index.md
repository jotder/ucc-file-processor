# Flow-Graph

The authored-pipeline subsystem: an immutable graph IR, a lift from legacy configs, a validator, and an
executor — layered on top of the [engine](../engine). Authored flows run as [`JobType.PIPELINE`](live-execution.md)
jobs.

# Concepts

* [Design](design.md) - the `PipelineGraph` IR, `PipelineLift`, `PipelineValidator`, `PipelineExecutor`, and the node-type registry.
* [Live execution](live-execution.md) - running an authored flow end-to-end (`PipelineJobRunner`, `source_store` seeds, conservation checks).
