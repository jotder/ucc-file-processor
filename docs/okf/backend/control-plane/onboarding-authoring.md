---
type: Seam
title: Onboarding authoring seams (draft lifecycle, previews, register pair)
description: The control-plane routes the guided Stream/Reference onboarding authors against — a draft is an inactive pipeline; enrichments need explicit hot-registration.
resource: inspecto/src/main/java/com/gamma/control/ConfigRoutes.java
tags: [control-plane, onboarding, config, enrichment, pipeline, reference]
timestamp: 2026-07-16T00:00:00Z
---

# Onboarding authoring seams

The guided onboarding (frontend: [onboarding](../../frontend/features/onboarding.md)) authors real
Stage-1 configs through these seams — **a draft is just a pipeline with `active: false`** (parsed,
indexed, catalog-visible, never executed; D3 of the design). Shipped P0–P3, 2026-07-16.

## Draft lifecycle (`ConfigRoutes`)

- `POST /config/write {type, config, overwrite?, subdir?}` — spec + `ConfigSafetyValidator` gate
  (ERRORs → 422), filename from the config's own identity field, **scan-suffix convention
  enforced**: pipelines → `<name>_pipeline.toon` (`MultiCollectorProcessor.resolveConfigs`),
  enrichments → `<name>_enrich.toon` (`ServiceBootstrap.resolveBySuffix`) — a bare name silently
  drops out of the registry on the next restart (found live, P2/P3).
- `GET|DELETE /config/{type}/{name}` — suffix-first resolution with bare-name fallback; DELETE
  refuses an `active: true` pipeline (409).
- **Write alone does not index a NEW file** — the register pair below completes a create; later
  overwrites hot-reload pipelines by mtime (enrichments do NOT — see below).

## Stateless sample previews

- `POST /config/preview/parsing {config, sample_text}` → `ComponentPreview.parsing` — the draft is
  interpreted by the real config parser, read with the engine's own DuckDB idioms per frontend;
  `all_varchar=true` for delimited/NDJSON (raw ingest is 100% VARCHAR; also keeps `java.time` out of
  the JSON). **`format: array|auto` (2026-07-19 fix)**: `read_json` has no `all_varchar` option, so
  `jsonSelect` instead casts every column with `SELECT COLUMNS(*)::VARCHAR FROM read_json(...)` —
  before this fix, an auto-detected timestamp came back as a DuckDB `TIMESTAMP` (a non-serializable
  `java.time` value), inconsistent with every other format's raw-string preview.
- `POST /config/preview/schema {config:{raw:{fields}}, sampleRows}` → `ComponentPreview.schema`
  TRY_CAST split → `{columns, okCount, rejectedCount, rejectedRows}`.
- `POST /enrichment/preview {config:{…enrichment draft…}, sampleRows}` → `EnrichmentEngine.preview` — seeds
  the `input` view from the sample (all VARCHAR), registers the real reference views (`ref:`-by-name resolve
  against the loaded pipelines, `path:` reads the file), runs the draft's `transform`, returns
  `{columns, rows, truncated}`. Persists nothing (throwaway DuckDB, `output.database` untouched); the enrichment
  stage's "Validated" state. 400 for a missing config/sample; 422 when the draft doesn't parse or its transform
  fails on the sample (surfaces exactly the error a run would hit).
  **UI (2026-07-19):** the enrichment pane's **Preview** button (`enrichment-pane.component`) samples the
  stream's Stage-1 output via `GET /db/table?name=<normalizedName>&limit=200` (the decision-rule Simulate
  idiom) and posts it as `sampleRows` (`ConfigService.previewEnrichment`); results render in a shared
  `<inspecto-query-panel>`, a 422 surfaces as an inline alert, and a stream with no ingested data yet warns
  (the `/db/table` 404/empty path) instead of calling the endpoint. Read-only — available in every lens.

## The register pair

- **Pipelines:** `POST /runs {configPath}` → `CollectorService.registerPipeline` (in-memory; the
  file under a scanned config dir is what survives restart).
- **Enrichments (v5.1.0):** `POST /enrichment {configPath}` → `CollectorService.registerEnrichment`
  → `EnrichmentService.register` — **upsert by `name`** over a live job list. Needed because
  enrichments have NO mtime hot-reload and had no register route (a new `*_enrich.toon` used to
  require a restart; the `POST /jobs type=enrich` workaround does full recomputes and breaks
  by-name refs). Event triggers apply from the next committed batch; schedule timers resolve their
  config **by name at fire time**. `EnrichmentService` is **always constructed** (empty-list tolerant)
  so a fresh space can register its first job; `GET /enrichment` with no jobs is 200-`[]` (was 404).
  Gates mirror `POST /runs`: 503 → 400 → 403 → 404 → 422; replace is the documented upsert (no 409).

**2026-07-20 SHIPPED — the unregister counterpart, for both pipelines and enrichments:**
`CollectorService.unregisterPipeline` removes a config path from the active registry and rebuilds the
read surface synchronously; wired from `DELETE /config/pipeline/{name}` (the onboarding draft-discard
path), so a discarded draft drops out of the catalog/`pipelines()` at once instead of ghosting there
until the next poll cycle. `EnrichmentService.unregister` (via `CollectorService.unregisterEnrichment`,
wired from `DELETE /config/enrichment/{name}`) removes a hosted job and cancels its schedule timer
immediately — previously a deleted-on-disk enrichment job's completeness timer kept firing (as a no-op)
until restart. `EnrichmentService.register`'s re-arm also now cancels the prior timer before starting a
new one, so a **changed** `schedule_seconds` on an existing name applies immediately rather than only
at restart (`Scheduler.everySeconds` now returns a cancellable `ScheduledFuture`, mirroring `cron()`'s
`CronHandle`).

## Reference production (`produces: reference`, P0)

A pipeline declaring `produces: reference` registers a standalone `REFERENCE_DATASET` origin
(`ref:<pipeline>`) instead of a Stream; `EnrichmentConfig.Reference{ref}` binds it **by name**,
resolved per recompute against the live registry (`EnrichmentEngine.referenceReader` — a
Hive-partitioned glob over the producer's `dirs.database` in its output format). Origin nodes
carry `attrs.active` (P3) so `/catalog/references` rows expose Draft/Live. The bare `EnrichJob`
path passes no pipeline context — by-name refs there fail with a clear error (deliberate).

## Engine fixes the live walks surfaced (apply beyond onboarding)

1. `SqlBuilder.appendCoalesce` with empty `date_formats` emitted zero-arg `COALESCE()::DATE` —
   whole batch `QUARANTINED_UNREADABLE`; now falls back to `TRY_CAST` (native ISO parse).
2. The collector-level `duplicate:` block is a **no-op on the legacy local poll path** — real
   dedup there is `processing.duplicate_check` (marker files); without it the same file re-ingests
   every cycle (idempotent output, but a spurious `BatchEvent` per cycle re-fires enrichments).
3. Without `dirs.status_dir` no batch audit lands — `/runs/{name}/batches` stays empty forever.

(2) and (3) are why the guided create derives the full orders-convention dir set +
`duplicate_check` silently.
