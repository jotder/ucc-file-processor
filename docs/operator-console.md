# Operator Console (Inspector) — User Guide

**Inspector** is the web console for operating the UCC File Processor. It puts every Control API
operation — and all seven AI assist skills — behind a browser UI, so you can monitor pipelines,
trigger and reprocess batches, schedule jobs, watch enrichment, browse the data catalog, author and
validate configs, and review failure diagnoses without touching `curl`.

This guide is for **operators** (the people using the console). For building/serving the SPA and its
project layout, see [`inspector-ui/README.md`](../inspector-ui/README.md); for the underlying HTTP
routes, see [Operations → Control API](operations.md#control-api--rest-control-plane-controlapi).

---

## Table of contents

- [How it's served (dev vs. prod)](#how-its-served-dev-vs-prod)
- [Connecting — tokens, not a login](#connecting--tokens-not-a-login)
- [The screens](#the-screens)
  - [Dashboard](#dashboard)
  - [Pipelines](#pipelines)
  - [Pipeline detail](#pipeline-detail)
  - [Jobs](#jobs)
  - [Enrichment](#enrichment)
  - [Catalog](#catalog)
  - [Config authoring](#config-authoring)
  - [Diagnoses](#diagnoses)
  - [AI Assist](#ai-assist)
- [Common tasks](#common-tasks)
- [Troubleshooting](#troubleshooting)
- [What the console can't do (yet)](#what-the-console-cant-do-yet)

---

## How it's served (dev vs. prod)

The console is hosted by the **same `ControlApi` process** that exposes the REST API — there is no
separate web server to run.

**Production (single origin).** The deploy bundle (`package.ps1`) builds the SPA and drops it in
`./ui` beside the JAR. Launch the control plane with the bundled script:

```bash
CONTROL_TOKEN=secret ASSIST_TOKEN=secret bash serve.sh     # Linux/Mac
set CONTROL_TOKEN=secret && serve.bat                      # Windows
```

Then open **`http://localhost:8080/`**. The UI calls the API at the same origin, so no CORS is
needed. Deep links (e.g. `/pipelines/adjustment_etl`) resolve to the SPA; unknown **API** paths
still return JSON.

**Development (live, two servers).** Run the backend with CORS enabled for the dev server, then run
the SPA on `:4200`:

```bash
java -Dcontrol.token=dev -Dassist.read.token=dev -Dcontrol.cors=http://localhost:4200 \
     -cp file-processor.jar com.gamma.control.ControlApi config/
cd inspector-ui && pnpm install && pnpm start    # ng serve on :4200, /api proxied to :8080
```

Open `http://localhost:4200/`. The dev proxy forwards `/api/*` → `:8080`, so browser calls stay
same-origin.

---

## Connecting — tokens, not a login

There is **no username/password login**. The backend is secured with scoped **bearer tokens** set
server-side via system properties:

| Token (`-D` property / env) | Scope | Grants |
|---|---|---|
| `control.token` / `CONTROL_TOKEN` | `CONTROL` | Everything — list/trigger/pause/reprocess pipelines, run jobs, plus all read + assist routes (superuser). |
| `assist.read.token` / `ASSIST_TOKEN` | `ASSIST_READ` | Read-only: catalog, config specs, diagnoses, and running assist skills. |
| `assist.write.token` | `ASSIST_WRITE` | Persisting authored configs to disk — the Config screen's **Save to server** button (`POST /config/write`, jailed under the server's `-Dassist.write.root`). |

On the **Connect** screen, paste the token(s) you were issued. They're held in the browser session
(`sessionStorage`) and attached to every request as `Authorization: Bearer …`. The console is
**scope-aware**: if you connect with only an assist token, `CONTROL`-only actions (trigger, pause,
reprocess, run-now, run-all) are disabled rather than failing. Use **Disconnect** in the header to
clear your tokens. A `401` from the backend bounces you back to Connect.

> Tokens are configured by whoever runs the server. If a scope has **no** token configured on the
> server, its routes are locked (`401`) — the platform is fail-closed, never open-by-default.

---

## The screens

The left nav has eight sections. Every screen has a **Refresh** control; several support live
auto-refresh (below).

### Dashboard

The at-a-glance health view. KPI tiles (service ready, pipeline count, paused count, committed
batches, quarantine count, error rate), a bar chart of processing-time percentiles (p50/p95/p99), a
success-vs-failed doughnut, and a per-pipeline status grid. A collapsible panel shows the raw
Prometheus `/metrics` text. **Auto-refresh** can be toggled on (default 15 s); it pauses
automatically when the browser tab is hidden and resumes when you return.

### Pipelines

A grid of every registered pipeline (name, config path, paused state, committed-batch count) with a
toolbar and per-row actions:

- **Trigger** — run the pipeline once now (confirm-first).
- **Pause / Resume** — toggle whether the poll cycle includes it.
- **Reprocess** — replay a specific batch (enter the batch id in the dialog).
- **Run all** (toolbar) — trigger every pipeline once.
- **Open** — go to the [pipeline detail](#pipeline-detail) view.

`CONTROL`-scoped actions are disabled without a control token. Auto-refresh is available and skips
ticks while a reprocess dialog is open.

### Pipeline detail

Tabs over one pipeline's operational data:

- **Batches** — batch-audit rows (status, member/rejected counts, input/output rows, bytes,
  duration). Each row has **Lineage & details** (opens a drawer with the batch summary, its member
  files, and the input→output lineage matrix) and **Reprocess**.
- **Files** — per-file audit with a **processing-status strip**: live **Pending** (files sitting in
  the inbox, not yet processed) and **Processing** (whether the pipeline is mid-ingest) cards, plus
  audit-derived **Processed / Succeeded / Rejected / With-errors** cards that double as filters, a
  **filename search**, and a status filter. *Pending* comes from a read-only inbox scan; *Processing*
  is a pipeline-level flag (ingest is synchronous per cycle — there is no per-file in-flight count).
- **Lineage** — input→output rows; filter by batch id.
- **Quarantine** — files isolated as wrong-schema/unreadable, with the reason.
- **Commits** — the durable committed-batch ledger.
- **Report** — pick a date range to get a percentile + throughput rollup for the window.

### Jobs

Config-driven scheduled jobs (cron / fixed-delay): type, schedule, target pipeline, enabled state,
last status, last run, next fire. **Run history** opens a popup of recent runs; **Run now** triggers
a job immediately (`CONTROL`). **New schedule** opens the `nl-to-schedule` assist flow — describe the
schedule in plain English and get a validated draft with the next run times.

### Enrichment

Stage-2 enrichment jobs. Select a job to see tabs for **Runs**, **Lineage** (filter by run id), and
a date-range **Report**.

### Catalog

The metadata graph, in three tabs:

- **Tables** — every catalog node with its operational overlay (freshness, row count, completeness).
  Click a node for a detail popup (attributes + neighbours, click-through). The detail popup also
  embeds an **explain-entity** assist panel — ask a grounded question about that node.
- **KPIs** — KPI definitions (grain, join keys, inputs).
- **Graph** — traverse from a node by depth/direction/kinds; the subgraph renders as an
  **interactive diagram** (read-only `dxDiagram`) with a colour-coded legend per node kind — click
  a node in the diagram to open its detail popup and walk the graph from there.

### Config authoring

Author or validate configuration without hand-editing `.toon`:

- **Draft** — pick a config type (pipeline / enrichment / job / schema / meta). The form is rendered
  dynamically from the server's spec for that type (dropdowns, tag lists, number/boolean/text fields
  with required markers and hints). Your inputs assemble into a live config preview you can **Copy**.
- **Validate** — check a draft (or a config file by path). Findings render with severity, field path,
  and message; a clean config reports ✓.

> Closing the loop: **Save to server** persists the draft as a `.toon` under the server's
> `-Dassist.write.root` (`assist.write` scope; an existing file prompts before overwriting, and
> ERROR-level findings block the save with the findings shown). For a saved **pipeline** config,
> **Register pipeline** then makes it live (`CONTROL` scope) — picked up on the next poll cycle,
> no restart. When the server has no write root configured, saving returns a clear error and the
> copy-the-preview path still works. The `suggest-config` assist skill can pre-fill a draft.

### Diagnoses

Recent event-driven failure diagnoses (batch, pipeline, severity, root cause, whether heuristic-only,
time, citations). Click a row for the detail drawer, which shows the root cause, citations, and a
suggested alert-rule `.toon` you can copy — plus an embedded `diagnose-and-alert` assist panel,
pre-filled, to refine it into an alert.

### AI Assist

A console over all seven skills (pick one from the dropdown):

| Skill | What you type | What you get |
|---|---|---|
| `kpi-to-sql` | a KPI in business terms | Stage-2 SQL (sandbox-validated) + optional sample rows |
| `report-sql` | a question over the audit ledgers | a read-only query + optional sample rows |
| `nl-to-schedule` | "every weekday 6am after adjustment_etl" | a JobConfig draft + next run times |
| `suggest-config` | a sample + partial config | pre-filled fields + rationale |
| `diagnose-and-alert` | a failure description | severity + root-cause / alert-rule draft |
| `explain-entity` | a question about an entity | grounded plain-language explanation |
| `report-narrative` | a report JSON | a short, extractive narrative |

Every result shows the answer, confidence, whether it was validated, citations/links, and the raw
data. **All skills are draft-only and confirm-first** — nothing is applied automatically. SQL skills
have a *"include sample rows"* toggle.

> Assist requires the optional `file-processor-agent` module on the server's classpath. If it isn't
> present, assist calls return a friendly **"agent not available"** message instead of an error — the
> rest of the console works unchanged.

---

## Common tasks

- **Is anything stuck in the inbox?** Pipelines → open a pipeline → **Files** tab → read the
  **Pending** card. >0 with **Processing = No** means files are waiting for the next poll (or a
  manual trigger).
- **Run a pipeline now:** Pipelines → row **Trigger** (or **Run all**). Watch committed-batch count
  climb; open the pipeline to see the new batch under **Batches**.
- **Replay a bad batch:** Pipeline detail → **Batches** → **Reprocess** (or **Lineage & details** to
  inspect first), enter the batch id.
- **Find a specific file's outcome:** Pipeline detail → **Files** → filename search; click the status
  cards to filter to rejected / errored.
- **Schedule something:** Jobs → **New schedule** → describe it → review the draft and next runs.
- **Draft a KPI's SQL:** Assist → `kpi-to-sql` → describe the KPI → review the SQL + sample rows
  before using it.
- **Diagnose a failure:** Diagnoses → open the row → read the root cause and copy the suggested
  alert rule.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Bounced to **Connect** repeatedly | Token wrong/expired, or the scope is locked on the server (no token configured for it). Re-paste a valid token; check with whoever runs the server. |
| Actions (Trigger/Pause/Reprocess/Run-now) greyed out | You connected with only an **assist** token. Reconnect with a **control** token. |
| Assist screens say "agent not available" | The optional `file-processor-agent` module isn't on the server classpath. Build the whole reactor and restart, or operate without assist. |
| A screen is empty | The backend returned an empty list (e.g. no jobs/enrichment registered) — the console shows an empty state, not an error. |
| Pipeline shows 0 pipelines / a config won't load | The server couldn't load that config (missing schema/grammar file, bad path). Check the server log; fix the config and restart. |
| UI loads but every API call fails in dev | The dev proxy or `-Dcontrol.cors` origin is wrong. Ensure the backend was started with `-Dcontrol.cors=http://localhost:4200` and the proxy targets `:8080`. |

---

## What the console can't do (yet)

Called out so expectations are honest:

- **Save/register needs a server-side write root.** The Config screen's Save/Register buttons
  require `-Dassist.write.root` on the server (fail-closed `503` otherwise) — and Register only
  accepts configs that pass full pipeline validation, so a draft missing required sections (e.g.
  a schema file the spec form doesn't cover) must still be completed on disk.
- **No per-file in-flight tracking** (backend limit). "Processing" is a pipeline-level flag; there
  is no durable per-file in-flight counter (it only matters for very large or stuck files).
