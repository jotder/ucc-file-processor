---
type: Concept
title: Query catalog & execution
description: Query as a first-class Component — the Query Library store, $-Parameters, the Result Set descriptor, and POST /queries/{id}/run on DuckDB.
resource: inspecto/src/main/java/com/gamma/query/
tags: [control-plane, query, parameter, result-set, duckdb, studio]
timestamp: 2026-07-07T00:00:00Z
---

# Query catalog & execution

A **Query** is a first-class, reusable Component (R3): `{ type (sql | structured), source Dataset,
text | model, Parameters }` — lifted out of the artifacts that embed it so one Query serves many
renderings (Widgets, Dashboards, exports). Vocabulary: [`GLOSSARY.md`](../../../GLOSSARY.md) §6-B.

* **Storage** — the `query` kind is writable in the component store
  (see [component registry](../components/component-registry.md)); authored in the Studio's
  **Query Library** pane.
* **Execution (W4)** — `POST /queries/{id}/run` executes on the embedded DuckDB with **server-side
  `$`-Parameter resolution** (`$today`, `$day(-7)`, user-declared `$name`, …) and returns rows plus the
  **Result Set** descriptor (columns with type + analytic role: dimension / measure / temporal) that
  the Presentation layer matches renderings against.
* **Current limits** — `structured` (non-SQL) queries are compiled client-side today; the server
  returns `422` for them explicitly. Pagination is offset-based in this slice.
* **Contract** — part of the versioned [`/api/v1`](api-v1.md) surface; envelope + error codes apply.
