# API Stability Policy

> Part of the [UCC File Processor](../file-processor/README.md) documentation.

The framework distinguishes its **stable public API** from internal implementation.
Types, methods, and constructors marked [`@com.gamma.api.PublicApi`](../file-processor/src/main/java/com/gamma/api/PublicApi.java)
are the surface external code may depend on; everything else is internal and may
change in any release.

## What the marker means

| | Marked `@PublicApi` | Unmarked (internal) |
|---|---|---|
| Removed / changed incompatibly | Only on a **major** version bump, noted in release notes | Any release, no notice |
| New members added | Allowed (minor bump) | Anytime |
| Safe to depend on from plugins / embedders | **Yes** | No |

Within a major version, `@PublicApi` elements follow semantic versioning. The
annotation has `CLASS` retention — visible to tooling and Javadoc, not required
at runtime.

## The public surface (2.0.0)

Two audiences depend on the framework from outside:

**Plugin authors** (implementing a custom ingester):

| Type | Since | Role |
|---|---|---|
| `com.gamma.etl.FileIngester` | 1.3.0 | The interface you implement |
| `com.gamma.etl.FileIngester.Segment` | 1.3.0 | One returned event-type table |
| `com.gamma.etl.IngestResult` | 1.0.0 | Row counts you report |
| `com.gamma.etl.PipelineConfig` (+ nested records `Identity`, `Dirs`, `Processing`, `CsvSettings`, `Output`, `Schemas`) | 1.0.0 / records 2.0.0 | Passed to `ingest(...)`; read for paths, settings, `ingesterConfig` |

**Embedders** (driving the ETL from Java instead of the CLI):

| Type | Since | Role |
|---|---|---|
| `com.gamma.inspector.SourceProcessor` | 1.0.0 | Run one source (`run(cfg)` / `main`) |
| `com.gamma.inspector.MultiSourceProcessor` | 1.6.0 | Run many sources concurrently (`runAll` / `main`) |
| `com.gamma.etl.PipelineConfig.load(String)` | 1.0.0 | Parse a pipeline `.toon` into a config |

**Service & control line** (long-running host, Stage-2 enrichment, REST control plane):

| Type | Since | Role |
|---|---|---|
| `com.gamma.service.SourceService` | 2.2.0 | Always-on host: registry, poll schedule, event bus, control surface (`fromArgs`, `runAllOnce`, `runPipeline`, `pause`/`resume`, `pipelines`, `statusStore`) |
| `com.gamma.service.EnrichmentService` | 2.3.0 | Orchestrates Stage-2 enrichment against batch-commit events + schedules |
| `com.gamma.control.ControlApi` | 2.4.0 | Embedded REST control plane over a running `SourceService` |
| `com.gamma.service.DbStatusStore` | 2.6.0 | Database-backed `StatusStore` (engine-neutral JDBC; Postgres in prod, DuckDB in tests) — selected via `-Dstatus.backend=db` |

## Explicitly internal (do not depend on)

`CsvIngester`, `DuckDbCsvIngester`, `DataTransformer`, `PartitionWriter`,
`LineageCollector`, `SchemaSelector`, `BatchProcessor`, `BatchPlanner`,
`MarkerManager`, `QuarantineManager`, `DuckLakeRegistrar`, `CommitLog`,
`BatchAuditWriter`, the `com.gamma.util.*` CLI tools, and all of
`ManifestStore`/`Batch`/`PartitionDef`/`PartitionOutput`/`LineageRow` are
implementation detail. They are stable enough for the framework's own use but
carry no cross-version guarantee. Plugin authors interact with them only
indirectly (e.g. you populate DuckDB tables that `DataTransformer` later reads —
that contract is documented on `FileIngester`, not on `DataTransformer`).

## What is *not* covered by code-level stability — but is still stable

- **Pipeline `.toon` config format** — treated as a stable user-facing contract;
  changes are additive and documented in [configuration.md](configuration.md).
- **On-disk output** (Hive-partitioned Parquet/CSV layout, audit CSVs, commit log
  format) — forward-compatible; a format change would be called out as breaking.

These carry forward across the 1.x → 2.0 boundary unchanged; 2.0 broke only the
**Java embedding API** (the `PipelineConfig` field → nested-record move).

## Why no `module-info.java`

A JPMS module descriptor exporting only the public packages was considered and
**declined**: the project ships a fat, shaded JAR that bundles several
automatic-module dependencies (DuckDB JDBC, univocity, JToon, Jackson, SLF4J,
commons-*, gson, opencsv). Layering JPMS over that combination is high-risk
(split packages, automatic-module naming, shade interactions) for little gain
over the annotation + this policy. The `@PublicApi` marker is the lighter,
sufficient mechanism. Revisit only if the project ever stops shading.
