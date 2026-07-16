# Inspecto — Operations & Support Guide (pointer map)

> Audience: operations & support · Status date: **2026-07-07**
> The authoritative ops reference is [`../ADVANCED_GUIDE.md`](../ADVANCED_GUIDE.md) — per-component
> process, events, metrics, persisted state, `-D` flags, the full Control API, and troubleshooting
> playbooks. This page is the orientation layer on top of it.

## Run it

- **Serve** — one JVM: fat JAR + bundled JDK runtime; `serve.sh`/`serve.bat` in the deploy bundle
  hosts API + UI on `:8080` (SPA served by the engine). [`../okf/backend/build-run/operations-reference.md`](../okf/backend/build-run/operations-reference.md).
- **Mandatory JVM flag** — DuckDB needs the native-access flag; the bundle scripts set it. Details:
  [build & run](../okf/backend/build-run/operations.md).
- **Editions** — Personal boots open (no login); Standard needs the IAM/OIDC settings and HTTPS
  keystore. Run the Standard bundle with `-NoRuntime` until the jlink/Nimbus verification closes.
- **Spaces** — everything lives under `spaces/<id>/…`; single-space installs use `default`. Migrate
  legacy flat layouts once with `SpaceMigrator`.

## Watch it

| Signal | Where |
|---|---|
| Liveness / readiness | `GET /health` · `GET /ready` |
| Metrics (Prometheus) | `GET /metrics` — throughput, error rate, lag, plus `inspecto_legacy_api_requests_total` (legacy-API sunset meter) |
| Operational activity | the **Signal ledger** (`/events` page; live tail, saved views, CSV export) |
| Fired alerts → incidents | Alerts / Incidents / Cases panes (SLA, comments, correlation ids) |
| Run health | Runs + Run detail panes; durable run reporting (success rate, p50/p95) |
| Who-did-what | the immutable Audit Log (distinct from the activity stream) |

## Operate it

- **Run now** — jobs and pipelines trigger async: the API answers `202` + a `runId` to poll; the UI
  shows a "run started" toast.
- **Recover** — every batch is idempotent and crash-isolated: reprocess a batch, resume a paused
  pipeline, or replay from quarantine; markers make re-ingest safe.
  Playbooks: [`../ADVANCED_GUIDE.md`](../ADVANCED_GUIDE.md) §troubleshooting ·
  [`../okf/backend/build-run/troubleshooting.md`](../okf/backend/build-run/troubleshooting.md) (DuckDB/pg_duckdb quirks).
- **Config changes** — validated drafts only (`POST /validate` → `/config/write` under the fail-closed
  write root); the safety validator blocks dangerous specs (`422`).
- **Backup / move** — whole-Space zip export/import (dry-run preview), or artifact-level **Metadata
  Bundles** (config only, secrets masked, drift fit-check on import).

## When something's wrong

1. Check the **connectivity banner** story first (backend down ≠ a 503 from a write gate).
2. `GET /health`, then `/metrics` error counters, then the Signal ledger filtered to the pipeline/Source.
3. Failing Run → **Diagnosis** (AI RCA, produces an Incident with a suggested fix) if the agent module
   is present.
4. Escalate with the correlation id — it threads UI → API → runs → audit.
