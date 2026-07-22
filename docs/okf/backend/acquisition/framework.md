---
type: Concept
title: Data Acquisition Framework
description: The poll cycle and phases A–F — discovery, stability, dedup/watermark ledgers, gap detection, retry/circuit-breaker.
resource: inspecto-acquire/src/main/java/com/gamma/acquire
tags: [acquisition, phases, dedup, watermark, stability, ledger]
timestamp: 2026-06-28T00:00:00Z
---

# Data Acquisition Framework

A full acquisition engine (not a directory poller): **Discover → Determine readiness → Guarantee collection
semantics → Retrieve/validate → Finalize**. Driven by [`CollectorProcessor.run()`](../engine/ingestion.md) (one
call = one poll cycle). Authoritative doc: [`data-acquisition-framework.md`](data-acquisition-framework.md).

## Phases

* **A — Discovery.** `SourceConnectors.forConfig(cfg)` resolves the [connector](connectors.md): scheme
  `local` → built-in `LocalFileSystemConnector`; otherwise a `ServiceLoader<SourceConnectorFactory>` lookup.
  `SourceConnector.discover(ctx)` lists candidates (never dedups — that's an engine concern).
* **B — Stability gate.** `StabilityGate` (`com/gamma/acquire/StabilityGate.java`) holds back half-written
  files: it first asks `connector.readiness()`; if `UNKNOWN`, applies size/mtime quiescence (unchanged for
  `stability.window` across `stability.sizeChecks` cycles). One shared instance per [space](../control-plane/multi-space.md).
* **C — Deduplication + watermark.** `AcquisitionLedger` (`com/gamma/acquire/AcquisitionLedger.java`) is the
  fingerprint SPI: `find`/`record` per `(sourceId, relativePath)`, `highWatermark(sourceId)` for incremental
  discovery, `dbWatermark` for row-level DB export. Implementations: `InMemoryAcquisitionLedger` (default,
  lost on restart) and `DbAcquisitionLedger` (durable DuckDB/Postgres, via `-Dacquire.ledger.backend=db`).
  `DuplicatePolicy` modes: PATH / METADATA / CHECKSUM / SKIP / REPROCESS / VERSION / FAIL.
* **D — Gap detection.** `GapDetector` (`com/gamma/acquire/GapDetector.java`) flags missing files in a
  sequence series and fires alerts via `AcquisitionTelemetry`.
* **E/F — Retry + circuit breaker.** `RetryPolicy` (`com/gamma/acquire/retry/RetryPolicy.java`, configurable
  attempts + EXPONENTIAL/FIXED backoff) wraps connector calls; `CircuitBreaker` (`com/gamma/acquire/CircuitBreaker.java`,
  per-source) opens after a failure threshold and skips the source until cooldown.

`AcquisitionLedgers` (`com/gamma/acquire/AcquisitionLedgers.java`) is the per-space `shared()` accessor +
lifecycle manager (one of the five MDC-routed singletons — see [multi-space](../control-plane/multi-space.md)).
