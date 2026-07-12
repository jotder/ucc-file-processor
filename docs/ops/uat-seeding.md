# UAT Sample-Data Seeding Runbook

**Purpose:** populate a backend space with enough realistic data for user acceptance testing —
volume in the grids, history in the runs, incidents/cases in the mail UI, backups and reports in
System Maintenance — **without ever writing to a database directly**. Everything flows through the
real engine (pipelines, jobs, control routes), so audit, lineage, run history, signals and the
DuckDB projections are all genuinely produced, which is exactly what UAT should be exercising.

**Tool:** `tools/seed-uat.ps1` (PowerShell; deterministic — same RNG seed → same data).

## What you get (defaults)

| Surface | Seeded content |
|---|---|
| Datasets / BI / Catalog | ~60 days of retail orders (~20–25k rows) with weekend dips, a growth trend, mixed-case regions (exercises the `UPPER()` mapping), 8 products, weighted statuses |
| Runs / batches / lineage / quarantine | Every row ingested through the real `orders` pipeline; 1 file carries 3 malformed rows (rejects), 1 day is silent (fires the `SEQUENCE_GAP` alert) |
| Jobs / Scheduler / Run Log | Ingestion runs, a `storage_report` job registered via the Jobs CRUD, `orders_summary` (sql.template), the nightly maintenance chain fired twice |
| System Maintenance | Backup archives + sidecar manifests, verify runs, maintenance reports, storage-report artifacts (feeds the Overview pane) |
| Incidents / Case Manager | 36 incidents (varied severity/priority/assignee, some acked/resolved, comments, SLA deadlines), 10 cases, incident→case links, 5 tags + 1 auto tag rule |

## Procedure

```powershell
# 1. Build the space + data offline (idempotent with -Force):
pwsh tools/seed-uat.ps1                     # -Days 60 -MinRows 150 -MaxRows 500 by default

# 2. RESTART the backend — spaces are discovered by the boot scan, never adopted live.
#    (dev: restart the 'inspector-backend' launch config)

# 3. Drive the live seeding:
pwsh tools/seed-uat.ps1 -Phase drive        # prints a summary of what landed
```

Then switch the UI's space picker to **UAT**.

## Notes & guardrails

- `spaces/uat/` is **fully generated and gitignored** — delete it (or re-run with `-Force`) to
  reset UAT to a clean, reproducible state. Never commit it; `spaces/demo` stays the pristine,
  minimal, committed catalog.
- The clone rewrites every `spaces/demo` path inside the copied configs to `spaces/uat`; without
  that, the clone would silently read/write the demo space's data.
- **Durable incidents/cases require `-Dobjects.backend=db`** (the dev launch config now sets it);
  on the default in-memory backend the seeded objects vanish on restart — everything else
  (datasets, run history, backups) is file/DuckDB-backed and survives regardless.
- Scaling up: `-Days 180 -MaxRows 2000` for stress-flavored UAT; generation stays quick, ingestion
  time grows with volume.
