---
metadata:
  document_id: 06-OPERATIONS
  title: Operations
  last_updated_date: 2026-06-13
  sources_used:
    - docs/operations.md
    - docs/integrations.md
    - docs/troubleshooting.md
    - docs/configuration.md
    - inspecto/README.md
    - docs/operator-console.md
    - docs/performance.md
    - inspecto-ui/README.md
  open_questions:
    - Dev SPA port / CORS origin is inconsistent in the source operator-console guide (:4200 vs :4204). This doc uses :4204 (the value in README/operations and the UI proxy). See Conflict Report C8.
  assumptions_made:
    - Metric/table names use the current `inspecto_*` scheme; the legacy `ucc_*` names are migrated in place on first DB connect.
---

# Operations

Deployment, environment, observability, CI/CD, and runbooks. The fat JAR bundles all
dependencies — no JVM classpath setup on the target.

## 1. Build & toolchain

```powershell
# Lean core only (artifactId file-processor → file-processor-<version>.jar, ~90 MB):
cd inspecto && mvn clean package
# Whole reactor (core + optional assist agent):
mvn clean package          # at repo root
```

Prerequisites: **Java 25+** (CI pins 25; local dev on 26), **Maven 3.9+**. Reference workstation
toolchain (neither on `PATH` — set per shell):
```powershell
$env:JAVA_HOME='C:\.jdks\openjdk-26.0.1'
$env:PATH="$env:JAVA_HOME\bin;C:\maven\apache-maven-3.9.16\bin;$env:PATH"
```

## 2. Deployment bundle

```powershell
powershell -ExecutionPolicy Bypass -File inspecto\package.ps1            # builds + bundles
powershell -ExecutionPolicy Bypass -File inspecto\package.ps1 -NoBuild   # reuse current JAR
```

Produces **`file-processor-deploy.zip`** in the sandbox root. `package.ps1`: builds the fat JAR,
builds the optional operator UI (`inspecto-ui/` via npm → bundled as `ui/`; skip with `-NoUi`),
assembles JAR + configs + `run`/`serve`/`ura` scripts, rewrites `schema_file` paths relative to the
bundle root, creates placeholder dirs, and zips it.

**Bundle contents:** `file-processor.jar` (~94 MB), `config/<source>/*.toon`, the working dirs
(`inbox`/`database`/`backup`/`temp`/`errors`/`quarantine`/`markers`), `ui/` (when built),
`run.sh`/`run.bat`, `serve.sh`/`serve.bat`, `ura.sh`/`ura.bat`, `warehouse_setup.sql`, README.

```bash
unzip file-processor-deploy.zip && cd file-processor-deploy
# one-shot ETL:
bash run.sh <data_source>                                   # Linux/Mac
run.bat <data_source>                                       # Windows
# always-on control plane + operator console:
CONTROL_TOKEN=secret ASSIST_TOKEN=secret bash serve.sh      # → http://localhost:8080/
# direct invocation:
java --enable-native-access=ALL-UNNAMED -jar file-processor.jar config/<source>/<source>_pipeline.toon
```

`SourceProcessor.main` exit codes: `0` all batches succeeded, no quarantine · `1` invalid
invocation · `2` at least one batch threw. Wrappers/cron should treat non-zero as failure + alert.

## 3. Run modes

| Mode | Entry point | Use |
|---|---|---|
| One-shot single source | `SourceProcessor` (or `run.sh/.bat`) | run a pipeline once |
| Many sources, one JVM | `MultiSourceProcessor -Dsources.max=N config/` | failure-isolated per-source lanes |
| Always-on host | `SourceService -Dservice.poll.seconds=60 config/` | poll loop, batch-commit events |
| Control plane + UI | `ControlApi -Dcontrol.port=8080 -Dcontrol.token=… config/` | REST + optional SPA |

## 4. Concurrency tuning (set both knobs)

Worker pressure ≈ `sources.max × processing.threads × processing.duckdb_threads`.

| Knob | Default | Controls |
|---|---|---|
| `processing.threads` | 4 | concurrent batches (semaphore permits) |
| `processing.duckdb_threads` | 0 = **auto** (`max(1, cores ÷ threads)`) | `PRAGMA threads=N` per batch connection |

Since v3.12.0 `duckdb_threads=0` auto-derives so concurrent batches divide the cores (a single
batch keeps all cores; `-1` opts back into DuckDB's per-core default; positive `N` is verbatim).
**The auto-derive does not know about `sources.max`** — when running many sources in one JVM, set
`duckdb_threads` explicitly (e.g. on 16 cores: `sources.max=4, threads=2, duckdb_threads=2`). The
config validator warns when an explicit `sources.max × threads × duckdb_threads` exceeds cores.

## 5. Control API runbook

Bearer token on every route except public `/health`, `/ready`, `/metrics`. **Fail-closed and
scoped** (v3.0): a scope with no configured token returns `401`. Scopes are hierarchical —
`CONTROL` satisfies everything; `assist.write` satisfies `assist.read`. Constant-time compare.

| Scope | `-D` property / env | Grants |
|---|---|---|
| `CONTROL` | `control.token` / `CONTROL_TOKEN` | superuser: list/trigger/pause/reprocess pipelines, run jobs, all reads + assist |
| `assist.read` | `assist.read.token` / `ASSIST_TOKEN` | `/catalog*`, `/config/spec/*`, `/assist/*`, `/assist/diagnoses` |
| `assist.write` | `assist.write.token` | `POST /config/write` (also gated on `-Dassist.write.root`) |

Common routes: `GET /pipelines` · `POST /pipelines` (register live, v4.1) · `POST
/pipelines/{name}/trigger|pause|resume|reprocess` · `GET /pipelines/{name}/{commits,batches,files,
lineage,quarantine,pending,report}` · `POST /trigger` · `POST /validate` · `GET /status` · `GET
/report` · `GET /jobs[/{name}/runs]` + `POST /jobs/{name}/trigger` · `GET /enrichment[/{job}/
{runs,lineage,report}]` · `GET /catalog*` · `GET /config/spec/{type}` · `POST /assist/{intent}` ·
`GET /assist/diagnoses` · `POST /config/write` (v4.1).

**Authoring → save → register (v4.1).** With `-Dassist.write.root=<dir>`: persist a validated draft
(`POST /config/write`, `assist.write`) then register it live (`POST /pipelines`, `CONTROL`),
picked up on the next poll cycle. Fail-closed: unset write root ⇒ `503`; paths jailed under the
root; ERROR-level findings ⇒ `422`; existing file ⇒ `409` unless `overwrite:true`.

## 6. Serving the operator console (Inspector)

The same `ControlApi` process serves the SPA — no separate web server.

- **Production (single origin):** `-Dui.dir=<built SPA folder>`. The deploy bundle's `serve.sh`
  wires `-Dui.dir=./ui` when a `ui/` folder is present. Unknown GET paths with no extension fall
  back to `index.html` (SPA deep links); unmatched API paths still return JSON; static assets are
  public. Open `http://localhost:8080/`.
- **Development (two servers):** start the backend with `-Dcontrol.cors=http://localhost:4204`, then
  `cd inspecto-ui && npm install && npm start` (ng serve on **:4204**, `/api/*` proxied to `:8080`).

There is **no login** — operators paste scoped bearer token(s) on the Connect screen
(`sessionStorage`); `CONTROL`-only actions are disabled when only an assist token is held. A `401`
bounces to Connect.

## 7. Status backend — file (default) or database

`-Dstatus.backend=db` projects the audit into a DB and serves queries from it; ingest keeps writing
the file audit (write-time source of truth, survives a DB outage). Default engine **DuckDB** (no new
dep; local file `inspecto-status.db` if no URL). Schema created on first connect
(`inspecto_status_{commits,batches,files,lineage,quarantine}`; pre-rebrand `ucc_status_*` renamed
in place; legacy `ucc-status.db` still picked up). Syncs at startup + after every poll cycle
(transactional DELETE-then-INSERT per pipeline — idempotent, doubles as backfill).
```bash
java -cp file-processor.jar com.gamma.control.ControlApi -Dcontrol.token=secret \
     -Dstatus.backend=db -Dstatus.db.url="jdbc:duckdb:/var/lib/inspecto/status.db" config/
```
**Distributed future:** point at `jdbc:postgresql://host:5432/inspecto` + put the PG driver on the
classpath (bring-your-own — not bundled).

## 8. Observability

`GET /metrics` (open) — Prometheus text from a zero-dependency in-process registry. Metrics:
`inspecto_batches_total` (counter, `pipeline`/`status`), `inspecto_output_rows_total`,
`inspecto_rejected_files_total`, `inspecto_partitions_written_total`,
`inspecto_batch_duration_seconds` (histogram), `inspecto_enrichment_recomputes_total` /
`_duration_seconds`, `inspecto_poll_cycles_total`, `inspecto_source_run_failures_total`,
`inspecto_active_runs` (gauge), `inspecto_committed_batches` / `_quarantine_files`,
`inspecto_inbox_oldest_seconds` (lag gauge), `inspecto_paused`. Point-in-time gauges compute lazily
on scrape. The `inspecto.events` logger emits one JSON line per batch (correlatable by `batch_id`).

## 9. Pre-ETL utilities (`ura`)

```bash
bash ura.sh [--dry-run] <command> <pipeline.toon>     # Linux/Mac
ura.bat     [--dry-run] <command> <pipeline.toon>     # Windows
```
Commands: `search` (audit manifest), `copy` (→ `dirs.poll/<date>/`), `copy-tars`, `extract` /
`prepare-inbox` (unpack + arrange by date + backup archives), `backup`, `create-schema`,
`reprocess <batch_id>`. `--dry-run` honoured by all. Date detection: first 8-digit token in
1900–2099; unmatched → `obscure/`. Safety: `dirs.poll` must not nest inside any `base_dirs` entry.

## 10. Reprocessing & crash semantics

`ura reprocess <pipeline.toon> <batch_id>` reads the manifest, deletes the batch's outputs +
partition dirs + `.processed` markers, restores members from `dirs.backup` to inbox, supersedes the
manifest with `REPROCESSED`, and the next poll re-processes. Commit ordering (idempotent on
re-run): (1) DuckLake register (non-fatal) → (2) manifest → (3) backup originals → (4) markers
**last**. A crash before step 4 leaves the file un-marked → re-processed; `OVERWRITE_OR_IGNORE`
makes re-running safe.

## 11. Large-file handling

- **Scratch off `/tmp` (since 3.10.0):** the per-batch temp DB + DuckDB spill default to
  `dirs.temp` on the data volume. Override with `processing.duckdb.temp_directory`; cap with
  `memory_limit` + `max_temp_directory_size` (fail fast, not fill the disk). Budget ~1–3× the
  decoded file size.
- **Auto-chunking:** `processing.chunking.max_file_bytes` streams the native-CSV path in bounded
  chunks (~one chunk peak scratch). Original file stays the audit/marker/backup unit.
- **Plugin (binary/ASN.1) huge files:** the framework runs the `StreamingFileIngester` in
  **generation mode** (member ≥ `processing.streaming.large_file_bytes`, default 256 MB) flushing
  bounded generations; many small files use **union mode**.

## 12. Integrations setup

- **DuckLake:** create a PostgreSQL catalog DB, set `output.ducklake.{enabled,catalog_url,
  data_path,schema,table}`; the ETL `INSTALL ducklake FROM core` → `ATTACH` → create + `INSERT`.
  Registration is **non-fatal**. Remote users connect via the DuckDB JDBC driver + ducklake
  extension against the same catalog (Parquet on a shared path).
- **`pg_duckdb` warehouse:** `apt-get install postgresql-NN-pgduckdb`, set
  `shared_preload_libraries='pg_duckdb'`, restart, apply `warehouse_setup.sql` (substitute
  `DATA_ROOT`), create login roles. Views in a `warehouse` schema; connect with the standard
  PostgreSQL driver; partition pruning automatic.

## 13. CI/CD

- Backend: `.github/workflows/ci.yml` runs `mvn test` on every push; CI builds the **full reactor**
  and has **no GPU** (every assist golden test runs CPU-only against `FakeModelProvider`). To gate
  coverage, add `mvn -Pcoverage test` + a JaCoCo `check` scoped to the engine/agent packages (don't
  let the untested `util` CLI tools set the bar; ~80% line floor).
- UI: `.github/workflows/ui.yml` — a single npm job (`npm ci`, Vitest, prod build); guarded e2e
  backend-smoke runs only when `E2E_BASE_URL` is set.

## 14. Troubleshooting (common failures)

| Symptom | Cause / fix |
|---|---|
| All output in `year=NULL/...` | Partition-key value matches no configured date format → add it to `date_formats`/`timestamp_formats`. |
| ORA-28002 junk leaks into data | `skip_junk_lines` cap too low → raise it or set `-1` (unlimited adaptive scan). |
| Wrong-schema file lands SUCCESS parsed=0 | Old bug; fixed — junk-scan-exhaustion now quarantines. Update the JAR. |
| "directory X must not be inside poll directory" | Move the offending `dirs.*` to be a sibling of `poll`. |
| JVM crash `EXCEPTION_ACCESS_VIOLATION ... VCRUNTIME140.dll` | DuckDB 1.1.1 Windows AVX2 page-boundary bug; worked around by materialising `transformed` before `COPY`. Verify the workaround survives DuckDB upgrades. |
| `No space left on device` / OOM on a huge file | Point `processing.duckdb.temp_directory` at a roomy disk; cap memory/spill; enable `processing.chunking`. Custom formats → confirm generation-mode routing. |
| DuckLake registration fails silently | Check stderr `DuckLake registration failed`: PG host/port/creds, blocked extension download (needs first-run internet), or unwritable `data_path`. |
| `.processed` files block reprocessing | Markers live in `markers/` (mirrors inbox); delete to force reprocess. Stale ones pruned at poll start by `retention_days`. |
| `pg_duckdb` "column X does not exist" | `read_parquet` columns aren't visible to PG's planner — every column must be aliased via `r['col']`. Use `generate_warehouse_views.py` to emit the full DDL. |
| Console bounced to Connect / actions greyed out | Token wrong/expired or scope locked; reconnect (control token for write actions). |
| Console "agent not available" | Optional `file-processor-agent` not on the server classpath; build the whole reactor + restart. |

## 15. Performance reference (single-node, HDD, 4 threads)

Ingest dominates wall-clock (the DuckDB-native stages run at 1–1.4M rows/s; the native `read_csv`
engine is ~4–5× the Java parser, growing with column count). Plugin Appender ingest is ~75× the old
JDBC path. Reference: a 30 × `.csv.gz` source at ~2.3M rows/file ≈ ~19 min/file, ~2.5 hr total. See
the Appendix for baseline metrics.
