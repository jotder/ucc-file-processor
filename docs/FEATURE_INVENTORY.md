# Feature & Example Inventory — advanced reference

> **What this is.** A map of *every user-facing engine feature* an example/config can exercise —
> its TOON config shape and where it's defined (doc + code) — plus the existing examples, how the
> release bundle is assembled, what a config minimally needs to run, and a recommended example set.
> Written for advanced users and for anyone extending the worked-example suite in
> [`../inspecto/examples/`](../inspecto/examples).
>
> **Point-in-time snapshot** (compiled 2026-06-20). Status tags — `[LIVE]` shipped, `[NATIVE]`/
> `[PROPOSED]` not yet wired — and `file:line` references reflect the tree at that date; **verify
> against current code** before relying on a specific line. The authoritative living docs are
> [`configuration.md`](configuration.md) (all TOON keys), [`parsing-options-reference.md`](parsing-options-reference.md)
> (frontend status), and [`ADVANCED_GUIDE.md`](ADVANCED_GUIDE.md) (runtime flags, events, metrics, Control API).

---

## 1. Feature inventory

### A — Stage-1 ingest: M..N multiplexer basics

| Feature | TOON skeleton | Doc / code |
|---|---|---|
| `active:` gate (default false = never runs) | `name: X` / `active: true` | `configuration.md` · `SourceService` poll filter |
| M..N parallelism (sources × batches) | `processing:` / `  threads: 4` | `configuration.md` · `MultiSourceProcessor` + `Semaphore(maxConcurrentRuns)` |
| `batch.max_files` / `batch.max_bytes` | `processing:` / `  batch:` / `    max_files: 500` | `BatchPlanner` |
| Multi-source (many `active: true` pipelines in one service) | one `*_pipeline.toon` per source under `config/` | `ADVANCED_GUIDE §3` · `SourceService.runAllOnce` |
| File-pattern glob | `processing:` / `  file_pattern: "glob:**/*.{csv,csv.gz}"` | `configuration.md` |

### B — Parsing frontends

| Feature | TOON skeleton | Status / code |
|---|---|---|
| Delimited CSV (basic) | `csv_settings:` / `  delimiter: ","` | `[LIVE]` `CsvIngester` |
| Delimiter variants (pipe, semicolon) | `csv_settings:` / `  delimiter: "\|"` | `[LIVE]` (single literal char) |
| `engine: duckdb` (fast path, clean files) | `csv_settings:` / `  engine: duckdb` | `[LIVE]` `configuration.md` |
| `engine: java` (SQL*Plus spool dumps) | `csv_settings:` / `  engine: java` / `  skip_junk_lines: -1` / `  skip_tail_lines: 2` | `[LIVE]` `CsvIngester` |
| `has_header: false` | `csv_settings:` / `  has_header: false` | `[LIVE]` |
| `skip_tail_columns` (strip trailing metadata) | `csv_settings:` / `  skip_tail_columns: 3` | `[LIVE]` (forces Java engine) |
| `.csv.gz` / `.bz2` / `.zip` compression | `file_pattern: "glob:**/*.{csv,csv.gz,csv.bz2}"` | `[LIVE]` `Compression` |
| External grammar file (reusable `*.grammar.toon`) | `processing:` / `  grammar: config/x/x.grammar.toon` | `[LIVE]` |
| Fixed-width text (`frontend: fixedwidth`, `record: line`) | `frontend: fixedwidth` / `fixedwidth:` / `  fields[N]{name,start,length}:` | `[LIVE]` `configuration.md` |
| Fixed-length binary (`record: bytes`) | `processing:` / `  ingester: …FixedWidthRecordIngester` / `  ingester_config: { record_length: 256 }` | `[LIVE]` `parsing-options-reference.md` |
| Plugin/segments (multi-event-type, e.g. CALL/SMS) | `processing:` / `  ingester: com.acme.MyIngester` / `  segments: { CALL: …, SMS: … }` | `[LIVE]` `StreamingPluginBatchStrategy` |
| Multi-schema dispatch (column-count + filename glob) | `processing:` / `  schemas[N]{column_count,file_pattern,schema_file,table}:` | `[LIVE]` `configuration.md` · voucher pipeline |
| `FILENAME_DATE` transform (date in filename) | `rules[1]{…}: EVENT_DATE,FILENAME\|prefix_,FILENAME_DATE` | `[LIVE]` `TransformCompiler` |
| JSON / NDJSON frontend | `parsing:` / `  frontend: json` | `[PROPOSED]` — not yet wired |
| `text_regex` frontend (LDIF, flat XML) | `parsing:` / `  frontend: text_regex` | `[PROPOSED]` — not yet implemented |

### C — Schema & validation

| Feature | TOON skeleton | Doc |
|---|---|---|
| Field types (VARCHAR/DATE/TIMESTAMP/DOUBLE) | `raw:` / `  fields[N]{name,selector,type}:` | `configuration.md` |
| `DIRECT` pass-through rule | `rules[1]{…}: AMT,AMT,DIRECT` | `configuration.md` |
| `EXPR` (arbitrary DuckDB scalar) | `RESULT,"CASE WHEN CODE='0' THEN 'OK' ELSE 'FAIL' END",EXPR` | `configuration.md` |
| `CONCAT_DT` (date+time cols → timestamp) | `TS,"DATE_COL\|TIME_COL",CONCAT_DT` | `configuration.md` |
| Multi-format date parsing | `date_formats[3]: %d-%b-%y, "%d-%b-%Y %H:%M:%S", "%Y%m%d"` | `configuration.md` |
| Reject routing / quarantine | automatic — schema/structural mismatch → `quarantine/field_mismatch/` or errors CSV | `ADVANCED_GUIDE §5.2` · `QuarantineManager` |
| Field metadata (PII/INTERNAL classification) | `fields[1]{name,selector,type,description,unit,classification}:` | subscriber schema |

> **Gotcha:** type-cast failures are `TRY_CAST` → NULL (row still written); only **structural**
> errors (wrong column count) are rejected to `errors/<base>_errors.csv`.

### D — Transforms (Stage-1) & Stage-2 enrichment

| Feature | TOON skeleton | Doc |
|---|---|---|
| Stage-1 per-record scalar transform | see `EXPR` above | `configuration.md` |
| `partitionKey` shorthand (→ year/month/day) | `partitionKey: EVENT_DATE` | `configuration.md` |
| Explicit `partitions[]` (multi-column) | `partitions:` / `  - { column: event_type, source: EVENT_TYPE, type: VARCHAR }` | `configuration.md` |
| Stage-2 enrichment (`*_enrich.toon`) | `name: KPI` / `input: {database: …}` / `references: {…}` / `output: {…}` / `transform: "SELECT … GROUP BY …"` | `configuration.md` · events_daily_kpi |
| Enrichment reference join | `references:` / `  region_dim: { path: ref/region_dim.parquet, format: PARQUET }` | events_daily_kpi |

### E — Output / sinks

| Feature | TOON skeleton | Doc |
|---|---|---|
| Parquet + snappy (default) | `output:` / `  format: PARQUET` / `  compression: snappy` | `configuration.md` |
| Parquet + zstd / gzip | `output:` / `  compression: zstd` | `configuration.md` |
| CSV output | `output:` / `  format: CSV` | `configuration.md` |
| Hive partitioning (automatic) | output always `year=/month=/day=` under `dirs.database` | `ADVANCED_GUIDE §8` · `PartitionWriter` |
| DuckLake (PostgreSQL catalog) | `output:` / `  ducklake: { enabled: true, catalog_url: "postgresql://…" }` | `configuration.md` · adjustment pipeline |
| DuckDB scratch/memory tuning | `processing:` / `  duckdb: { temp_directory: temp/big, memory_limit: "16GB" }` | `configuration.md` |
| Auto-chunking (huge single files) | `processing:` / `  chunking: { max_file_bytes: 5000000000 }` | `configuration.md` |

### F — Jobs (`*_job.toon`)

Types: `enrich`, `report`, `maintenance`, `flow` (`JobConfig.load()`).

| Feature | TOON skeleton | Doc |
|---|---|---|
| Enrich job (cron) | `job: { name: daily-kpi, type: enrich, cron: "0 2 * * *", enabled: true }` | `ADVANCED_GUIDE §5.4` |
| Enrich job (`on_pipeline` event) | `job: { name: …, type: enrich, on_pipeline: events }` | `EnrichmentConfigTest` |
| Maintenance (cleanup, cron+event) | `job: { name: …, type: maintenance, cron: …, task: cleanup, retention_days: 30 }` | `JobConfigTest` |
| Report job (`enabled: false`) | `job: { name: …, type: report, enabled: false, scope: status }` | `JobConfigTest` |
| `catch_up: true` (missed-fire recovery) | `job: { …, cron: "0 * * * *", catch_up: true }` | `JobServiceTest` |
| Flow job (`type: flow`) | `job: { name: …, type: flow, flow: cdr_flow, on_pipeline: events }` | `ADVANCED_GUIDE §5.3` |
| Manual trigger only | `job: { name: …, type: report, enabled: true }` → `POST /jobs/{n}/trigger` | `ADVANCED_GUIDE §5.4` |

> **Gotcha:** `on_pipeline:` matches the **lowercased** pipeline name (`BatchEvent.pipeline()`).

### G — Authored flows (`*_flow.toon`)

Node types from `FlowNodeTypes.catalog()`. (No `*_flow.toon` file exists in the repo yet — the
authored-flow TOON shape lives only in test Java strings: `ControlApiFlowCrudTest`,
`FlowJobRunnerTest`. `FlowCodec` round-trips `nodes[id,type,name?,description?,use?,config?]` +
`edges[from,rel,to]` + `name` + `active`.)

| Node | Fragment | Doc |
|---|---|---|
| `transform.filter` | `- { id: keep, type: transform.filter, where: "amount > 0" }` | `flow-graph-design.md` |
| `transform.route` (case / clone) | `mode: case` (exclusive) or `mode: clone` (fan-out); `routes: [{rel,where},{rel,default:true}]` | `flow-graph-design.md` |
| `transform.derive` | `fields: [{name,type:EXPR,expr}]` | `flow-graph-design.md` |
| `transform.select` | `columns: [a,b,c]` | `flow-graph-design.md` |
| `transform.validate` | `check: "amount > 0"` → `valid` + `invalid` edges | `flow-graph-design.md` |
| `transform.dedup` | `key: [call_id]` | `flow-graph-design.md` |
| `transform.split` | UNNEST | `flow-graph-design.md` |
| `transform.merge` (fan-in) | `inputs: [a,b]` / `sql: "SELECT … JOIN … USING(id)"` | `flow-graph-design.md` |
| `sink.persistent` | `store: events` | `flow-graph-design.md` |
| `sink.view` (no bytes; `derived_sql` registered) | `store: active_subs` / `source_store: subscriber` | `ADVANCED_GUIDE §5.3` |
| `incremental_column` (flow job watermark) | in the `*_job.toon`: `flow: f` / `incremental_column: event_dt` | `ADVANCED_GUIDE §5.3` |

### H — Connections (`*_connection.toon`)

| Feature | TOON skeleton | Doc |
|---|---|---|
| SFTP (key auth + bastion tunnel) | `connection: { id, connector: sftp, host, username, password: "${ENV:…}", tunnel: { host, username } }` | connections/cdr_sftp |
| FTPS (TLS) | `options: { tls: explicit }` | `data_acquisition_framework.md` |
| SSH host-key pinning | `options: { host_key: "ssh-rsa AAAA…" }` or `{ known_hosts: … }` | `data_acquisition_framework.md` |
| FTP passive port range (NAT) | `options: { passive_ports: "10000-10100" }` | `data_acquisition_framework.md` |
| DB-export source (JDBC + watermark) | `connection: { connector: db, options: { watermark_column: updated_at } }` | `data_acquisition_framework.md` |
| Secret via env / sys property | `password: "${ENV:MY_SECRET}"` / `"${SYS:my.prop}"` | `configuration.md` · `SecretResolver` |

### I — Acquisition (`source:` block)

| Feature | TOON skeleton | Doc |
|---|---|---|
| Stability gate (no half-written files) | `source:` / `  stability: { window: 30s, size_checks: 2 }` | `configuration.md` |
| Ready-marker short-circuit | `source:` / `  stability: { ready_marker: "{name}.done" }` | `configuration.md` |
| PATH dedup (default) | `source:` / `  duplicate: { mode: PATH }` | `configuration.md` |
| CHECKSUM dedup (SHA256) | `source:` / `  duplicate: { mode: CHECKSUM, algorithm: SHA256, on_change: REPROCESS }` | `configuration.md` |
| Incremental high-watermark | `source:` / `  incremental: { watermark: last_modified }` / `  duplicate: { mode: METADATA }` | `configuration.md` |
| Sequence-gap detection | `source:` / `  gap_detection: { enabled: true, sequence: "CDR_{yyyyMMddHH}" }` | `configuration.md` |
| `guarantee: EXACTLY_ONCE` | `source:` / `  guarantee: EXACTLY_ONCE` (+ CHECKSUM dedup) | `configuration.md` |
| Parallel fetch + rate limit | `source:` / `  fetch: { parallel_fetch: 8, rate_limit: 50MBps }` | `configuration.md` |
| Retry + backoff | `source:` / `  retry: { count: 5, backoff: EXPONENTIAL, initial_delay: 30s }` | `configuration.md` |
| Circuit breaker | `source:` / `  circuit_breaker: { failure_threshold: 5, cooldown: 5m }` | `configuration.md` |
| Post-action (MOVE/DELETE/RENAME) | `source:` / `  post_action: { on_success: MOVE, archive_path: archive/yyyy/MM/dd }` | `configuration.md` |
| Regex include/exclude | `source:` / `  include[1]: "regex:CDR_[0-9]{8}.*\\.csv"` | `configuration.md` |

### J — Operational intelligence

| Feature | TOON skeleton / flag | Doc |
|---|---|---|
| Alert rule (`*_alert.toon`) | `alert: { name, metric: error_rate, comparator: gt, threshold: 0.05, window: 1h, severity: WARNING, onPipeline: events }` | `ADVANCED_GUIDE §5.5` · `AlertRule` |
| Alert metrics | `error_rate`, `failed_batches`, `rejected_files`, `duration_ms` | `AlertService` |
| Alert batch window | `window: 20b` (last 20 batches) | `AlertRuleTest` |
| Gap → ALERT object (auto) | via `EventObjectBridge` when objects backend on | `ADVANCED_GUIDE §5.5` |
| Durable events (Parquet) | `-Devents.backend=parquet -Devents.dir=inspecto-events` | `ADVANCED_GUIDE §9` |
| DB-backed objects | `-Dobjects.backend=db` | `ADVANCED_GUIDE §9` |
| DB-backed job runs | `-Djobs.backend=duckdb` | `ADVANCED_GUIDE §9` |
| Provenance data plane | `-Dprovenance.backend=duckdb` | `ADVANCED_GUIDE §5.3` |
| RCA templates (`*_rca.toon`) | consumed by `GET /rca/templates`; **no example file/shape in the tree yet** | `ADVANCED_GUIDE §10` (gap) |

### K — Views, metrics, events (serve mode)

`GET /metrics` (Prometheus) · `GET /events` / `/events/search` · `GET /views` / `/views/{name}/data`
(needs a flow `sink.view` + `-Dassist.write.root`) · `GET /catalog` / `/catalog/graph` (lineage) ·
`GET /sources` (current DB watermark).

---

## 2. Existing example configs (under `spaces/<id>/config/` — `ucc` hosts voucher; `default` hosts subscriber, events, connections)

| File | Demonstrates |
|---|---|
| `adjustment/adjustment_pipeline.toon` | Full pipeline: CSV+GZ, Oracle date formats, `skip_junk/tail_lines`, PARQUET+snappy, DuckLake stub, `batch.max_files` |
| `adjustment/test_pipeline.toon` | Minimal CSV-output pipeline (no `markers:`) |
| `voucher/voucher_pipeline.toon` | Multi-schema dispatch (76/116/537 cols), filename-glob fast path, external grammar, `duckdb_threads: 0` |
| `voucher/voucher.grammar.toon` | External delimited grammar (`has_header:false`, multi-format dates) |
| `voucher/voucher_{76,116,537}.toon` | Schema variants incl. a 537-col wide schema |
| `subscriber/subscriber_pipeline.toon` | Fixed-width pipeline (`.dat`), external grammar + schema |
| `subscriber/subscriber.grammar.toon` | `frontend: fixedwidth`, slice layout |
| `subscriber/subscriber_schema.toon` | Schema with `description`/`unit`/`classification` |
| `events/call_schema.toon` | Plugin-segment schema (CALL) |
| `events/events_daily_kpi.toon` | Stage-2 enrichment (input/output/references/transform SQL) |
| `events/events_meta.toon` | Catalog metadata (tables, KPIs, reports) |
| `connections/cdr_sftp_connection.toon` | SFTP: key auth, bastion tunnel, `${ENV:…}` secret |
| `connections/local_demo_connection.toon` | Local/demo SFTP stub |

**Sample input data** lives under each space's own `data/` dir (`spaces/<id>/data/…`, created on first
run; gitignored). No committed sample data exists for subscriber `.dat`, events/CALL, connections, jobs, or flows.

---

## 3. Packaging (`inspecto/package.ps1`)

Builds `file-processor-deploy.zip`. Bundle layout:

```
file-processor-deploy/
  file-processor.jar          shaded fat JAR
  spaces/{default,ucc}/       per-space config trees + space.toon (bundled verbatim; configs use
                              repo/bundle-root-relative spaces/<id>/… paths — no rewrite, no flat config/)
  examples/                   the runnable example suite (run-example.ps1|sh + catalog)   ← added 2026-06-20
  run.(bat|sh)                one-shot ETL launcher
  serve.(bat|sh)              long-running ControlApi + UI
  ura.(bat|sh)                utility CLI (com.gamma.util.MainApp)
  ui/                         Angular SPA (if built)
  runtime/                    trimmed jlink JVM (Windows; unless -NoRuntime)
  docs/  README.md            docs tree (../docs/ links rewritten)
```

Run from the bundle:

```
run.bat <adapter>                          one-shot ETL (Windows)   |  bash run.sh <adapter>
set CONTROL_TOKEN=secret && serve.bat      control plane + UI :8080 |  CONTROL_TOKEN=secret bash serve.sh
pwsh examples/run-example.ps1 01-ingest/hello-csv                   |  bash examples/run-example.sh …
```

The launchers bake in `--enable-native-access=ALL-UNNAMED` (mandatory; DuckDB JNI). `-Dassist.write.root`
is **not** set by default → write APIs (connections/flows/registry) return 503 until the user adds it.

---

## 4. Runnability constraints (for a clean offline run)

1. `active: true` (default false ⇒ nothing runs).
2. All `dirs.*` must exist on disk (the example runner pre-creates them).
3. At least one matching file in `dirs.poll`. **The poll dir is consumed** — files move to `backup/`
   (success) or `quarantine/` (failure). Keep pristine samples elsewhere and seed a throwaway inbox.
4. `--enable-native-access=ALL-UNNAMED` (else JVM crash on DuckDB init).
5. A `DATE`/`TIMESTAMP` column **requires** `date_formats`/`timestamp_formats` — empty lists generate
   broken SQL. ISO dates → `%Y-%m-%d`.
6. **No `#` comments** in `.toon` (parser stops at the first `#`).
7. Service/write APIs need `-Dassist.write.root=.` added manually.

---

## 5. Recommended example set (build plan)

Layout: `examples/<NN>-<category>/<name>/` — each with `*.toon` + a tiny `samples/` + its own `out/`.
**Phase 1 (`[done]`) is built, verified, and shipped** in [`../inspecto/examples/`](../inspecto/examples);
the rest are planned in subsequent phases. Features that can't run offline (remote connections, the
`[PROPOSED]` frontends, custom-class plugins, RCA) ship as labeled `_reference/` templates.

**A. Ingest** — hello-csv `[done]` · multi-source · active-gate.
**B. Parsing** — pipe-delimited `[done]`, no-header `[done]`, compressed-gzip `[done]`, fixedwidth `[done]`; csv (duckdb engine), sqlplus-dump (java engine), fixedwidth-binary `[ref/plugin]`, plugin-segments `[ref/plugin]`, multi-schema-dispatch.
**C. Schema & transforms** — expr-transform `[done]`, reject-routing `[done]`; type-casting, concat-dt, filename-date.
**D. Output** — csv-output `[done]`; parquet-compression (snappy/zstd/gzip), large-file-chunking.
**E. Acquisition** — stability-gate, dedup-path, dedup-checksum, incremental-watermark, gap-detection; sftp-with-retry `[ref]`, post-action-move `[ref]`.
**F. Jobs** — enrich-on-commit, enrich-cron (+catch_up), maintenance-cleanup, flow-job-on-pipeline.
**G. Flows** — filter-route, merge-two-sources, sink-view.
**H. Stage-2** — daily-kpi enrichment.
**I. Ops intelligence** — alert-error-rate, alert-gap-sequence, durable-events.
**_reference** — SFTP/FTP/FTPS/DB connections, JSON/text_regex frontends, plugin ingester, RCA template.

---

## 6. Known gaps

- No `*_flow.toon` or `*_rca.toon` example exists in the repo — the shapes live only in tests
  (`ControlApiFlowCrudTest`, `FlowJobRunnerTest`) or are consumed by APIs without a file fixture.
- `json` / `text_regex` frontends are `[PROPOSED]` — example configs can be drafted but won't run yet.
- No subscriber `.dat` / plugin-binary sample data in the repo — synthesize for those examples.
- `package.ps1` pre-creates inbox/database dirs only for `adjustment` + `voucher`; the example suite
  sidesteps this by being self-contained (each example owns its `out/`).
