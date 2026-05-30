# v2.x Roadmap

Tracks the 2.x line of work. Branch: `2.x` (current version `2.10.0`).

v2 began as the place for **breaking changes** the 1.x line deliberately deferred —
see [design-notes.md](design-notes.md) for the original deferral rationale — and grew into
the **two-stage data-platform** arc (Stage-1 multiplexer + Stage-2 enrichment under a shared
service/API/observability control plane). The strategic "what/why" is in
[v2-backlog.md](v2-backlog.md); the sequenced milestone record is in [v2-plan.md](v2-plan.md).

## Status: v2.x backlog fully delivered (v2.0.0 → v2.10.0)

The 2.0.0 foundation shipped, then milestones M0–M8 delivered the platform (185 tests green):

**2.0.0 foundation:**
- ✅ **D6** — `PipelineConfig` split into six nested records (the one breaking change).
- ✅ **D2** — durable fsync'd `CommitLog` batch ledger.
- ✅ **M3** — `@PublicApi` markers + [api-stability.md](api-stability.md) policy.

**Platform arc (one minor release per milestone, each tagged on `2.x`):**
- ✅ **v2.1.0** enrichment core · **v2.2.0** service mode · **v2.3.0** enrichment orchestration
- ✅ **v2.4.0** Control API · **v2.5.0** observability · **v2.6.0** status DB (DuckDB)
- ✅ **v2.7.0** enrichment run audit · **v2.8.0** cron engine + config-driven jobs + reports
- ✅ **v2.9.0** enrichment audit over the API · **v2.10.0** date-range + percentile report windows

Nothing is open in 2.x. The remaining bets — embedded AI operator agent, Web UI, object
storage, distributed execution — are **deferred to v3.0+ by design** (see
[v2-backlog.md](v2-backlog.md#next-major-v30--deferred-by-design)).

(The original 2.0.0 planning detail is kept below for the record.)

## Planned work

### D6 — `PipelineConfig` record split (foundational, breaking)

The 30-field flat `PipelineConfig` becomes nested records grouped by concern.
This is the defining breaking change of 2.0: every consumer moves from
`cfg.databaseDir` to `cfg.dirs().database()`, etc.

Proposed shape (final field grouping TBD — see open question):

```java
public record PipelineConfig(
    Identity      identity,   // name, pipelineName, runTimestamp
    Dirs          dirs,       // poll, database, backup, temp, errors, quarantine, markers, log, status paths
    Processing    processing, // threads, duckdbThreads, filePattern, batch caps, ingester + segments
    CsvSettings   csv,        // delimiter, engine, skip*, hasHeader, date/ts formats
    OutputConfig  output,     // format, compression, ducklake
    Schemas       schemas     // schemaSelector | singleSchema | segmentSchemas
) {}
```

- **Risk:** large mechanical change across ~15 source + ~20 test files; the
  field grouping is a one-way door (it's the public API of 2.0).
- **Approach:** land the nested records first with the flat accessors delegating
  (temporary bridge), migrate consumers package by package, then remove the
  bridge. Keep the suite green at each step.

### M3 — `@PublicApi` stability markers (after D6)

Once the config surface is in its final nested shape, mark the public API
(`FileIngester`, `PipelineConfig` + nested records, `IngestResult`, `Segment`,
`PartitionDef`, the audit row records) with an `@PublicApi` annotation, and add
`module-info.java` exporting only the public packages. Deferred until after D6 so
the surface marked is the final one, not the flat version.

### D2 — commit-log hardening (independent)

A single source-of-truth per-batch commit log so a mid-commit crash is fully
recoverable, replacing the current "infer durability from markers + manifest +
backup" approach. Independent of D6; can land in any 2.x minor. Needs a short
design spec first (append-only vs per-batch file; fsync policy; read-on-poll vs
operator-triggered recovery).

## Non-goals for 2.0

- No change to the M..N multiplexer model or the deliberate non-goals
  (joins/lookups/aggregation stay out — see
  [architecture.md](architecture.md#design-philosophy--scope)).
- Output format and on-disk layout unchanged — 2.0 is an API/internal release,
  not a data-format break. Existing Parquet/CSV output and DuckLake registration
  are forward-compatible.

## Compatibility note

2.0 breaks the **embedding API** (code that constructs/reads `PipelineConfig`,
i.e. plugin authors and anything calling the ETL classes directly). It does
**not** break **pipeline `.toon` configs** or **on-disk output** — operators'
configs and data carry forward unchanged.
