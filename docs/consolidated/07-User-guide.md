---
metadata:
  document_id: 07-USER-GUIDE
  title: User Guide
  last_updated_date: 2026-06-13
  sources_used:
    - inspecto/README.md
    - docs/configuration.md
    - docs/plugins.md
    - docs/operator-console.md
    - docs/parsing-options-reference.md
    - docs/delimited-grammar-design.md
    - docs/integrations.md
    - docs/troubleshooting.md
    - inspecto-ui/docs/ui-components.md
  open_questions:
    - None blocking.
  assumptions_made:
    - Deployment/runbook detail lives in 06-Operations; this guide covers the end-user "how do I onboard, configure, run, operate, and query" path and links across.
---

# User Guide

A path from a clean checkout to a running, observable pipeline, then into the optional layers.

## 1. Build & install

```powershell
cd inspecto
mvn clean package      # → target/file-processor-<version>.jar (~90 MB, all deps bundled)
```
Requires Java 25+ and Maven 3.9+. Build the whole reactor (`mvn clean package` at the repo root) to
include the optional assist agent. Full build/deploy detail: [06-Operations](06-Operations.md).

## 2. Onboard a new source

A source is three config files under a space's `config/<source>/` directory (`spaces/<id>/config/<source>/`). Only the first is hand-authored.

| File | Authored | Purpose |
|---|---|---|
| `<source>_gen.toon` | by hand | how `SchemaExtractor` reads the sample (delimiter, junk/tail skipping, which columns are dates/timestamps) |
| `<source>_schema.toon` | generated | the transform: `raw.fields[]`, `mapping.rules[]`, `partitions[]` |
| `<source>_pipeline.toon` | generated | runtime: dirs, output format, threads, dedup, pre-ETL sections |

Generate from a sample:
```powershell
ura.bat create-schema <source> path\to\sample.csv config\<source>\<source>_gen.toon   # Windows
./ura.sh create-schema <source> path/to/sample.csv config/<source>/<source>_gen.toon   # Linux/Mac
```

> **`.toon` gotchas:** no `#` comments (parsing stops at the first one); quote any value containing
> `:` (Windows paths, JDBC URLs); the map-vs-tabular array choice is load-bearing — author scalar
> lists with the tabular form `key[N]: v1, v2`, never YAML-style `["a","b"]`. The Smart Config
> serializer handles these when configs are written programmatically.

## 3. Configuration reference (essentials)

### 3.1 Generation config (`*_gen.toon`)
`csv_settings`: `delimiter`, `engine` (`auto`/`duckdb`/`java`), `skip_header_lines`,
`skip_junk_lines` (a *cap* for the adaptive preamble scan; `-1` = unlimited), `skip_tail_lines`,
`skip_tail_columns`, `has_header`, `date_formats[]`, `timestamp_formats[]`; plus `type_patterns`
listing columns to force to DATE/TIMESTAMP.

`engine` is a pure performance lever (identical output on clean files): `auto` uses native DuckDB
`read_csv` when all skip knobs are 0 (the Java parser otherwise); `duckdb` always native (4–5×
faster); `java` always the line-by-line parser. The validator warns if you force `duckdb` alongside
`skip_tail_columns > 0`.

### 3.2 Schema config (`*_schema.toon`)
- **Partitions:** `partitionKey: COLUMN` shorthand (derives year/month/day) **or** an explicit
  `partitions[]{column,source,type}` list (plugin / multi-type). `DATE_YEAR`/`DATE_MONTH`/`DATE_DAY`
  parse with `timestamp_formats` when the source field is `TIMESTAMP`, else `date_formats`;
  unparsed → the `1900/01/01` sentinel.
- **`raw.fields[]`** bind output field ← source column by zero-based `selector` + declared type.
- **`mapping.rules[]` / `transformType`** (optional, blank/omit ⇒ `DIRECT`):

| Value | `sourceExpression` | Description |
|---|---|---|
| `DIRECT` (default) | column name | pass-through with a type cast from the field's declared type |
| `EXPR` | any DuckDB **scalar** expr | emitted verbatim; full scalar library (`UPPER(TRIM(x))`, `TRY_CAST(a AS DOUBLE)/100.0`, `CASE …`); per-row only |
| `CONCAT_DT` | `DATE_COL\|TIME_COL` | concat into one TIMESTAMP |
| `FILENAME_DATE` | `COL\|PREFIX[\|FORMAT]` | extract an 8-digit date from a filename column (restricted to `EVENT_DATE`) |

**Three levels of extension:** (1) a mapping rule incl. `EXPR` (config, no code); (2) a new named
`transformType` in `TransformCompiler` (engine code, reusable verb); (3) a `StreamingFileIngester`
plugin (non-delimited input). Anything needing more than one row is **Stage-2 enrichment**, not a
transform.

### 3.3 Pipeline config (`*_pipeline.toon`)
`dirs.*` (seven required: poll/database/backup/temp/errors/quarantine/markers; optional
status_dir/log_dir), `output.{format,compression,ducklake}`, `processing.{threads,duckdb_threads,
file_pattern,duplicate_check,schema_file,csv_settings,batch,duckdb,chunking,streaming}`, and the
pre-ETL `search`/`copy_tars`/`backup` sections — all in one file.

- **Multi-schema dispatch:** replace `schema_file:` with `schemas[]{column_count,file_pattern,
  schema_file,table}` — file-pattern fast path, then a column-count probe (max columns over ≤200
  scanned lines). List most-specific patterns first; `""` pattern = probe-only.
- **Output:** `format` CSV/PARQUET; PARQUET `compression` snappy/zstd/gzip.
- **Large files:** `processing.duckdb.temp_directory`/`memory_limit`/`max_temp_directory_size` and
  `processing.chunking.max_file_bytes` (see [06-Operations §11](06-Operations.md)).

### 3.4 Externalized delimited grammar (v4.1)
A pipeline may reference a reusable **`*.grammar.toon`** via `processing.grammar:` (overlaid by any
inline `csv_settings` — inline wins) instead of an inline block. The grammar adds `encoding`,
`compression`, `strict_mode`, `null_strings`, and row filters
(`include_prefixes`/`exclude_prefixes`/`include_regex`/`exclude_regex` + `filter_target_column`),
and a boundary pre-scan can route SQL\*Plus dumps onto the **native** path. (Footer-line dropping
via `skip_tail_lines` stays on the Java path; use an `exclude_regex` like `"rows selected"` to drop
SQL\*Plus footers natively.) *Note: the broader format reference is still catching up to this — see
the Conflict Report.*

## 4. Run a pipeline

```powershell
run-adjustment.bat                                              # bundled sample (Windows)
java -jar inspecto/target/file-processor-<version>.jar config/<source>/<source>_pipeline.toon
```
Drop input files under `inbox/<source>/` (date sub-folders). Already-processed files are skipped via
`.processed` markers in `markers/<source>/` (pruned by `retention_days`, default 90). Wrong-schema
or unreadable files move to `quarantine/<source>/` and are never retried.

Run many sources at once:
```bash
java -cp file-processor.jar com.gamma.inspector.MultiSourceProcessor -Dsources.max=4 config/
```

## 5. Plugin ingester (binary / proprietary / multi-event-type)

Implement `com.gamma.etl.StreamingFileIngester` in the fat JAR — decode the file and `emit()`
records into a `RecordSink`; the framework owns table creation, transform, partitioned write,
lineage, and scratch bounding.

```java
public class MyCdrIngester implements StreamingFileIngester {
  public void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
    try (var decoder = openYourDecoder(file)) {
      for (var record : decoder) switch (record.type()) {
        case CALL -> sink.emit("CALL", record.id(), "CALL", record.date());
        case SMS  -> sink.emit("SMS",  record.id(), "SMS",  record.date());
        case BAD  -> sink.reject(record.typeKey());   // counted as errorRows
        default   -> sink.junk();                      // skipped
      }
    }
  }
}
```
- Either `sink.define(key, columns)` once per segment, or rely on the segment's `raw.fields`. You
  **must** `define` when a `partitions[]` source column is ingester-derived (e.g. `EVENT_TYPE`). Do
  not pass `__src_id` — the framework adds it. Throw → `QUARANTINED_UNREADABLE`; zero records →
  `QUARANTINED_MISMATCH`.
- Wire it in the pipeline toon: `processing.ingester: <FQCN>` + `processing.segments: {KEY:
  schema.toon}` (+ optional `processing.streaming.{large_file_bytes,flush_records}` and
  `ingester_config`). Each segment schema uses the `partitions[N]{column,source,type}` tabular form.
- Reference impl: `com.gamma.ingester.TypedRecordIngester` (~120 lines) for type-tagged text
  records — fork it. Test with rows on **≥2 distinct dates** and **2 members sharing a partition**.

Format decision guide (frontend/backend split): well-formed delimited → `delimited` (native);
SQL\*Plus → `delimited` (Java/native via grammar); JSON → `read_json`; flat XML / key-value / LDIF →
`text_regex` (proposed); fixed-width → `read_text`+substring (proposed) or a byte-slicer plugin;
nested XML / ASN.1 / proprietary binary → a `StreamingFileIngester` plugin.

## 6. Stage-2 enrichment (joins & aggregation)

Define an `*_enrich.toon`: register reference tables + Stage-1 partitions as views, run a `transform`
SQL, write an idempotent partitioned result. Jobs are event-driven (`triggers.on_pipeline`) and/or
schedule-driven (`triggers.schedule_seconds`), per-job locked, self-chaining. Inspect via
`/enrichment*`. Don't want to hand-write the SQL? The `kpi-to-sql` skill drafts + sandbox-validates
it from a business description.

## 7. Schedule jobs

Define a `*_job.toon` (`type`: ingest/enrich/report/maintenance; `cron` 5- or 6-field; optional
`on_pipeline` event trigger). The cron engine supports `*`, ranges, steps, lists, and month/day
names (Vixie-cron dom/dow semantics). List/trigger via `/jobs*`. The `nl-to-schedule` skill turns
"every weekday 6am after adjustment_etl" into a validated JobConfig draft with next-run times.

## 8. The operator console (Inspector)

Open the served SPA (`http://localhost:8080/` in prod; `:4204` in dev) and paste your scoped
token(s) on **Connect** (no username/password). Screens:

- **Dashboard** — KPI tiles, latency percentiles, success/failed doughnut, per-pipeline grid, raw
  `/metrics`; auto-refresh (pauses on hidden tab).
- **Pipelines / Pipeline detail** — grid + Trigger/Pause/Resume/Reprocess/Run-all; tabs for
  Batches (lineage drawer), Files (Pending/Processing/Current-file cards + filters), Lineage,
  Quarantine, Commits, Report (date-range percentile rollup).
- **Jobs** — schedule grid; Run history; Run now; **New schedule** opens the `nl-to-schedule` flow.
- **Enrichment** — per-job Runs/Lineage/Report tabs.
- **Catalog** — Tables (overlay + explain-entity panel), KPIs, Graph (interactive read-only diagram;
  click a node to walk the graph).
- **Config authoring** — spec-driven dynamic form (Draft) + Validate; **Save to server**
  (`assist.write` + write-root) + **Register pipeline** (`CONTROL`); `suggest-config` pre-fills.
- **Diagnoses** — event-driven failure diagnoses (root cause, citations, suggested alert `.toon`)
  with a `diagnose-and-alert` refine panel.
- **AI Assist** — a console over all 7 skills; every result shows answer/confidence/validated/
  citations/raw data; SQL skills have an "include sample rows" toggle. All draft-only, confirm-first.

Scope-awareness: `CONTROL`-only actions disable when only an assist token is held. If the agent
module is absent, assist screens show a friendly "agent not available" and the rest works.

## 9. Using the AI assist agent (API)

Build the whole reactor (so `file-processor-agent` is on the classpath), provide a model (local
Ollama, or a hosted provider in connected builds), and set the `assist.read`/`assist.write` tokens.
```bash
curl -X POST localhost:<port>/assist/nl-to-schedule -H "Authorization: Bearer <assist-token>" \
     -d '{"userText":"every weekday 6am after adjustment_etl","knownPipelines":["adjustment_etl"]}'
```
Response: `{ suggestions, rationale, confidence, validated, data, applyVia? }`. Drafts pass a
deterministic oracle (config parser / sandboxed DuckDB) + repair loop before you see them — so
they're crash-safe and parse-safe. **"Valid" ≠ "correct":** review the surfaced interpretation
(chosen join keys, KPI definition, sample rows) before applying. Configure model routing
(local/hosted, per-tier model ids) on the **Settings → Model Settings** screen or via `GET/PUT
/assist/settings`; API keys are referenced by env-var name, never stored.

## 10. Output, audit & querying

**Output:** Hive-partitioned Parquet/CSV under `database/<source>/.../year=YYYY/month=MM/day=DD/
<table>_out.<ext>`, named after the input file (no UUID noise). Optionally registered into DuckLake.

**Audit (three layers):** per-file status CSV (`status/<source>/`), per-batch summary, input→output
lineage matrix — also queryable via `/pipelines/{name}/{files,batches,lineage}`. Per-run logs in
`logs/<source>/` when `dirs.log_dir` is set. The fsync'd `<pipeline>_commits.log` definitively
answers "did batch X finish?".

**Query the output** with familiar SQL tooling:
- **DuckLake** — remote DuckDB clients `ATTACH` the PostgreSQL catalog and query the Parquet.
- **`pg_duckdb` warehouse** — connect DBeaver/any PostgreSQL client to views in the `warehouse`
  schema; partition pruning is automatic. Setup + the column-aliasing caveat are in
  [06-Operations §12 / §14](06-Operations.md).

When something fails, check the quarantine reason + status CSV +
[06-Operations §14 troubleshooting](06-Operations.md). The `explain-entity` and `diagnose-and-alert`
skills can synthesize a root cause from the same audit data.
