# Spec: Data Acquisition & File Collection Framework — Implementation Roadmap

> **Date:** 2026-06-14
> **Status:** Planned (implementation roadmap; nothing in this spec is built yet)
> **Branch:** `4.x`
> **Source requirement:** [data_acquisition_framework.md](../../data_acquisition_framework.md)
> **Builds on (confirmed seams — verified 2026-06-14):**
> `com.gamma.inspector.SourceProcessor#collectCandidates/#run`
> ([SourceProcessor.java:52](../../../inspecto/src/main/java/com/gamma/inspector/SourceProcessor.java)),
> `com.gamma.inspector.MultiSourceProcessor#runAll`, `com.gamma.etl.MarkerManager` (path-sentinel dedup),
> `com.gamma.etl.QuarantineManager` (dead-letter tree), `com.gamma.etl.CommitLog` (fsync'd batch ledger),
> `com.gamma.etl.BatchPlanner`, `com.gamma.etl.PipelineConfig` (six nested config records + the additive
> `if (block != null)` parse pattern), `com.gamma.inspector.FileChunker` + `com.gamma.util.TarInboxPreparer`
> (large-file + tar.gz handling), `com.gamma.etl.StreamingFileIngester` (the **SPI precedent** to copy),
> `com.gamma.service.SourceService` + `Scheduler` (poll loop), `com.gamma.metrics.MetricRegistry`,
> `com.gamma.event.EventType` + `EventLog`, `com.gamma.alert.AlertService`, and the
> `com.gamma.service.StatusStore` / `DbStatusStore` DuckDB-JDBC pattern (re-used by the OI object stores).
>
> This document is a **self-contained implementation roadmap**. Each phase maps a requirement section onto
> concrete Inspecto packages/classes/config keys, marks what already exists, and is grounded in real seams —
> **confirm the cited classes before coding** (this codebase moves; line numbers drift).

---

## 0. The thesis — Inspecto already *is* a single-connector acquisition engine

The requirement asks for a "Data Acquisition Engine with five responsibilities: **Discover → Determine
readiness → Guarantee → Retrieve & validate → Finalize & audit**." Inspecto already implements that loop
end-to-end **for exactly one connector: the local filesystem.** The work is therefore *not* a greenfield
build — it is **(a) extracting the one hardwired connector behind an SPI, then (b) filling the capability
gaps that a local `inbox/` never forced us to solve** (stability, checksums, gap detection, remote retrieval,
retry/backoff, post-actions).

Today's loop, traced through real code:

| Responsibility | Where it lives today | Connector-coupled? |
| :------------- | :------------------- | :----------------- |
| **Discover** | `SourceProcessor.collectCandidates()` — `Files.walk(dirs.poll)` + `PathMatcher` from `processing.file_pattern`, excluding `errors/`+`quarantine/` ([SourceProcessor.java:139](../../../inspecto/src/main/java/com/gamma/inspector/SourceProcessor.java)) | **Yes** — `Files.walk` is local-FS only |
| **Determine readiness** | *(absent)* — a matched file is a candidate immediately | n/a — gap |
| **Guarantee** | `MarkerManager` (path-sentinel skip) + `CommitLog` (fsync'd batch ledger) ⇒ **at-least-once** ([MarkerManager.java:40](../../../inspecto/src/main/java/com/gamma/etl/MarkerManager.java)) | Partly — markers are path-keyed |
| **Retrieve & validate** | `BatchProcessor`/`CsvIngester` stream the file in place; `FileChunker` bounds huge files; column-count + header checks validate ([FileChunker.java](../../../inspecto/src/main/java/com/gamma/inspector/FileChunker.java)) | **Yes** — "open" = open a local `File` |
| **Finalize & audit** | `MarkerManager.createMarkerFile` + status/batches/lineage CSV ledgers + `commit_log` + `EventType.FILE_RECEIVED`/`FILE_QUARANTINED` | Partly — no post-action on the source file |

So the keystone is a **`SourceConnector` SPI** that abstracts *discover / readiness / open / stage / post /
capabilities*, with a **`LocalFileSystemConnector` that reproduces today's behaviour byte-for-byte** (so every
existing `*_pipeline.toon` is unaffected). Every later phase is a capability layered on that seam.

This is the **same modularity move the engine already made twice** — `StreamingFileIngester` (custom
*decoders* behind an SPI, framework owns buffering/DuckDB) and `TypedRecordIngester`. We copy that proven
pattern for *acquisition* instead of *parsing*.

---

## 1. What exists vs. what's missing (the honest matrix)

Verified against `inspecto/src/main/java/com/gamma/**` on 2026-06-14. This is the gap list every phase below
closes; **FULL** items are reused as-is, **PARTIAL** hardened, **ABSENT** built.

| # | Requirement section | Status | Today | Target phase |
| :- | :------------------ | :----- | :---- | :----------- |
| 1 | Source connectivity | **PARTIAL** | Local FS only (`dirs.poll`); no connector abstraction | **A** (SPI) / **E**,**Fut** (remote) |
| 2 | File discovery | **PARTIAL→FULL** | Recursive `Files.walk` + glob `file_pattern`; no regex, no exclude list, unbounded depth | **A** (exclude/depth), **C** (regex) |
| 3 | Incremental discovery / watermarks | **ABSENT** | Full re-scan every cycle; only the `.processed` marker carries state | **C** |
| 4 | File stability detection | **ABSENT** | A matched file is processed immediately — *the requirement's "biggest production problem"* | **B** |
| 5 | Duplicate prevention / marker repo | **PARTIAL** | `MarkerManager` — **path-keyed sentinel only** (no size/checksum/mtime) | **C** |
| 6 | File change detection | **ABSENT** | Re-upload at same path is silently **skipped** (data-loss risk) | **C** |
| 7 | Collection guarantees | **PARTIAL** | At-least-once via `CommitLog`; **no gap/missing-sequence detection** | **D** |
| 8 | File retrieval | **PARTIAL** | In-place streaming + `FileChunker`; no stage/resume/chunked-download | **E** |
| 9 | Compression | **PARTIAL** | `.gz` transparent + `.tar.gz` (`TarInboxPreparer`); no `.zip`/`.bz2` | **E** |
| 10 | Parallel fetching | **PARTIAL** | Virtual-thread + semaphore caps (`sources.max`, `processing.threads`); **no rate limiting** | **F** |
| 11 | Post-processing actions | **MINIMAL** | Source file left in place on success; quarantine only on failure | **F** |
| 12 | Failure handling | **MINIMAL** | Quarantine tree exists; **no retry/backoff/circuit-breaker/resume** | **F** |
| 13 | Integrity verification | **PARTIAL** | Column-count + header + row-field checks; **no size/checksum** | **E** |
| 14 | Observability | **COMPREHENSIVE** | `MetricRegistry`, `EventType`, status/batches/lineage/commit ledgers, `AlertService` | cross-cutting |
| 15 | Config model | **FULL** | `PipelineConfig` 6-record TOON; additive `if(block!=null)` parse | extended per phase |
| 16 | Orchestration | **FULL** | `SourceService` poll loop on `Scheduler`; `MultiSourceProcessor.runAll` | reused |

**Reuse ratio:** observability, config, orchestration, batching, dedup-skeleton, and quarantine are already
here. The genuinely new engineering is the **SPI seam (A)**, **readiness (B)**, **fingerprint repo (C)**,
**gap detection (D)**, and **remote I/O + resilience (E/F)**.

---

## 2. The keystone seam — `SourceConnector` SPI (Phase A)

New package `com.gamma.acquire` (mirrors the `com.gamma.event` / `com.gamma.ops` naming). The SPI is modeled
directly on `StreamingFileIngester`'s contract style (small, documented, framework owns orchestration).

```java
package com.gamma.acquire;

/** A pluggable file source. One instance per configured `source:` block; configured entirely from
 *  that block (no global state). Implementations are discovered by `scheme()` via ServiceLoader,
 *  exactly like the StreamingFileIngester plugin path. */
public interface SourceConnector extends AutoCloseable {

    /** Stable scheme id used in config (`connector:`) and ServiceLoader lookup: "local","sftp","s3",… */
    String scheme();

    /** What this connector can do — lets the engine pick STREAM vs STAGE and validate post-actions. */
    EnumSet<Capability> capabilities();   // RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, TAG, ETAG, VERSIONING

    /** Responsibility 1: list candidate files (name, relative path, size, mtime, etag/version).
     *  The engine applies include/exclude/watermark filtering on top of this listing. */
    List<RemoteFile> discover(DiscoveryContext ctx) throws AcquisitionException;

    /** Responsibility 2: is this file done arriving? Connectors that know natively (S3 finalized,
     *  SFTP rename-on-complete) answer authoritatively; the generic path falls back to the engine's
     *  size/mtime stabilization (Phase B) when this returns UNKNOWN. */
    Readiness readiness(RemoteFile f) throws AcquisitionException;

    /** Responsibility 4a: direct streaming — no local copy (S3/GCS/FTP/local). */
    InputStream open(RemoteFile f) throws AcquisitionException;

    /** Responsibility 4b: stage-then-process — copy to `stagingDir`, return the local Path.
     *  Supports resume when capabilities() includes RESUMABLE. */
    Path stage(RemoteFile f, Path stagingDir) throws AcquisitionException;

    /** Responsibility 5: finalize the source side — RETAIN/DELETE/MOVE/RENAME/TAG. */
    void post(RemoteFile f, PostAction action) throws AcquisitionException;
}
```

Supporting records (all in `com.gamma.acquire`): `RemoteFile(uri, name, relativePath, size, lastModified,
etag, version, checksum?)`, `DiscoveryContext(includes, excludes, recursiveDepth, watermark)`,
`Readiness{READY, NOT_READY, UNKNOWN}`, `Capability` enum, `PostAction(kind, archivePath, tagMap)`,
`AcquisitionException extends IOException`.

**`LocalFileSystemConnector` (Phase A, the parity implementation):**
- `discover` = today's `collectCandidates` tree-walk over `dirs.poll`, excluding `errors/`+`quarantine/`.
- `readiness` = `UNKNOWN` (delegates to Phase-B stabilization; pre-B it is always `READY` ⇒ unchanged behaviour).
- `open` = `FileInputStream`; `stage` = identity (returns the existing path — no copy, today's model).
- `post` = local `Files.move`/`delete`/rename; `capabilities` = `{RANDOM_ACCESS, RESUMABLE, DELETE, MOVE}`.

**Refactor, not rewrite:** `SourceProcessor.run` keeps its shape — `collectCandidates(cfg)` becomes
`connector.discover(ctx)` filtered by the engine; `BatchPlanner.plan(...)` and `BatchProcessor` are unchanged;
`MarkerManager` calls stay where they are. **Acceptance for Phase A = the full reactor stays green and every
existing `*_pipeline.toon` produces identical output** (a `connector:`-less config ⇒ implicit `LOCAL`).

---

## 3. Config model — the additive `source:` block (Requirement §13)

A new top-level `source:` block parsed into a `PipelineConfig.Source` record, following the **exact additive
pattern** already used for `processing.duckdb` / `processing.chunking` / `processing.duplicate_check`
(`Map block = raw.get("source"); if (block != null) { … }`). **Absent ⇒ implicit `LOCAL` connector reading
`dirs.poll`** — so the ~existing configs ([voucher_pipeline.toon](../../../inspecto/config/voucher/voucher_pipeline.toon))
are byte-for-byte unaffected. Staging defaults to `dirs.temp`; archive defaults under `dirs.backup`.

```yaml
# additive; omit the whole block for today's local-inbox behaviour
source:
  id: CDR_SFTP
  connector: SFTP                       # default LOCAL → reads dirs.poll exactly as today
  host: sftp.example.com                # connector-specific keys live here
  path: /cdr/outbox
  include: ["glob:**/*.dat"]            # falls back to processing.file_pattern
  exclude: ["*.tmp","*.partial",".~lock.*"]
  recursive_depth: 2                    # Phase A; default unbounded (today)
  incremental:                          # Phase C
    watermark: last_modified            # last_modified | etag | version
  stability:                            # Phase B
    window: 300s
    size_checks: 2
    ready_marker: "{name}.done"
  duplicate:                            # Phase C
    mode: CHECKSUM                      # PATH (default, =today) | METADATA | CHECKSUM
    algorithm: SHA256                   # MD5 | SHA256 | CRC32 | ETAG
    on_change: REPROCESS                # IGNORE | REPROCESS | ALERT | ARCHIVE_OLD_VERSION
  guarantee: EXACTLY_ONCE               # BEST_EFFORT | AT_LEAST_ONCE | EXACTLY_ONCE (Phase D)
  fetch:                                # Phase E/F
    mode: STAGE                         # STREAM | STAGE | CHUNKED
    staging_dir: ../data/voucher/temp   # defaults to dirs.temp
    parallel_fetch: 10
    rate_limit: 50MBps
  integrity:                            # Phase E
    size_check: true
    checksum: SHA256
  retry:                                # Phase F
    count: 5
    backoff: EXPONENTIAL
    initial_delay: 30s
    max_delay: 15m
  circuit_breaker:                      # Phase F
    failure_threshold: 5
    cooldown: 5m
  post_action:                          # Phase F
    on_success: MOVE                    # RETAIN | DELETE | MOVE | RENAME | TAG
    archive_path: archive/yyyy/MM/dd
    on_unsupported: WARN_AND_CONTINUE   # FAIL | WARN_AND_CONTINUE | IGNORE
  gap_detection:                        # Phase D
    enabled: true
    sequence: "CDR_{yyyyMMddHH}"
```

Each sub-block is parsed only when present, so phases ship independently: e.g. Phase B reads only
`source.stability`, leaving the rest unparsed/defaulted.

---

## 4. Phased roadmap

Ordered by **value × independence**: the seam first (A), then the highest-pain local-FS gaps (B, C, D) which
deliver value *before any remote work*, then remote I/O and resilience (E, F). Each phase is independently
shippable, additive, and reactor-green-gated.

### Phase A — Connector SPI + Local parity  *(keystone refactor; zero behaviour change)*
**Status: ✅ Implemented 2026-06-14** (`com.gamma.acquire` SPI + `LocalFileSystemConnector` + `RetrievalPlanner` + additive `source:` config; reactor green).
**Outcome:** acquisition is pluggable; nothing observable changes.
- New `com.gamma.acquire` package: `SourceConnector` SPI + records (§2); `LocalFileSystemConnector`.
- `ServiceLoader`-based registry keyed by `scheme()` (copy the `StreamingFileIngester` discovery path).
- `PipelineConfig.Source` record + additive parse (§3); `connector:`-less ⇒ `LOCAL`.
- `SourceProcessor.run` calls `connector.discover()` → engine-side include/exclude/depth filter → `BatchPlanner`.
- **Discovery hardening (§2):** honor `source.exclude` patterns and `source.recursive_depth` (today: neither).
- **Tests:** `LocalFileSystemConnector` reproduces `collectCandidates` exactly (golden test over a fixture inbox);
  `Source` config round-trips; existing pipeline integration tests unchanged.

### Phase B — Readiness: stability detection + temp-file exclusion  *(Requirement §3-readiness, §4)*
**Status: ✅ Implemented 2026-06-14** (`StabilityGate` + `source.stability` config + `ready_marker` on the local connector + `FILE_STABLE`/`inspecto_files_waiting_stability`; reactor green).
**Outcome:** never ingest a half-written file — the requirement's stated "biggest production problem."
- Engine-side `StabilityGate` (in `com.gamma.acquire`): for each candidate, when `connector.readiness()` is
  `UNKNOWN`, require **N consecutive `size_checks` unchanged across `window`** and skip files modified inside
  the `window`. Connectors that know natively (later: S3 finalized / SFTP completion) short-circuit via `READY`.
  **As-built note:** the release decision is **wall-clock driven** (a file is released only once
  `now - mtime >= window` *and* it has been seen at the same size on `size_checks` consecutive cycles), which
  makes `filter()` idempotent under repeated evaluation — the read-only `countPending` scan and the real poll
  cycle can both call it without one stealing the other's progress. Observations live in a process-wide
  per-`(sourceId, relativePath)` map on the `StabilityGate.shared()` instance (the same shape as
  `IngestProgress`'s per-pipeline state); `Clock`/`Probe` are injectable for deterministic tests. The gate
  stats a file **only** when gating is on *and* readiness is `UNKNOWN`, reusing listing size/mtime when a
  connector already provided them — keeping discovery I/O at the minimum.
- **Temp-file exclusion:** when `source.stability` is on, the discovery excludes gain
  `*.tmp,*.partial,*.filepart,.~lock.*` (`exclude_temp_files: true` by default; override via
  `exclude_temp_patterns`); `ready_marker` ("process only when `{name}.done` exists") is a native readiness
  signal on the local connector — marker files are themselves never offered as candidates.
- **Observability:** new `EventType.FILE_STABLE` (emitted per file the gate releases, on the real `run` path
  only — `countPending` stays side-effect-free); gauge `inspecto_files_waiting_stability{pipeline}`.
- **Tests:** `StabilityGateTest` (held-then-released growing file, `size_checks` gating, polling pressure can't
  release a hot file, connector-native short-circuit without probing, vanished-file drop, per-source isolation,
  listing-metadata probe); `LocalFileSystemConnectorTest` (`ready_marker` READY/NOT_READY + marker excluded
  from discovery); `SourceConfigTest` (stability parse + `ready_marker`/temp-exclusion via `collectCandidates`).

### Phase C — Fingerprint marker repository + dup/change policy  *(Requirement §2-regex, §3, §5, §6)*
**Status: C1 + C2 + C3 ✅ Implemented 2026-06-14** (reactor green) — PATH/METADATA/CHECKSUM dedup all live,
plus `regex:` include/exclude. The incremental **watermark** is the only deferred piece (C4 below; it pays off
mainly with remote LISTs in Phase E).
**Outcome:** dedup by content, detect re-uploads, incremental scans — closes the path-only data-loss gap.

**As-built (C1, committed `eef78e4`):** `com.gamma.acquire.AcquisitionLedger` SPI + `InMemoryAcquisitionLedger`
(lean default) + `DbAcquisitionLedger` (its **own** DuckDB file, single-writer, upsert by `(source_id,
relative_path)` — resolves §8 Q1); `Checksums` (MD5/SHA-256/CRC32 via the JDK, streamed — zero new deps);
`DuplicatePolicy` (pure: `Mode` PATH|METADATA|CHECKSUM × prior `LedgerEntry` → `Decision` NEW|DUPLICATE|CHANGED,
+ `OnChange` IGNORE|REPROCESS|ALERT|ARCHIVE_OLD_VERSION); additive `source.duplicate` config (absent ⇒ PATH =
today's markers); `EventType.FILE_CHANGED`.

**As-built (C2, METADATA wired):** `AcquisitionLedgers.shared()` (process-wide ledger, `-Dacquire.ledger.backend`
memory|db, like the OI stores). `SourceProcessor.collect` applies a content-mode **ledger filter** for
`source.duplicate.mode != path`: per candidate, `stat` size+mtime (no file read → `countPending` stays cheap),
`ledger.find`, `DuplicatePolicy.decide` → drop DUPLICATE, reprocess CHANGED (unless `on_change=ignore`), emit
`FILE_CHANGED` on `alert`, bump `inspecto_duplicates_skipped_total` (run path only). `BatchProcessor.commit`
records the fingerprint **post-commit** (captured pre-backup, written last beside the markers — same
stranding-safety ordering). PATH mode unchanged.

**As-built (C3, CHECKSUM + regex):** CHECKSUM hashes each candidate via
`Checksums.of(algorithm)` **on the run path only** (`countPending` degrades to a metadata approximation so a
dashboard poll never hashes) and compares the digest against the ledger — catching content changes that
size+mtime hide. The hash is handed to `BatchProcessor` via `AcquisitionLedgers.stashChecksum`/`takeChecksum`
so the post-commit record reuses it (no second read). *Note:* the default CSV path ingests via DuckDB's own
reader, so the dedup hash can't be teed off the ingest read — CHECKSUM is inherently one extra streamed read
per candidate (opt-in, heavier; METADATA stays the cheap default). `regex:` include/exclude already worked
through the `LocalFileSystemConnector` matcher (Phase A passes `regex:`/`glob:` prefixes through) — now tested.

**Remaining (C4, not yet built):** the incremental **watermark** — persist max `last_modified` per source (§8
Q3 = per-source) and skip `<= watermark` at discovery. Marginal for a local `Files.walk` (it still walks the
tree) but a real win for remote connectors, where it lets a LIST request only recent objects — so it lands
naturally alongside Phase E.
- **`AcquisitionLedger`** (DuckDB-backed, **its own DB file + single-writer lock**, reusing the
  `DbStatusStore`/OI-store pattern): rows `{source_id, relative_path, name, size, checksum, last_modified,
  processed_at, status}`. `InMemoryAcquisitionLedger` + `DbAcquisitionLedger` (SPI twin of the note/link stores).
- **Duplicate modes:** `PATH` (default = today's `MarkerManager`, retained), `METADATA` (name+size+mtime),
  `CHECKSUM` (MD5/SHA-256/CRC32 computed via `java.security.MessageDigest` — **zero new deps**).
- **Change detection + policy:** same path, different checksum ⇒ `on_change` ∈
  `IGNORE | REPROCESS | ALERT | ARCHIVE_OLD_VERSION`.
- **Incremental discovery (§3):** persist `high_watermark` (max `last_modified`/etag per source) so a scan can
  skip everything `<= watermark` instead of re-walking + re-stat'ing the whole tree.
- **Discovery regex (§2):** `include`/`exclude` accept `regex:` in addition to `glob:` (Java `Pattern`).
- **Migration:** `PATH` mode keeps writing sentinel markers (backward compatible); `METADATA`/`CHECKSUM`
  require the ledger. A one-time importer can seed the ledger from an existing markers tree.
- **Observability:** `inspecto_duplicates_skipped_total`, `EventType.FILE_CHANGED`.

### Phase D — Collection guarantees + gap detection  *(Requirement §6)*
**Status: D1 ✅ Implemented 2026-06-14** (engine core, zero-dep; reactor green) — the `source.guarantee` knob +
sequence-gap detection → `SEQUENCE_GAP` event + metric. **D2 (deferred):** promoting a `SEQUENCE_GAP` to a
*managed* ALERT object (the "tracked, assignable in Cases/Issues" promise) — see the honest scope note below.
**Outcome:** "no file silently missed." A gap is a recorded, queryable operational fact.

**As-built (D1):**
- **Guarantee levels** — `PipelineConfig.Source.guarantee` (`source.guarantee:`) ∈ `BEST_EFFORT` (default, =today)
  | `AT_LEAST_ONCE` | `EXACTLY_ONCE`. **Declarative knob:** the teeth already exist — the fsync'd `CommitLog`
  gives idempotent replay after a crash, and the Phase-C fingerprint ledger (`duplicate.mode != path`) skips an
  already-processed file. The parser **logs a warning** when a stronger-than-best-effort guarantee is declared
  over path-only (marker) dedup (it then behaves as best-effort + commit-log replay), rather than silently
  over-promising. No fabricated crash-recovery beyond what `CommitLog`/the ledger already provide.
- **Gap / missing-sequence detection** — `com.gamma.acquire.GapDetector` (pure): `source.gap_detection.sequence`
  is a literal prefix/suffix around one `{…}` token holding a Java date pattern (`CDR_{yyyyMMddHH}`). It matches
  the discovered file names, derives the step from the finest pattern field present (s/m/H/d/M/y), enumerates the
  expected contiguous series between the lowest and highest observed keys (capped at `MAX_SERIES`), and reports
  the holes as full expected file names. Wired into `SourceProcessor.collect` **on the run path only** over the
  full discovery listing (not the dedup-filtered candidates), so a hole is reported even when nothing new is
  ingestable. `com.gamma.acquire.GapTracker.shared()` (the `StabilityGate.shared()` idiom) suppresses re-firing a
  *persistent* gap every poll cycle and forgets a gap once its file lands (so a reopened hole fires again).
- **Observability** — `EventType.SEQUENCE_GAP` (attrs `expected`/`sequence`/`unit`) on `EventLog` ⇒ visible in the
  Event Viewer + `inspecto_events_total{type=SEQUENCE_GAP}`; plus a dedicated `inspecto_sequence_gaps_total{pipeline}`.
- **Tests:** `GapDetectorTest` (hourly/daily/monthly holes, day rollover, non-matching names ignored,
  shape-matches-but-invalid-date skipped, malformed template throws), `GapTrackerTest` (fire-once / reopen /
  per-source isolation), `SourceConfigTest` (guarantee + gap_detection parse; run-path `SEQUENCE_GAP` emitted once
  and not re-fired on the next cycle).

**Deferred (D2) — promote `SEQUENCE_GAP` → managed ALERT object.** The roadmap originally assumed a
`*_alert.toon` rule on `SEQUENCE_GAP` would auto-promote through `AlertService` to a managed
`OPERATIONAL_OBJECT(ALERT)` "with zero new UI work." **Confirmed not true as-is:** `EventLog` is fire-and-forget
(no subscriber model) and `AlertService` evaluates rules over the **batches ledger** (`BatchEvent` + metric math),
never arbitrary `EventLog` types — so nothing bridges a gap event to an ALERT object today. D2 is that thin
bridge, and it belongs in the **service tier** (where `ObjectService` is wired), not the lean engine core: either
an `EventLog`→`ObjectService` subscriber for `SEQUENCE_GAP`, or a small `AlertService.promoteCondition(...)` entry
point. Until then a gap is a first-class operational **event + metric** (Event Viewer / Prometheus), just not yet
an assignable Cases/Issues object. The `EXACTLY_ONCE`-survives-a-crash test is a `CommitLog` concern already
covered there; not re-litigated here.

### Phase E — Remote connectors (SFTP/FTP) + retrieval + integrity + compression  *(Requirement §1,§7,§8-partial,§9,§11,§13)*
**Outcome:** the first non-local connector, proving the SPI on the wire.
- **`SftpConnector` / `FtpConnector`** implementing `SourceConnector`. *(Dependency note: SFTP needs an SSH
  client lib — e.g. Apache MINA SSHD or sshj. This is the first phase that may require a **new dependency**;
  gate it behind the optional connector module so the lean core stays dep-free — see Phase-packaging note below.)*
- **Retrieval modes (§7):** `STREAM` (direct `open()` into the ingester), `STAGE` (`stage()` → `dirs.temp` →
  process), `CHUNKED` (resumable segmented download where `RESUMABLE` is supported).
- **Integrity (§11):** size check (source size == bytes received) + checksum (MessageDigest during
  stage/stream, compared to connector-reported `etag`/sidecar). Content probes already exist (CSV header via
  `CsvIngester`; ASN.1/Parquet are decoder-specific via `StreamingFileIngester`).
- **Compression (§9):** add `.zip` + `.bz2` to the decompression path. `TarInboxPreparer` already uses **Apache
  Commons Compress** — confirm and reuse it so `.bz2`/`.zip` are **zero-new-dep**.
- **Observability:** `inspecto_bytes_transferred_total`, `inspecto_fetch_seconds` histogram,
  `inspecto_active_connections` gauge; `EventType.FILE_FETCHED`, `FILE_VALIDATED`.

### Phase F — Resilience + post-actions + parallel/rate-limit  *(Requirement §8,§10,§11-actions,§12)*
**Outcome:** production-grade fault tolerance and source-side finalization.
- **Retry/backoff:** `com.gamma.acquire.retry.RetryPolicy` (exponential w/ jitter, `count`/`initial`/`max`) —
  zero-dep; wraps `discover`/`stage`/`post`.
- **Circuit breaker:** per `source.id`, open after `failure_threshold`, cool down `cooldown`; a tripped source
  is skipped (logged + `EventType` emitted) instead of hammering a dead endpoint.
- **Dead-letter:** extend `QuarantineManager` with acquisition-stage reasons (`FAILED_FETCH`, `FAILED_DELETE`,
  `FAILED_MOVE`, `CORRUPT_DOWNLOAD`) — distinct from today's parse-stage reasons (`field_mismatch`,`unreadable`).
- **Post-processing actions (§11):** `post_action.on_success` ∈ `RETAIN | DELETE | MOVE | RENAME | TAG` via
  `connector.post()`; `on_unsupported` ∈ `FAIL | WARN_AND_CONTINUE | IGNORE` validated against
  `connector.capabilities()`.
- **Parallel fetch + rate limiting (§10):** per-connector `parallel_fetch` permits (reusing the existing
  semaphore model) + a token-bucket `rate_limit`; multipart download where the connector supports it.

### Future (explicitly out of scope here — requirement marks these "(future)")
Object storage (S3/GCS/Azure/MinIO) connectors; NFS/SMB/CIFS; SSH tunneling/proxy; **event-notification**
discovery (S3 events/inotify) as an alternative to polling; the **DB-export-file** source (SQL-template export
+ SSH tunnel to Postgres); "grouping data source" (one logical source spanning multiple collection points +
tagging). All are *additional `SourceConnector` implementations* — the SPI from Phase A is what makes them
non-disruptive, satisfying the requirement's extensibility clause ("new protocols via connector plugins
without modifying the core engine").

---

## 5. Cross-cutting — Observability (Requirement §12) reuses what's already comprehensive

No new observability *infrastructure* — only new *signals* on the existing rails:

- **Metrics (`MetricRegistry`, Prometheus `/metrics`):** add `inspecto_files_discovered_total`,
  `…_downloaded_total`, `…_duplicates_skipped_total`, `…_downloads_failed_total`,
  `inspecto_bytes_transferred_total`, `inspecto_fetch_seconds` (histogram), `inspecto_active_connections`
  (gauge). (`files_processed` already exists via the batch path.)
- **Events (`EventType` + `EventLog`):** `FILE_RECEIVED` + `FILE_QUARANTINED` **already exist**; add
  `FILE_DISCOVERED`, `FILE_STABLE`, `FILE_FETCHED`, `FILE_VALIDATED`, `FILE_CHANGED`, `FILE_ARCHIVED`,
  `FILE_FETCH_FAILED`, `SEQUENCE_GAP` — giving the requirement's lifecycle trail
  (`DISCOVERED→STABLE→FETCHED→VALIDATED→PROCESSED→ARCHIVED→FAILED`) end-to-end.
- **Audit trail:** the `AcquisitionLedger` (Phase C) **is** the per-file audit record; the existing
  status/batches/lineage CSV + `commit_log` continue to cover the processing stage.
- **Alerting:** `AlertService` already fires on events and (Phase-2 OI) promotes to managed ALERT objects —
  reused verbatim for "repeated failures / missing expected files / prolonged inactivity / excessive
  duplicates / connectivity degradation."

---

## 6. Packaging & dependency discipline

- Phases **A–D** are **zero-new-dependency** (local FS, DuckDB-JDBC already bundled, `MessageDigest` from the
  JDK, Commons Compress already present for `.tar.gz`).
- Phase **E** introduces the first connectors that need network client libs (SSH for SFTP). To **keep the core
  lean** (standing guardrail), put remote connectors in an **optional connector module** (own artifact,
  `ServiceLoader`-discovered), so the `file-processor` fat-JAR stays dep-free unless a deployment opts in —
  exactly how `inspecto-agent-hosted` isolates provider deps from the engine.
- **Never widen the core's dependency surface for a connector that a given deployment won't use.**

---

## 7. Development guidelines (same as the OI roadmap §7)

1. **Confirm seams before coding** — re-read the cited classes; line numbers and method shapes drift.
2. **Additive only** — every `source:` sub-block defaults to today's behaviour; a `source:`-less config is
   byte-for-byte unchanged. Phase A's acceptance bar is "reactor green + identical output on existing configs."
3. **Reactor green before commit**; one grouped conventional commit per phase; **no commit/push without an
   explicit ask**; **never stage `inspecto/pom.xml`**.
4. **Copy proven patterns:** SPI = `StreamingFileIngester`; store = `DbStatusStore`/OI note+link stores
   (own DuckDB file, single-writer); events/alerts = `EventLog`/`AlertService`.
5. **Keep the core lean** — remote-connector deps live in an optional module (§6).

---

## 8. Open questions (resolve before Phase A code)

1. **Ledger substrate for Phase C** — confirm DuckDB-table (own file, reusing `DbStatusStore`) over an
   enriched file-based marker. Recommendation: DuckDB table; keep PATH-mode sentinels for the zero-config case.
2. **`stage()` vs `open()` default** — local connector should default to `open()` (no copy, today's model);
   remote defaults to `STAGE`. Confirm the per-connector default policy.
3. **Watermark scope** — per `source.id` or per (source, relative-dir)? Per-source is simpler; per-dir scales
   better for date-partitioned inboxes. Recommendation: per-source, with the relative path stored for audit.
4. **SFTP client library** for Phase E (Apache MINA SSHD vs sshj) — driven by license + transitive-dep weight;
   decide when Phase E is scheduled, not now.

---

## 9. Connection profiles — reusable remote-system config + connection testing  *(decided 2026-06-14)*

**Status: foundation ✅ Implemented 2026-06-14** (artifact + registry + `source.connection` reference + secret-ref
resolution + reachability tester + Control API + UI Test button; zero new deps). The protocol-level auth handshake
(real SFTP/JDBC login, full SSH-tunnel establishment) lands with the **Phase E** connectors.

**Why:** Phase E was going to inline connector details (host/port/path/credentials) into each pipeline's `source:`
block. Instead, factor them into a **named, reusable connection profile** that many pipelines reference by id — one
controlled place for credentials, and a natural home for a **"test connection / tunnel"** action surfaced in the UI.

- **Artifact — `*_connection.toon`** (a `connection { … }` block, parsed with `ConfigCodec` exactly like
  `*_rca.toon`): `id`, `connector` (scheme), `host`, `port`, `database`, `base_path`, `username`, `password`,
  `options{…}`, optional `tunnel{ host, port, username, password }`. Loaded at `SourceService.fromArgs` into a
  by-id registry (`registerConnection`/`connections`/`connection`), mirroring the RCA-template registry.
- **Secrets — references only** (decision): credential fields hold `${ENV:NAME}` / `${SYS:prop}` / `${NAME}`
  references resolved at runtime by a zero-dep `SecretResolver`; **nothing sensitive is stored in the file or
  surfaced by the API/UI** (`ConnectionProfile.toMap()` shows a `${…}` ref verbatim but masks any non-ref value as
  `***`, and never the resolved value). The resolver never logs values.
- **Reference — `source.connection: <id>`** on a pipeline's `source:` block (additive; parsed into
  `PipelineConfig.Source.connection`, null when absent). Resolving a profile into a *constructed remote connector*
  is Phase E (no remote connector exists yet); the id is parsed, stored, and surfaced now.
- **Test — `ConnectionTester`** (zero-dep): resolves the effective endpoint (the tunnel/bastion `host:port` when a
  `tunnel` is set, else the target `host:port`) and does a `java.net.Socket` connect with a timeout → reports
  `{reachable, latencyMs}`; also reports `secretsResolved` (whether the profile's `${…}` refs resolve in this
  environment, without revealing them). `local` profiles report "no remote connection". Honest about scope: a
  tunnel test checks reachability to the **jump host**; the through-tunnel login is Phase E.
- **API:** `GET /connections` (masked list), `GET /connections/{id}` (masked), `POST /connections/{id}/test`
  (run the tester) — CONTROL-scoped, mirroring the `/rca/templates` wiring.
- **UI:** a Connections pane listing profiles with a **Test** button → `POST /connections/{id}/test`, rendering
  reachable / latency / secrets-resolved.
