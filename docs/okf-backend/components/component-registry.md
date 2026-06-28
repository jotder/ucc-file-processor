---
type: Concept
title: Component Registry
description: Reusable grammar/schema/transform/sink/alert components, the /components + /flows routes, preview and safe-delete.
resource: inspecto/src/main/java/com/gamma/flow/ComponentStore.java
tags: [components, registry, grammar, schema, transform, sink, routes]
timestamp: 2026-06-28T00:00:00Z
---

# Component Registry

Components are the `use:`-referenced building blocks of authored [flows](../flow-graph/design.md). They live
under `<write-root>/registry/<type>/` as TOON files, addressed by `<type>/<name>`.

* **Built-in types**: `connection`, `grammar`, `schema`, `transform`, `sink`, `alert`. (The UI also persists a
  `rule` type, used by the data-table rule save — see the UI bundle.)
* **Storage**: `ComponentStore` (`inspecto/src/main/java/com/gamma/flow/ComponentStore.java`) — CRUD over the
  registry dir; `ComponentRegistry` holds the `Component` record (`type`, `name`, `ref`, `content`).
* **Safe delete**: `FlowReferences` scans every flow's nodes for `use:` references; `ComponentRoutes` returns
  `409` if a component is still referenced.

## Routes

* `/components/{type}` — `GET` list, `GET /{id}`, `POST` create, `PUT /{id}` update, `DELETE /{id}` (safe).
* `/components/{type}/{id}/test` — preview a transform/grammar/schema/sink over sample rows on a **throwaway**
  DuckDB connection (`ComponentPreview`), never touching production.
* `/flows…` — `FlowRoutes` (`inspecto/src/main/java/com/gamma/control/FlowRoutes.java`): `GET /flows`
  (lifted pipelines), `GET /flows/node-types` (the editor palette catalog), `GET /flows/combined` (the
  store-joined pipeline+job topology), and `/flows/authored/*` CRUD + `dry-run` for authored flows. The store
  superimposition (`FlowStores.superimpose`) joins consumer `source_store` names to producer `store` names —
  no `on_pipeline` name-coupling — and a `DeletionFence` guards store deletion.

The UI counterparts are the components and flows features in the inspecto-ui bundle
(`inspecto-ui/docs/okf/features/`).
