---
metadata:
  document_id: APPENDIX-SUPPORTING-REFERENCES
  title: Appendix — Supporting References
  last_updated_date: 2026-06-13
  sources_used:
    - docs/api-stability.md
    - docs/test-coverage.md
    - docs/performance.md
    - docs/integrations.md
    - docs/parsing-options-reference.md
    - inspecto/README.md
    - inspecto-ui/README.md
    - inspecto-agent/docs/AGENT_ARCHITECTURE.md
  open_questions:
    - Some third-party version pins differ across source docs (DuckDB JDBC 1.5.2 in current perf docs vs 1.5.2.1 in the 2026-05 batch spec; langchain4j 1.15.1). Latest values are listed; older pins are historical.
  assumptions_made:
    - Baseline metrics are reproduced as measured (DuckDB 1.5.2 / JDK 26 unless noted) and are "non-perishable" reference points, not current SLAs.
---

# Appendix — Supporting References

## A. Glossary

| Term | Meaning |
|---|---|
| **Inspecto** | The product (formerly *UCC File Processor* / *file-processor*). |
| **Inspector** | The optional Angular operator web console (naming under review — see Conflict C2). |
| **M..N multiplexer** | Stage-1: M input files demultiplexed/routed into N partitioned outputs; counts decoupled. |
| **Stage-1 / Stage-2** | Stateless per-record ingest multiplexer / joins-aggregation enrichment over committed output. |
| **`.toon` / JToon** | The canonical config file format (no `#` comments; quote `:`-bearing values; tabular arrays). |
| **Generation / Schema / Pipeline config** | The three per-source `.toon` files (`*_gen` / `*_schema` / `*_pipeline`). |
| **`partitionKey` / `partitions[]`** | Shorthand (derives year/month/day) / explicit partition column list. |
| **`transformType`** | Per-column rule: `DIRECT` / `EXPR` / `CONCAT_DT` / `FILENAME_DATE`. |
| **`StreamingFileIngester` / `RecordSink`** | The plugin SPI for custom/binary formats; `emit`/`reject`/`junk` into a sink. |
| **Union / Generation mode** | Plugin execution modes chosen per batch by file size (many-small consolidate / huge-file bounded flush). |
| **`BatchEvent` / `BatchEventBus`** | Per-batch commit/failure event + sync pub/sub bus. |
| **`StatusStore`** | Pluggable audit backend: `FileStatusStore` (default) / `DbStatusStore` (DuckDB/Postgres). |
| **`CommitLog`** | Durable, fsync'd, append-only "did this batch finish" ledger. |
| **Smart Config (`ConfigSpec`)** | Machine-readable config model for loader + AI + UI; `Finding`s; `ConfigRegistry`; `ConfigCodec`. |
| **Metadata Graph / `*_meta.toon`** | Typed traversable catalog (sources→…→KPIs) + semantic descriptor; served at `/catalog*`. |
| **Assist skill / Capability** | A task-scoped agent unit (post-v4 mapped to the kernel's `Capability`). |
| **SqlGuard / SqlSandbox / SqlOracle / SqlViews** | The locked-down SQL validation stack (`com.gamma.sql`). |
| **RepairLoop** | generate→validate→repair, cap 3, verbatim oracle error fed back. |
| **GroundingGuard / NarrativeGuard** | Deterministic check that every narrated figure traces to source evidence. |
| **agent-kernel** | The reusable agent library (separate repo `com.gamma.agentkernel`); three rings; Inspecto consumes 1.0.0. |
| **Capability / Tool / Evidence / CredibilityTier** | Kernel core abstractions (declarative unit / deterministic compute / provenance / tier). |
| **EscalationPolicy / EscalationRung** | Confidence-driven escalation (`BumpModelTier`/`HumanHandoff`/`Abstain`); Inspecto wires `Abstain`. |
| **DuckLake** | Lakehouse format: Parquet data + PostgreSQL catalog. |
| **`pg_duckdb`** | PostgreSQL extension embedding DuckDB to query Parquet via a standard PG client. |
| **`ura`** | The pre-ETL utility CLI (search/copy/extract/backup/create-schema/reprocess). |
| **Hive partitioning** | `year=YYYY/month=MM/day=DD` directory layout. |

## B. Third-party components & links

| Component | Role | Reference |
|---|---|---|
| DuckDB | Embedded transformation engine (bundled; perf docs cite 1.5.2; batch spec cited 1.5.2.1) | https://duckdb.org · https://duckdb.org/docs/data/csv/overview |
| DuckLake | Lakehouse catalog (Parquet + PostgreSQL) | https://ducklake.select |
| pg_duckdb | DuckDB-in-PostgreSQL extension | https://github.com/duckdb/pg_duckdb |
| univocity-parsers | Java CSV parser (messy-file fallback path) | https://github.com/uniVocity/univocity-parsers |
| JToon | `.toon` config codec | (project dependency) |
| Jackson | JSON (API wire form, manifests) | https://github.com/FasterXML/jackson |
| Gson | JSON sentinels/manifests (historical, 2.11.0) | https://github.com/google/gson |
| commons-compress | Archive/compression handling | https://commons.apache.org/proper/commons-compress |
| LangChain4j | LLM client abstraction (agent module, 1.15.1) | https://docs.langchain4j.dev |
| Ollama | Local model server (default, air-gapped-safe) | https://ollama.com |
| Hosted model providers | Optional connected routing (Anthropic / OpenAI / Gemini) | https://docs.anthropic.com · https://platform.openai.com · https://ai.google.dev |
| Angular 21 | Operator UI framework | https://angular.dev |
| Angular Material / Tailwind | UI chrome / styling (gamma-analytics ThemeForest template) | https://material.angular.io · https://tailwindcss.com |
| ag-Grid Community | UI grids (MIT; search/paging/selection only) | https://www.ag-grid.com |
| Chart.js | Dashboard charts | https://www.chartjs.org |
| AntV G6 | Catalog metadata-graph diagram | https://g6.antv.antgroup.com |
| ngx-toastr | UI toasts | https://github.com/scttcper/ngx-toastr |
| Prometheus | Metrics exposition (`/metrics`, hand-rolled registry) | https://prometheus.io |
| GitHub Packages / Actions | agent-kernel publish + CI | https://docs.github.com/packages |
| Default models | Qwen2.5-7B / 14B-Instruct, Gemma-2-2B (local tiers) | https://ollama.com/library/qwen2.5 · https://ollama.com/library/gemma2 |

> Hosted default tier maps (from the model-routing plan): Anthropic `claude-haiku-4-5` /
> `claude-sonnet-4-6` / `claude-opus-4-8`; OpenAI `gpt-4o-mini` / `gpt-4o`; Gemini
> `gemini-flash` / `gemini-pro` (SMALL/MEDIUM/LARGE).

## C. Baseline / reference metrics (non-perishable)

> Reproduced as measured; reference points, not SLAs.

### C.1 Ingest throughput (2M rows → PARQUET, JDK 26 / DuckDB 1.5.2)
| Stage | Time | Throughput | Share |
|---|---|---|---|
| ingest (Java CSV parse + appender) | 15.5 s | 129K rows/s | ~78% |
| tag (union / `__src_id`) | 1.4 s | — | 7% |
| transform (`CREATE TABLE AS SELECT`) | 1.4 s | 1.42M rows/s | 7% |
| write (`COPY … PARTITION_BY` + reveal) | 1.8 s | 1.09M rows/s | 9% |
| lineage (`GROUP BY`) | 0.02 s | — | <1% |

Ingest cost ≈ 0.6–1.0 µs/cell (linear in rows × columns): 3 cols 0.87 · 12 cols 0.65 · 40 cols
0.99 µs/cell.

### C.2 Native vs Java CSV engine (2M rows → PARQUET)
| Cols | Java ingest | DuckDB ingest | Speedup |
|---|---|---|---|
| 12 | 15.7 s (127K r/s) | 3.8 s (523K r/s) | 4.1× |
| 40 | 80.2 s (25K r/s) | 16.0 s (125K r/s) | 5.0× |

### C.3 Plugin ingest (DuckDB Appender vs JDBC)
- JDBC `executeBatch`: ~6.9K rows/s → DuckDB **Appender**: ~520–530K rows/s (**~75×**).
- Mode comparison (500K rows, 30 day-partitions): union ≈ 233K rows/s / 30 files / 23 MB peak;
  generation (4 gens) ≈ 202K rows/s / 120 files / 15 MB peak.

### C.4 Deployment reference (single-node, HDD, 4 threads)
| Source | Files | Rows/file | Time/file | Total (30 days) |
|---|---|---|---|---|
| (wide source) | 30 × `.csv.gz` | ~2.3 M | ~19 min | ~2.5 hr |
| (other) | varies | ~420 K | ~3.4 min | — |

Projected wide-schema ingest (~0.8 µs/cell, 2M rows): ~12 cols ~16 s · 76 cols ~2 min · 537-col CDR
~14 min (Java) → ~3 min (native).

### C.5 Test coverage (JaCoCo, v3.9.0, 2026-06-01)
| Scope | Line | Branch |
|---|---|---|
| Core ETL data-path (`etl`+`inspector`+`ingester`) | 87.4% | 76.0% |
| Core engine (excl. `util` CLI) | 86.5% | 72.2% |
| Assist agent | 85.8% | 62.9% |
| Pre-ETL `util` CLI tools (accepted gap) | ~5.7% | ~7.9% |

Intentional low-coverage classes: `OllamaModelProvider` / `DocRetriever` (need a live model/index;
CI is CPU-only) and the operator-run `util` CLI tools.

### C.6 Test counts by milestone (snapshots)
v2 final 185 → refactor Phases 1–3 (2026-06-11) 417 → test-coverage v3.9.0 466 (346 core + 120
agent) → v3-plan M8 / SESSION_STATUS 436 (+29 Vitest) → memory latest ~461. (Evolving, not
contradictory.)

## D. Toolchain & paths (reference workstation)

- JDK: `C:\.jdks\openjdk-26.0.1`; Maven: `C:\maven\apache-maven-3.9.16\bin` (neither on `PATH`).
- Repo: `C:\sandbox\ucc-file-processor`, branch `4.x`. (Historical docs reference stale paths
  `C:\sandbox\URA\sandbox` and `c:/sandbox/ucc-inspecto` — see Conflict Report.)
- Dev SPA: `inspecto-ui`, `npm start` → `:4204` (proxy `/api/*` → `:8080`).
- Companion repos (separate projects): `agent-kernel` (`C:/sandbox/agent-kernel`),
  CVVE (`C:/sandbox/agentic-doc-validation`).

## E. Source documents index (the consolidated set)

`01-Executive-Summary` · `02-Product-Requirements` · `03-Architecture and design` ·
`04-Implementation-Status` · `05-Roadmap` · `06-Operations` · `07-User-guide` · `08-Decisions` ·
`Appendix/Supporting-References` · plus the administrative deliverables `Archive-Index`,
`Conflict-Report`, `Coverage-Report` (in this `docs/consolidated/` directory).
