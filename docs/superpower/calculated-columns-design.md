# Calculated columns (DAT-5) — expression safety design

**Status:** backend designed + shipped 2026-07-08; **authoring UI shipped 2026-07-10** · **Owner:**
backend (+ UI) · **Companions:** `api-contract-design.md` §10 W4 (the query stack this extends),
`docs/GLOSSARY.md` (Dataset).

## 0. UI (2026-07-10)

`DatasetCalculatedComponent` (`modules/admin/studio/datasets/dataset-calculated.component.ts`) on the
dataset editor: add/edit/remove `{name, expr}` rows, a **Test** button that runs the expression over the
dataset's sample rows via the same offline AlaSQL path Measures use (no backend round trip), and an inline
error shown per-field. The inline check is `calculated-column-guard.ts` — a hand-maintained TS mirror of
this doc's three rules (closed token alphabet, keyword deny-set, function whitelist), kept for instant
author feedback. **It is not authoritative**: the server (`ExpressionGuard`, below) is the only enforcement
that matters for safety and re-validates at query time regardless of what the client thinks is clean.

## 1. The problem

A calculated column is **caller-authored SQL text inside the trusted relation**. `DatasetRelation`
builds the only SQL allowed to touch files (`read_parquet` globs / view SQL), and everything
caller-authored normally passes `SqlGuard` — but `SqlGuard` validates *whole statements* (single
read-only SELECT), not *fragments*. Splicing an unchecked fragment into the trusted relation would
hand the caller the exact capability the whole query stack exists to withhold.

Threat cases the design must kill:
- **Scalar-subquery smuggling** — `(SELECT secret FROM read_parquet('...')) `: every token looks
  innocent to a naive lexer; the structure is the attack.
- **File/extension function calls** — `read_parquet(...)`, `read_csv(...)`, `glob(...)`, UDFs.
- **Statement escape** — `1; DROP …`, comment tricks (`--`, `/* */`), quote-breaking.

## 2. The model: token-validated fragments with a call whitelist

`ExpressionGuard.check(expr)` validates a fragment with three cooperating rules — each closes the
hole the others leave:

1. **Closed token alphabet.** Only: plain identifiers (`[A-Za-z_][A-Za-z0-9_]*`), numeric literals,
   single-quoted string literals (`''` escape only), operators `+ - * / % || = != <> > >= < <=`,
   parentheses, commas. No semicolons, no double quotes, no `--`/`/*`, no backslashes. Length cap 500.
2. **Keyword deny-set for bare identifiers.** `select from where group having union join with values
   insert update delete drop create alter attach copy pragma install load call set table exec execute`
   → rejected outright. This is what kills the scalar subquery: its `SELECT` keyword can never appear.
3. **Function-call whitelist.** An identifier followed by `(` must be one of:
   `abs round floor ceil coalesce nullif greatest least upper lower trim ltrim rtrim length substr
   substring concat replace cast try_cast case` *(plus the CASE-expression keywords `case when then
   else end and or not is null in like between` allowed as flow keywords)*. Anything else —
   `read_parquet(`, `glob(`, a UDF — is rejected by name. `cast(x AS type)` allows only whitelisted
   type names (`integer bigint smallint double float decimal varchar text boolean date timestamp`).

Bare identifiers that survive the deny-set are **column references** — not resolved at guard time;
an unknown column fails DuckDB's binder at query time (a clean 4xx, no injection surface).

**Deliberate v1 cuts:** no double-quoted identifiers (plain `SAFE_IDENT` columns only), no window
functions, no aggregate functions (a calculated column is row-level — aggregation is the Measure
layer's job), no subqueries ever.

## 3. Where it enforces

`dataset` component config gains `calculated: [{name, expr}]`. `DatasetRelation.relationSql` wraps:

```sql
SELECT *, (expr1) AS "name1", (expr2) AS "name2" FROM (<base relation>) AS __base
```

- `name` must be `SAFE_IDENT`; `expr` must pass `ExpressionGuard` — either failure throws
  `IllegalArgumentException` → **422 at every route** (fail-closed: a dataset with a bad calculated
  column is unusable, never silently degraded).
- Enforced at relation-build time, so every consumer inherits it: `/bi/query`, `/queries/{id}/run`,
  reports, measure alerts, the DAT-4 materialize task, and dashboard shares.
- Measures/dimensions can reference calculated columns naturally — they are real columns of the
  wrapped relation.

## 4. Why not a real SQL parser?

DuckDB has no offline-safe Java parser we could reuse; vendoring one for row-level arithmetic is
disproportionate. The three-rule fragment model gives a *provably closed* surface (every token class
enumerable, every call named) at ~150 lines, testable exhaustively. If v2 needs window functions or
richer types, the whitelist grows one name at a time — never "parse harder".
