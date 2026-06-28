---
type: Concept
title: Jobs & Scheduling
description: JobService — cron, event-triggered, and manual jobs, with an off-bus virtual-thread handoff.
resource: inspecto/src/main/java/com/gamma/job/JobService.java
tags: [control-plane, jobs, scheduling, cron, triggers]
timestamp: 2026-06-28T00:00:00Z
---

# Jobs & Scheduling

`JobService` (`inspecto/src/main/java/com/gamma/job/JobService.java`) hosts a registry of jobs and a
virtual-thread `workers` executor. Three trigger modes:

* **Cron** — jobs with a `cron` field are armed on the shared `Scheduler`.
* **Event** — jobs with `on_pipeline` subscribe to the `BatchEventBus`; `onBatchEvent` matches a `SUCCESS`
  status + pipeline name, then `submit()`s. This is the **deadlock-safe** path — `submit` hands work to
  `workers` and returns immediately, so the synchronous [event bus](events-metrics.md) never holds
  `ingestLock` across a new run.
* **Manual** — `POST /jobs/{name}/trigger`.

`submit()` binds the `space` MDC (for non-default spaces — see [multi-space](multi-space.md)) and runs on a
virtual thread. `runJob()` uses `runner.runExclusiveOrSkip(name, …)` for a non-overlap guarantee (a job
already in flight records `SKIPPED`); different jobs run in parallel. On startup, `catchUpMissedFires()`
replays a single missed cron fire for `catch_up: true` jobs from the durable `jobs_runs.csv` ledger.

`JobType` includes `ENRICH`, `REPORT`, `MAINTENANCE`, and **`FLOW`** (authored-flow execution — see
[flow live execution](../flow-graph/live-execution.md)).
