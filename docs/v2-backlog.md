# v2.x Product Backlog — toward a self-operating ETL service

The 1.x → 2.0 work made the engine clean and stable. The 2.x product arc turns the
**tool you run** into a **service that runs itself**: always-on, observable,
UI-driven, and ultimately operated by an embedded agent that generates config and
drives the application from inside.

> This is a proposal — priorities and sequence are mine to start the conversation;
> reorder freely. Status legend: 🔲 not started · 🟡 in progress · ✅ done.

## The vision in one line

A long-running service exposing a control API over a database-backed status store,
surfaced through a UI and a metrics stack, with an embedded operator agent that
onboards sources (sample → config), runs pipelines, and diagnoses failures —
through the same API a human uses.

## Epics

Six epics map to your six plans. Priority is **value × leverage**; the **Depends on**
column is what makes the build order differ from the priority order.

| # | Epic | Your plan | Priority | Depends on |
|---|---|---|---|---|
| **E1** | Status store in a database | "status data in database" | **P0 — foundation** | — |
| **E2** | Service / server mode | "service/server mode" | **P0 — foundation** | E1 |
| **E3** | Control API (interaction surface) | "user interaction" | **P0** | E2, E1 |
| **E4** | Observability | "observability" | P1 | E1, E2 |
| **E5** | Web UI | "user interface" | P1 | E3, E1 |
| **E6** | Embedded operator agent | "embed agent… operate from inside" | **P1 — strategic centerpiece** | E3 (full power: +E1, E4) |

### E1 — Status store in a database  🔲 P0

Move run state out of per-run CSVs/JSON into a queryable DB. This is the backbone:
the API, UI, observability, and agent all read it.

- **Tables:** `pipelines` (config registry), `runs`, `batches`, `files`, `lineage`,
  `quarantine`, `commits` — mirroring today's `_status_`/`_batches_`/`_lineage_` CSVs,
  manifests, and the commit log.
- **Build on:** `BatchAuditWriter`, `ManifestStore`, `CommitLog`, `LineageCollector`
  already produce exactly this data — E1 is largely "write to a DB table instead of
  (or in addition to) a CSV." Keep CSV/commit-log as a fallback sink.
- **Decision needed:** engine. **Recommend Postgres** — already in the stack for
  DuckLake, handles concurrent multi-writer (multi-source) cleanly, and the UI/agent
  want real SQL. Alternatives: DuckDB (in-process, but single-writer awkward for a
  live service) or SQLite (simple, embedded).
- **Size:** M. **Why first:** nothing else is durable/queryable without it.

### E2 — Service / server mode  🔲 P0

Turn poll-once-per-invocation into a resilient long-running service.

- Watch loop / scheduler per pipeline; graceful shutdown; **recovery on startup via
  `CommitLog.committedBatchIds()`** (the ledger exists but nothing reads it yet —
  this finally wires it in).
- Lifecycle: load pipeline registry from E1, start/stop/pause per source, health.
- **Build on:** `MultiSourceProcessor` (the concurrent multi-source runner) becomes
  the service's core loop instead of a one-shot CLI.
- **Decision needed:** embedded HTTP server choice (see E3); process model (single
  service hosting all pipelines vs one per pipeline).
- **Size:** M.

### E3 — Control API  🔲 P0

The REST surface for *all* interaction — humans (CLI/UI) and the agent use the same
endpoints. No backdoors.

- Endpoints: list/CRUD pipelines & configs; trigger/pause/resume runs; query
  runs/batches/files/lineage/quarantine; stream logs; reprocess a batch
  (wraps `ReprocessCommand`); validate a config (wraps `ConfigValidator`).
- **Decision needed:** framework. **Recommend a lightweight embedded server**
  (Javalin / Helidon SE / even the JDK `com.sun.net.httpserver`) over Spring, to
  preserve the "small, few-deps, fat-JAR" ethos. AuthN/Z becomes required here.
- **Size:** M–L.

### E4 — Observability  🔲 P1

- Metrics (Prometheus/Micrometer or JMX): throughput, batch-latency histograms,
  quarantine/error rates, oldest-unprocessed-file age (lag), in-flight batches.
- Structured logs (finish the SLF4J→JSON story) correlated by `run_id`/`batch_id`;
  optional OpenTelemetry traces across the batch lifecycle.
- **Build on:** the per-batch timings already captured in audit rows.
- **Size:** S–M. Can land incrementally alongside E2/E3.

### E5 — Web UI  🔲 P1

Dashboard over E3 + E1: pipeline list & health, run history, lineage explorer,
quarantine browser (with "why did this fail?"), config editor with live validation,
trigger/reprocess controls.

- **Decision needed:** stack (SPA served by the API — React/Svelte — vs server-rendered).
- **Size:** L. Highest effort; pure consumer of E1/E3, so it can lag.

### E6 — Embedded operator agent  🔲 P1 (strategic centerpiece)

An agent that *uses the tool* — generates config and operates the application from
inside, through the Control API (E3) so it has no special privileges and is testable.

- **Capabilities:**
  - **Onboard a source:** sample file → schema + pipeline `.toon`, conversationally —
    infer types, partition key, delimiter, engine; validate before saving.
  - **Operate:** trigger runs, summarize results, reprocess on request.
  - **Diagnose:** read quarantine/error/audit, explain *why* files failed, and
    propose concrete config fixes (e.g. raise `skip_junk_lines`, add a `date_format`),
    with dry-run + human approval before applying.
  - **NL control:** "onboard this feed", "why did last night's voucher run quarantine
    12 files?", "reprocess batch X".
- **Build on:** `SchemaExtractor` (`create-schema`) already does sample → schema +
  pipeline toon — the agent wraps and improves it conversationally rather than
  starting from zero.
- **Decisions needed:** model/provider; in-process vs sidecar; **guardrails**
  (read-only by default, approval gate for config writes & run triggers); an **eval
  harness** for config-generation quality (this is a measurable, testable surface).
- **Early thin slice (high value, low risk):** agent-assisted **config generation**
  from a sample can ship *before* the full server stack — it only needs SchemaExtractor
  + the agent loop, not E2–E5. Good candidate for a parallel spike.
- **Size:** L, but sliceable.

## Suggested sequence (milestones)

- **Phase A — Foundation:** E1 (status DB) → E2 (service mode + recovery).
  *Outcome: an always-on service with durable, queryable state.*
- **Phase B — Surface & signals:** E3 (control API) + E4 (observability).
  *Outcome: everything is controllable and watchable via API/metrics.*
- **Phase C — Operators:** E5 (UI) and E6 (agent), both consumers of A+B.
  *Outcome: humans and the agent operate the service through the same surface.*
- **Parallel spike (anytime):** E6 config-generation slice on top of `SchemaExtractor`.

Rationale: each phase makes the next more valuable. The agent (E6) is the goal, but
it's *most* powerful once it has a control API to act through (E3) and a status DB +
metrics to reason over (E1, E4) — so the foundation isn't a detour, it's what makes
the agent more than a config wizard.

## Cross-cutting (threaded through, not separate epics)

- **AuthN/Z** — required once E3/E5 exist (API tokens / roles).
- **Packaging** — the fat JAR stays; add a service launcher + container image.
- **Config migration** — file-based `.toon` configs ↔ the E1 registry (import/export).

## Decisions needed before Phase A starts

1. **Status DB engine** — Postgres (recommended) / DuckDB / SQLite?
2. **Embedded HTTP server** — Javalin / Helidon / JDK built-in / Spring?
3. **Agent provider & boundary** — which model, in-process vs sidecar, and the
   approval-gate policy for write actions?

## Parking lot (my earlier suggestions, deprioritized per your direction)

- Object storage (S3/GCS) for inbox/output — high real-world value, but not on your
  current path; revisit when deployment target firms up.
- Additional input formats / output sinks via the plugin seam.
- Distributed multi-node execution — still **against** the single-JVM ethos; prefer
  N instances over disjoint inputs.
