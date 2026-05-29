# Design Notes

Architectural decisions, deferred work, and the reasoning behind them. Not user-facing — read this before you make a structural change to the framework.

**Companion docs:** [`performance.md`](performance.md) (benchmark results, bottleneck analysis) · [`test-coverage.md`](test-coverage.md) (JaCoCo coverage breakdown and gaps).

---

## Stability tiers (informal SemVer policy)

Until we adopt a formal `@PublicApi` annotation + JPMS module-info, treat symbols by package as:

| Tier | Packages | Compatibility policy |
|---|---|---|
| **Public** (plugin authors depend on these) | `com.gamma.etl.FileIngester`, `com.gamma.etl.PipelineConfig`, `com.gamma.etl.IngestResult`, `com.gamma.etl.PartitionDef` | Breaking changes require a major version bump |
| **Framework-internal** | Everything else in `com.gamma.etl` and `com.gamma.inspector` | May change between minor versions; document in changelog |
| **CLI** | `com.gamma.util.MainApp` (entry point + command surface) | Stable command names; flag additions allowed |
| **Utility internals** | Other `com.gamma.util.*` | May change at any time |

If you depend on a framework-internal symbol from outside the project, pin to an exact patch version.

---

## Deferred design items

The following came out of the v1.3.2 review pass. They are real improvements but require either a breaking change or a focused design exercise; the team should approach each one deliberately rather than slipping it into a maintenance patch.

### D1 — Unified concurrency primitive

**Current state.** `SourceProcessor` uses `Executors.newFixedThreadPool(cfg.threads)` for batch fan-out. The pre-ETL utilities (`FileOrganizer`, `TarArranger`, etc.) use `VirtualThreadRunner` + `Phaser`. Both work; the inconsistency is friction for new contributors.

**Why deferred.** Switching `SourceProcessor` to virtual threads is mostly mechanical, but `cfg.threads` is exposed in every pipeline config and currently controls the batch-level parallelism. Virtual threads make `cfg.threads` semantically meaningless (every task gets its own carrier). Two clean paths forward:

1. **Drop `cfg.threads`** entirely; document that DuckDB's internal parallelism handles CPU usage.
2. **Repurpose** `cfg.threads` as a permit semaphore so operators can cap total concurrency on a shared box.

Either way is a config-format consideration that wants explicit operator buy-in before shipping.

**When to do this.** Next minor release. Aim for a single PR that swaps both `SourceProcessor` and any remaining `newFixedThreadPool` usages, plus a config-validator warning for legacy `cfg.threads > 1` settings.

### D2 — Commit-log abstraction

**Current state.** Batch durability is signalled by the *combination* of (a) outputs present on disk under partition paths, (b) manifest written in `dirs.status_dir/manifests/`, (c) member files moved to `dirs.backup`, (d) marker files created in `dirs.markers`. The v1.3.2 commit ordering fix in `BatchProcessor.commit` makes a mid-step crash idempotent on rerun (markers go last) — but there's still no single "this batch finished" line operators can grep.

**Why deferred.** A proper commit log needs design choices: append-only or per-batch file? Synced to disk or buffered? Read on every poll to skip "in-progress" batches, or only on operator-triggered recovery? Each choice has different operational implications.

**When to do this.** When the first real production incident makes "did batch X finish or not" a question worth answering definitively. Until then the current 4-signal redundancy is operationally sufficient.

### D4 — DuckDB type leak in `CsvIngester`

`CsvIngester` casts `(DuckDBConnection) conn` at one site to reach the appender API (much faster than `INSERT ... VALUES`). This is a deliberate engine-coupling for performance — without the appender, ingesting a 5 GB CSV is 10× slower. If the project ever wants to swap engines, hide the appender behind an `RawTableWriter` interface; for now, accept the coupling.

### D6 — `PipelineConfig` nested-record refactor

**Current state.** `PipelineConfig` exposes 30+ flat public final fields. Logical groupings (`Identity`, `Dirs`, `CsvSettings`, etc.) would be more readable, but every existing plugin and CLI command reads `cfg.delimiter`, `cfg.databaseDir`, etc., directly. Splitting into nested records is a breaking change.

**When to do this.** v2.0. Pair it with the `@PublicApi` annotation rollout so the surface area we commit to is explicit.

### M3 — Formal SemVer policy + `@PublicApi`

See "Stability tiers" above for the interim informal policy. A formal `@PublicApi` annotation plus a `module-info.java` that exports only the public packages would let consumers know what they can depend on. Defer until D6 lands (so the surface to mark is the cleaned-up nested-record version).

### M4 — Split the README into a `docs/` tree

**Current state.** `README.md` is ~1100 lines. New readers don't know where to start.

**When to do this.** Probably next time the README is touched substantively. Suggested split:

- `README.md` — overview, quickstart, link tree (target: 200 lines)
- `docs/architecture.md` — module structure, batch lifecycle, DuckDB usage
- `docs/configuration.md` — full toon reference
- `docs/plugins.md` — `FileIngester` interface + author workflow
- `docs/deployment.md` — packaging, run scripts, classpath
- `docs/troubleshooting.md` — symptoms → causes

---

## Cross-cutting conventions

- **SLF4J everywhere in the framework.** ETL code (`com.gamma.etl.*`, `com.gamma.inspector.*`) uses SLF4J at INFO/WARN/ERROR. Pre-ETL utility commands (`com.gamma.util.*` CLI tools) keep `System.out.println` because their output is for the human invoker, not log scrapers. `LogSetup` still tees stdout to a per-run file for diagnostics.

- **DuckDB connection per batch.** Each `BatchProcessor.process` opens its own temp DuckDB file via `DuckDbUtil.tempDbFile` and closes it in `finally`. No pooling, no sharing. Worth it for crash isolation.

- **Two CSV ingest engines.** `DuckDbCsvIngester` (native `read_csv`, vectorized, 4–5× faster) and `CsvIngester` (Java line-by-line, lenient with ragged rows) are interchangeable behind the same `ingest(...)` signature. `BatchProcessor.processCsv` picks via `DuckDbCsvIngester.usesDuckDb(cfg)`. The Java path is the fallback for messy configs (`skip_tail_columns` etc.) the native reader can't faithfully reproduce — see `docs/performance.md` for the semantic-difference analysis. Don't delete the Java path; it's load-bearing for the messy-file sources.

- **`raw_<KEY>_f<srcId>` table naming.** Hard-coded convention used by `BatchProcessor.processPlugin` to union member tables. Plugin authors must obey it; documented in `FileIngester` Javadoc.

- **Markers go last in `commit()`.** See `BatchProcessor.commit` for the ordering rationale comment. Any future commit-step change must preserve "markers signal durability" — if you create a marker for a file whose backup hasn't moved, that file is stranded.
