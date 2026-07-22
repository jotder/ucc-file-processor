# API Stability Policy
> *Moved from `docs/api-stability.md` (docs consolidation, 2026-07-16).*

> Part of the [Inspecto](../../../../inspecto/README.md) documentation.

The framework distinguishes its **stable public API** from internal implementation.
Types, methods, and constructors marked [`@com.gamma.api.PublicApi`](../../../../inspecto-api/src/main/java/com/gamma/api/PublicApi.java)
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
| `com.gamma.etl.StreamingFileIngester` | 3.10.0 | **The** plugin ingester SPI — emit records into a sink; the framework owns tables/transform/write/lineage and picks union vs generation mode by file size. (Sole SPI since 3.11.0.) |
| `com.gamma.etl.RecordSink` | 3.10.0 | Framework-provided callback a `StreamingFileIngester` writes records into (`define`/`emit`/`reject`/`junk`) |
| `com.gamma.etl.IngestResult` | 1.0.0 | Row counts (CSV path); plugin counts flow through `RecordSink` |
| `com.gamma.etl.PipelineConfig` (+ nested records `Identity`, `Dirs`, `Processing`, `CsvSettings`, `Output`, `Schemas`, `DuckDbSettings`, `Chunking`) | 1.0.0 / records 2.0.0 (`DuckDbSettings`/`Chunking` 3.10.0) | Passed to `ingest(...)`; read for paths, settings, `ingesterConfig`; `Processing` adds `largeFileBytes`/`flushRecords` streaming controls (3.11.0) |

> **Removed in 3.11.0 (breaking):** the whole-file `com.gamma.etl.FileIngester` and its nested `Segment` (both since 1.3.0). The plugin SPI is unified on `StreamingFileIngester`; the framework now runs the same ingester in *union mode* (many small files → one transform/write) or *generation mode* (huge single files → bounded scratch), chosen per batch by `processing.streaming.large_file_bytes`. Port `FileIngester` plugins to `StreamingFileIngester` (see [plugins.md](../engine/plugins.md)). This is a **deliberate exception** to the within-major-version stability promise above, made to consolidate the plugin SPI before it had wide external adoption.

> **Major bump 4.0.0 (the agent-module reshape):** the **core ETL `@PublicApi` surface above is unchanged** — Stage-1/Stage-2/control/embedder types and signatures carry over from 3.x untouched. The major bump was driven by two things: (1) a **runtime-floor bump for the agent modules**, and (2) the optional `file-processor-agent` module was reshaped onto shared orchestrator primitives (`SyncOrchestrator` / `Capability` / confidence-escalation / `AuditSink`), and `com.gamma.assist.AssistResult.confidence` became a numeric `double` (was a `String`). Only code driving the assist module's result type was affected.
>
> **Current Java floor (verified 2026-07-07):** the core compiles at `maven.compiler.release=24`; the **agent modules require a JDK 25+ runtime** (their model-transport jars are class-file v69) — the bundled JDK 26 runtime satisfies both. **agent-kernel itself was replaced 2026-07-07**: its reasoning layer is vendored into `inspecto-agent` (`com.gamma.agent.kernel.*`) and the model transport is **eoiagent** (`com.eoiagent:eoiagent-core|-model`) — the assist SPI (`com.gamma.assist.spi.AssistAgent`) is unchanged. The Standard edition adds three control-plane SPIs — `com.gamma.control.Authenticator` / `Subject` / `TokenRelay` (implemented by the `inspecto-security` module) — which follow the same stability policy.

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
| `com.gamma.service.EnrichmentService` | 2.3.0 | Orchestrates Stage-2 enrichment against batch-commit events + schedules; exposes the run-audit read surface (`configs`, `views`, `runs`, `lineage`) backing `GET /enrichment[...]` (v2.9.0) |
| `com.gamma.enrich.EnrichmentAuditReader` | 2.9.0 | Read side of the Stage-2 audit — reads back the `<job>_enrich_runs.csv` / `_enrich_lineage.csv` ledgers as JSON-ready rows |
| `com.gamma.control.ControlApi` | 2.4.0 | Embedded REST control plane over a running `SourceService`. History: v3.0 added scoped token auth; the **editions realignment (2026-06-16) removed token auth from the core** — the common core is auth-free (Personal), and Standard re-adds OIDC via the `Authenticator`/`Subject`/`TokenRelay` SPIs. v4.8: versioned `/api/v1` envelope alongside byte-for-byte legacy routes |
| `com.gamma.assist.spi.AssistAgent` | 3.0.0 | SPI for the optional embedded assist agent; discovered via `ServiceLoader` (or `SourceService.registerAgent`) and wired in-process before `start()`. Implemented in the optional `file-processor-agent` module (M0) |
| `com.gamma.service.DbStatusStore` | 2.6.0 | Database-backed `StatusStore` (engine-neutral JDBC; DuckDB by default — zero extra dep; Postgres for a future distributed deployment, bring-your-own driver) — selected via `-Dstatus.backend=db` |
| `com.gamma.report.ReportService` | 2.8.0 | Rolls `StatusStore` audit into a live status snapshot + a historical batch-audit report (backs `GET /status`, `/report`, `/pipelines/{name}/report`); rolls the Stage-2 run audit into `enrichmentReport` (backs `GET /enrichment/{job}/report`, v2.9.0); reports accept a `Window` date range + report duration percentiles p50/p95/p99 (v2.10.0) |
| `com.gamma.job.JobService` | 2.8.0 | Registry + scheduler for config-driven jobs (cron / event / manual) hosting ingest, enrichment, report and maintenance work uniformly (backs `GET /jobs`, `/jobs/{name}/runs`, `POST /jobs/{name}/trigger`) |
| `com.gamma.job.JobConfig` | 2.8.0 | A `*_job.toon` definition: name, `type`, `cron`, `on_pipeline`, `enabled`, type-specific params |

### Agent-consumed core surface (frozen 4.0.0, M3)

The optional `file-processor-agent` (and `-intelligence`) modules are an L5 consumer that, per
[architecture-layers.md](../architecture-layers.md), must touch core **only through SPI interfaces +
`@PublicApi` types**. The read-only surface they actually consume — captured once in
`UccAgentContext` and reached by the capabilities/skills — is now `@PublicApi`-frozen `since = "4.0.0"`
so this contract can't drift silently. It is deliberately a *freeze*, not a wrapping facade: a parallel
`agent.spi` DTO layer was rejected because the modularization is reactor-internal (one deployable — no
build against an API jar), so wrapping would duplicate data shapes for no payoff. The frozen surface:

| Package | Types |
|---|---|
| `com.gamma.catalog` (+`.spi`) | `ConfigSource`, `Description`, `IdScheme`, `MetadataGraph`, `MetadataGraphService`, `MetadataNode`, `NodeKind`, `OperationalOverlay`, `Provenance`, `DescriptionProvider` |
| `com.gamma.config` (`.io`/`.safety`/`.spec`) | `ConfigCodec`, `ConfigLoader`, `ConfigSafetyValidator`, `SafetyPolicy`, `ConfigSpec`, `ConfigSpecs`, `FieldSpec`, `Finding`, `RawConfig`, `Severity` |
| `com.gamma.sql` | `SqlOracle`, `SqlSandboxPolicy` |
| `com.gamma.signal` | `Ref`, `Severity`, `Signal` |
| `com.gamma.util` | `CronExpression` (relocated from `com.gamma.service`, WS-D §1.7) |
| `com.gamma.service` | (`CollectorService` already public) |
| `com.gamma.etl` / `com.gamma.enrich` / `com.gamma.job` / `com.gamma.event` / `com.gamma.report` | `BatchEvent`, `StatusStore` (StatusStore relocated from `com.gamma.service`, WS-D §1.7); `EnrichmentAuditReader`, `EnrichmentConfig`; `JobConfig`; `EventLog` (4.2.0); `ReportService.Window` |

## Explicitly internal (do not depend on)

`CsvIngester`, `DuckDbCsvIngester`, `DataTransformer`, `TransformCompiler`,
`PartitionWriter`, `OutputFormat`, `LineageCollector`, `SchemaSelector`,
`BatchProcessor`, `BatchPlanner`, `MarkerManager`, `QuarantineManager`,
`DuckLakeRegistrar`, `CommitLog`, `BatchAuditWriter`, the `com.gamma.util.*` CLI
tools, and all of
`ManifestStore`/`Batch`/`PartitionDef`/`PartitionOutput`/`LineageRow` are
implementation detail. (The `com.gamma.inspector` batch-ingest strategy seam —
`BatchIngestStrategy`, `CsvBatchStrategy`, `StreamingPluginBatchStrategy`, `DuckDbRecordSink`,
`IngestOutcome`, `MemberAudit`, all package-private — is likewise internal.) They are stable enough for the framework's own use but
carry no cross-version guarantee. Plugin authors interact with them only
indirectly (e.g. you emit records that `DataTransformer` later reads —
that contract is documented on `StreamingFileIngester`/`RecordSink`, not on `DataTransformer`).

## What is *not* covered by code-level stability — but is still stable

- **Pipeline `.toon` config format** — treated as a stable user-facing contract;
  changes are additive and documented in [configuration.md](../config/configuration.md).
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
