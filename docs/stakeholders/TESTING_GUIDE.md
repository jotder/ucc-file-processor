# Inspecto — Testing Guide

> Audience: testers / QA (human or agent) · Status date: **2026-07-16**
> What to test, how to run it, and what "verified" means in this repo. Expected screen-by-screen
> behavior lives in the [`USER_GUIDE.md`](../USER_GUIDE.md); vocabulary per [`GLOSSARY.md`](../GLOSSARY.md).

## The verification ladder

Work is only "verified" at the level you actually ran. From cheapest to strongest:

| Level | Command | Proves |
|---|---|---|
| 1. Backend reactor | `mvn -o clean test` (offline, from repo root) | engine + control-plane unit/HTTP tests |
| 2. UI DoD | in `inspecto-ui/`: `npm run lint:tokens` · `npm run test:ci` · `npm run build` | design-token guard, unit tests, prod build |
| 3. GAUNTLET | levels 1 + 2 together (team macro; agents: use the `build-verify` skill / `verify-runner` agent) | full static + test verify |
| 4. Smoke | boot the real server, probe endpoints (see below) | the artifact actually serves |
| 5. Example suite / seeded spaces | run pipelines end-to-end on sample data | real data flows through |

**Every JVM launch — including tests — needs `--enable-native-access=ALL-UNNAMED`** (DuckDB JNI).
Test runs stand up a real `ControlApi` on an ephemeral port, so failures usually indicate real
HTTP-surface breakage, not fixture drift.

**Baseline discipline:** compare counts against the current baseline and report regressions
verbatim before touching anything. Baseline as of 2026-07-15: reactor **1467 pass / 0 fail**
(3 skipped) · UI **1305 pass / 0 fail** (5 skipped). The current baseline is kept in
`SESSION_STATUS.local.md` after each shift.

## Smoke test (the built thing serves)

Recipe (agents: `SMOKE` invokes `.claude/skills/smoke/`):

1. `mvn -o clean package -q` if `inspecto/target/file-processor-*.jar` is stale.
2. `java --enable-native-access=ALL-UNNAMED -cp inspecto\target\file-processor-*.jar com.gamma.control.ControlApi inspecto\config`
3. `GET /health` must be 200, then probe the endpoints under test; capture status + body.
4. UI e2e (suite: `inspecto-ui/src/e2e/`): `E2E_BASE_URL=http://localhost:8080 npm run test:ci -- --include src/e2e/**`
5. Always stop the server. Evidence = endpoint → status → one-line body; "smoke passed" without
   the probe table doesn't count.

## End-to-end on real data

- **Example suite** — [`inspecto/examples/`](../../inspecto/examples/README.md): self-contained,
  offline, one command each (`pwsh run-example.ps1 01-ingest/hello-csv` or `bash run-example.sh …`).
  Each writes only under its own `out/`; delete and re-run freely. Good for regression-testing a
  single feature (ingest, parsing, schema dispatch, output layout).
- **Seeded demo space** — `spaces/demo/` has one live-verified sample of every authorable kind
  (pipelines, datasets, expectations, alert rules, decision rules, reconciliation, enrichment,
  widgets/dashboards) plus `seed-inbox` / `seed-ops` scripts that push data and operational
  activity through the REAL routes. Catalog + hand-authored-TOON rules: `spaces/demo/config/README.md`.
- **Dev servers** — `.claude/launch.json` defines the backend (`:8080`) and UI dev serve (`:4204`).
  The default UI now targets the real backend; mock flags are opt-in (`environment.ts`).

## What deserves the most attention

- **Config-driven behavior** — most features are authored as TOON under `spaces/<id>/config/`;
  malformed or edge-case configs are the richest bug surface (`ConfigSafetyValidator` should 422,
  never crash the engine).
- **Edition differences** — Personal (HTTP, auth-free) vs Standard (HTTPS, OIDC) are build flavors
  of one codebase; test gates, not forks ([`EDITIONS.md`](../EDITIONS.md)).
- **Multi-space isolation** — anything under `/spaces/{id}/…` must never leak data across spaces.
- **Write gates** — control-plane writes fail closed (503 no write-root → 422 invalid spec →
  403 path jail → 409 conflict); every new route ships with a real-HTTP test class covering the gates.

## Filing what you find

Open items go to [`BACKLOG.md`](../BACKLOG.md) (one line + pointer to detail); cross-cutting
gotchas worth keeping go to the OKF bundle ([`okf/`](../okf/index.md)) or
[`PROJECT_NOTES.md`](../PROJECT_NOTES.md). Update at the shift handoff, not in a side channel.
