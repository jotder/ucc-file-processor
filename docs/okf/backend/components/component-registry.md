---
type: Concept
title: Component Registry
description: Reusable grammar/schema/transform/sink components (now + dataset/widget/dashboard/query), the /components + /pipelines routes, ETag optimistic concurrency, preview and safe-delete.
resource: inspecto/src/main/java/com/gamma/pipeline/ComponentStore.java
tags: [components, registry, grammar, schema, transform, sink, etag, routes]
timestamp: 2026-07-07T00:00:00Z
---

# Component Registry

Components are the `use:`-referenced building blocks of authored [Pipelines](../pipeline-graph/design.md). They
live under `<write-root>/registry/<type>/` as TOON files, addressed by `<type>/<name>`.

* **Types**: `connection`, `grammar`, `schema`, `transform`, `sink`, `alert`. `ComponentStore.WRITABLE_TYPES`
  was **widened in W3** to also persist `dataset`, `widget`, `dashboard`, and `query` — the seam that lets the
  UI's Studio kinds store for real instead of mock-only. (The UI also persists a `rule` type, used by the
  data-table rule save — see the UI bundle.)
* **Storage**: `ComponentStore` (`inspecto/src/main/java/com/gamma/pipeline/ComponentStore.java`) — CRUD over the
  registry dir; `ComponentRegistry` holds the `Component` record (`type`, `name`, `ref`, `content`).
* **Optimistic concurrency** (W3): `ContentHash` (mirrors the UI's `content-hash.ts`, parity-pinned by test)
  hashes each component's content; `/components` responses carry an **`ETag`**, reads honour
  `If-None-Match` (304), and writes honour `If-Match` (precondition-failed on a stale hash).
* **Safe delete**: `PipelineReferences` scans every authored Pipeline's nodes for `use:` references;
  `ComponentRoutes` returns `409` if a component is still referenced.

## Routes

* `/components/{type}` — `GET` list, `GET /{id}`, `POST` create, `PUT /{id}` update, `DELETE /{id}` (safe).
* `/components/{type}/{id}/test` — preview a transform/grammar/schema/sink over sample rows on a **throwaway**
  DuckDB connection (`ComponentPreview`), never touching production.
* `/pipelines…` — `PipelineRoutes` (`inspecto/src/main/java/com/gamma/control/PipelineRoutes.java`): `GET /pipelines`
  (lifted pipelines), `GET /pipelines/node-types` (the editor palette catalog), `GET /pipelines/combined` (the
  store-joined pipeline+job topology), and `/pipelines/authored/*` CRUD + `dry-run` for authored Pipelines. The
  store superimposition (`PipelineStores.superimpose`) joins consumer `source_store` names to producer `store`
  names — no `on_pipeline` name-coupling — and a `DeletionFence` guards store deletion.

The UI counterparts are the [components](../../frontend/features/components.md) and
[Pipelines](../../frontend/features/pipelines.md) features in the frontend bundle.
