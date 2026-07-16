---
type: Feature
title: Runs (Operations)
description: The ingest-operations list — one row per running Pipeline — with run-now/pause/history actions; rows open the run detail.
resource: inspecto-ui/src/app/modules/admin/runs/runs.routes.ts
tags: [feature, runs, operations, workbench]
timestamp: 2026-07-07T00:00:00Z
---

# Runs (Operations)

Route `/runs` (Workbench nav group), dir `modules/admin/runs/`. The operations view over executing
Pipelines: a **standard** [data-table](../design-system/data-table.md) (`autoHeight`) of the `RunView` /
`RunResult` / `RunStatus` models with per-row run-now/pause/history actions; row-click opens
[run detail](run-detail.md) (`/runs/:name`). Backed by `RunsService`.

**Run now** follows the v1 async contract (W5b): the trigger returns `202` + `{runId, …}` and the pane shows
a `Run "<name>" started.` toast — the refreshed list (auto-refresh via `visibleInterval`) shows the outcome
rather than blocking on it.

**Business lens is read-only observe here** (product decision 2026-07-03): trigger/pause/resume/reprocess
are hidden *and* method-guarded on `lens.readOnly()` (defense-in-depth) — a deliberate pane-specific
exception to the Jobs precedent, where run-now/enable stay operational in every lens. New panes default to
the Jobs heuristic unless the plan says "read-only observe" this explicitly.
