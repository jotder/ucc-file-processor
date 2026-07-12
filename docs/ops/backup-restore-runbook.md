# Backup & Restore Runbook (System Maintenance MNT-5/MNT-6)

**Scope:** per-space config (and optionally DuckDB) backup, verification, and restore — including
restore-into-a-new-space. Everything here runs through the `maintenance` Job Type; no shell scripts.
Plan of record: `docs/superpower/system-maintenance-plan.md`.

## Backup

Author a `maintenance` job with `task: backup` (see the live example
`spaces/demo/config/jobs/config_backup_job.toon`):

```
job:
  name: config_backup
  type: maintenance
  task: backup
  cron: "0 4 * * *"
  dir: spaces/<space>/config
  backup_dir: spaces/<space>/data/backups
  prefix: <space>_config
```

Each run produces `<prefix>_<timestamp>.zip` **plus a sidecar `<archive>.manifest.json`** carrying
the SHA-256 of the archive and of every file inside it, appends one row to the
`maintenance_backups` catalog Dataset (BI-queryable), records Run Artifacts, and emits
`maintenance.backup.completed`. Preview any backup with
`POST /jobs/<name>/trigger?dryRun=true`.

- **DuckDB stores:** run `task: db_maintenance` first (CHECKPOINT merges the WAL across the
  acquisition ledger + job-run + provenance stores), then include `duckdb/` in a backup of the
  space root. Never copy a `.duckdb` file while its owning service is writing without a prior
  CHECKPOINT.
- **Backup retention:** a `task: cleanup` job on the backup dir; ALWAYS set `min_keep` so a
  retention sweep can never delete the last backups
  (`dir: …/backups`, `retention_days: 30`, `min_keep: 5`).

## Verify

`task: backup_verify` with the same `backup_dir` checks the newest archive (or `all: "true"`, or
one named `archive:`) — archive hash first, then every entry hash. A mismatch fails the Run and
emits `maintenance.backup.verify_failed` (CRITICAL) — wire an Alert Rule to it. The demo chains
verification automatically via `on_signal: maintenance.backup.completed`
(`spaces/demo/config/jobs/backup_verify_job.toon`).

## Restore

`task: restore` is fail-closed: the sidecar manifest must be present and the archive hash must
match before a single byte is written; extraction is path-jailed under `target_dir`; every
extracted file is re-hashed against the manifest.

1. **Preview first** — `POST /jobs/<restore-job>/trigger?dryRun=true` reports file count, bytes,
   and every conflict (existing files in the target).
2. A real run **blocks on conflicts** unless `overwrite: "true"` is set.

### Restore into a new space (restore-into-new-environment)

1. Create the space (`POST /spaces` or `spaces/<new>/space.toon`) — this lays down the
   `config/ data/ audit/ duckdb/` axes.
2. Run a restore job: `archive: <backup zip>`, `target_dir: spaces/<new>/config` (empty target →
   zero conflicts).
3. Restart or hot-load: job/pipeline configs register on boot; components are picked up by the
   registry scan.
4. Smoke it: `GET /spaces/<new>/health`, `GET /spaces/<new>/jobs`, one representative pipeline
   trigger.

**Rollback of a bad restore:** the backup taken immediately before restoring (step 0 of any
restore into a *live* space: back up the current `config/` first) is the rollback path — restore
it with `overwrite: "true"`.
