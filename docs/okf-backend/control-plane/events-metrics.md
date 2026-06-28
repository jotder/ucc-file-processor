---
type: Concept
title: Events & Metrics
description: EventLog (synchronous bus + the ingestLock deadlock seam), MetricRegistry, and the StabilityGate.
resource: inspecto/src/main/java/com/gamma/event/EventLog.java
tags: [control-plane, events, metrics, observability, deadlock]
timestamp: 2026-06-28T00:00:00Z
---

# Events & Metrics

* **`EventLog`** (`inspecto/src/main/java/com/gamma/event/EventLog.java`) — the event bus. `global()` +
  per-space instances; `current()` routes by the calling thread's `space` MDC, falling back to global.
  **Emission is synchronous on the publishing thread** (`emit()` calls each subscriber inline). This is the
  deadlock seam: `SourceProcessor` holds `ingestLock` through a poll cycle, so a subscriber that triggered a
  new ingest **inline** would re-enter `ingestLock` and deadlock — hence event-triggered work is handed to an
  off-bus virtual-thread pool (see [jobs](jobs.md)). `emit()` uses no SLF4J (avoids re-entrant capture) and
  swallows subscriber errors. A startup store-swap (`InMemoryEventStore` → configured backend) drains the old
  store oldest-first so nothing is lost.
* **`MetricRegistry`** (`inspecto/src/main/java/com/gamma/metrics/MetricRegistry.java`) — counters/gauges/
  histograms keyed by name + sorted labels; `scrape()` runs registered collectors then renders Prometheus
  text. The per-space `space` label is supplied by callers as a label (no registry-level space awareness).
* **`StabilityGate`** (`inspecto/src/main/java/com/gamma/acquire/StabilityGate.java`) — the acquisition
  file-readiness gate (not a health gate); one shared instance per space (see [acquisition](../acquisition/framework.md)).

The matching UI surfaces are the events and dashboard screens (see the inspecto-ui bundle, `inspecto-ui/docs/okf/`).
Production-investigation detail: `docs/ADVANCED_GUIDE.md`.
