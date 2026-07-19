---
type: Concept
title: Query catalog & execution
description: Query as a first-class Component — the Query Library store, $-Parameters, the Result Set descriptor, and POST /queries/{id}/run on DuckDB.
resource: inspecto/src/main/java/com/gamma/query/
tags: [control-plane, query, parameter, result-set, duckdb, studio]
timestamp: 2026-07-16T00:00:00Z
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
* **Three parameter namespaces, deliberately distinct** — `$name` (runtime query Parameters, resolved
  server-side here) · `:fieldValue` (Expectation/rule templates) · `${ENV:…}` (secret references). Each
  resolver leaves the other two untouched.
* **Structured editor (2026-07-19 SHIPPED)** — the Query Library authors both types now: the
  `<inspecto-query-panel>` Query Core builder (projection + nested AND/OR filter, already reused by
  Decision Rules/Alert Rules/Expectations) is wired in for `type: 'structured'`, `queries.component.ts`.
  The panel gained an `@Input initialModel` so re-opening a saved structured query seeds its builder
  state (backward-compatible — other reused call-sites don't bind it, unaffected). `$`-parameters stay
  SQL-only (detected by scanning `text`; a structured query has no text to scan) — deliberate cut.
* **Current limits** — `structured` (non-SQL) queries are still compiled/evaluated client-side; the
  server returns `422` for them explicitly (widgets don't execute *any* bound query server-side yet —
  a separate, pre-existing follow-on). Pagination is offset-based in this slice. `graph`/`spatial`/
  `search`/`api` query types are deliberately not built (geo/link views keep their own query shapes).
* **Contract** — part of the versioned [`/api/v1`](api-v1.md) surface; envelope + error codes apply.

## Calculated columns (DAT-5, shipped 2026-07-08; authoring UI 2026-07-10)

A calculated column is caller-authored SQL **fragment** text spliced inside the trusted relation —
`SqlGuard` validates whole statements, so fragments get their own guard:

* **`ExpressionGuard.check(expr)`** validates with three cooperating rules: (1) a **closed token
  alphabet** (plain identifiers, numeric/single-quoted literals, arithmetic/comparison operators,
  parens, commas — no semicolons, double quotes, comments, backslashes; 500-char cap); (2) a
  **keyword deny-set** for bare identifiers (`select from where … drop create attach copy pragma …`)
  — this kills scalar-subquery smuggling; (3) a **function-call whitelist** (`abs round coalesce
  upper substr cast try_cast …`) — `read_parquet(`, `glob(`, UDFs are rejected by name; `cast` types
  are themselves whitelisted. Deliberate v1 cuts: no quoted identifiers, no window/aggregate
  functions (aggregation is the Measure layer's job), no subqueries ever. Rationale: DuckDB has no
  offline-safe Java parser; the three-rule model is a provably closed surface — grow the whitelist,
  never "parse harder".
* **Enforcement point** — `dataset` config gains `calculated: [{name, expr}]`;
  `DatasetRelation.relationSql` wraps `SELECT *, (expr) AS "name" FROM (<base>) AS __base`. Bad
  name/expr throws → **422 at every route, fail-closed** — enforced at relation-build time so every
  consumer inherits it (`/bi/query`, `/queries/{id}/run`, reports, measure alerts, DAT-4
  materialize, shares). Surviving bare identifiers are column refs resolved by DuckDB's binder
  (clean 4xx if unknown).
* The UI's inline `calculated-column-guard.ts` mirrors the three rules for instant feedback but is
  **not authoritative** — the server re-validates at query time regardless.
