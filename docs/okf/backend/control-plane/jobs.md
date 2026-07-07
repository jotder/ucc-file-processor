---
type: Concept
title: Jobs & Scheduling
description: JobService — cron, event-triggered, and manual jobs, with an off-bus virtual-thread handoff.
resource: inspecto/src/main/java/com/gamma/job/JobService.java
tags: [control-plane, jobs, scheduling, cron, triggers, async-runs]
timestamp: 2026-07-07T00:00:00Z
---

# Jobs & Scheduling

`JobService` (`inspecto/src/main/java/com/gamma/job/JobService.java`) hosts a registry of jobs and a
virtual-thread `workers` executor. Three trigger modes:

* **Cron** — jobs with a `cron` field are armed on the shared `Scheduler`.
* **Event** — jobs with `on_pipeline` subscribe to the `BatchEventBus`; `onBatchEvent` matches a `SUCCESS`
  status + pipeline name, then `submit()`s. This is the **deadlock-safe** path — `submit` hands work to
  `workers` and returns immediately, so the synchronous [event bus](events-metrics.md) never holds
  `ingestLock` across a new run.
* **Manual** — `POST /jobs/{name}/trigger`. The legacy unversioned call stays **synchronous and unchanged**;
  the same route under `/api/v1` is **async** (W5): it returns `202` + `{runId, …}` + a `Location` header,
  the caller polls `GET /jobs/runs/{runId}`, and an `Idempotency-Key` header replays the cached response on
  retry. Pipeline triggers gained the identical async contract in W5b (poll `GET /runs/runs/{runId}`).

`submit()` binds the `space` MDC (for non-default spaces — see [multi-space](multi-space.md)) and runs on a
virtual thread. `runJob()` uses `runner.runExclusiveOrSkip(name, …)` for a non-overlap guarantee (a job
already in flight records `SKIPPED`); different jobs run in parallel. On startup, `catchUpMissedFires()`
replays a single missed cron fire for `catch_up: true` jobs from the durable `jobs_runs.csv` ledger.

`JobType` includes `ENRICH`, `REPORT`, `MAINTENANCE`, and **`PIPELINE`** (authored-Pipeline execution — see
[pipeline live execution](../pipeline-graph/live-execution.md)).
