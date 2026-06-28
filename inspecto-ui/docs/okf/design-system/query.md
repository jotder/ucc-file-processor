---
type: Module
title: Query (AST, SQL, eval, builder)
description: Framework-free query model — projection + nested AND/OR filter — compiled to SQL and evaluated in-browser.
resource: inspecto-ui/src/app/inspecto/query/index.ts
tags: [design-system, query, sql, filter-builder, framework-free]
timestamp: 2026-06-28T00:00:00Z
---

# Query

`inspecto/query` is the framework-free "query core" behind the Pro [data-table](data-table.md):

* **Model** (`query-types.ts`): `QueryModel` = `projection` (`'*'` or column names) + a nested `ConditionGroup` (AND/OR over `Condition` leaves) + optional `sqlOverride`. `QuerySource` = `{ name, rows, columns? }`.
* **Columns** (`query-columns.ts`): `inferColumns(rows)` guesses `ColumnMeta` types; `OPERATORS`/`operatorsFor(type)` map column type → allowed operators.
* **Compile** (`query-sql.ts`): `compileSql(model, source)` → an illustrative DuckDB-style SQL string; `compileSqlWithParams(model, source)` → SQL with `:fieldValue` binds + the param list (basis for a [rule](rule.md) template).
* **Eval** (`query-eval.ts`): `evaluateRows(model, source)` runs the structured model over rows in-browser (used for the standalone panel's live preview).
* **Builder** (`query-condition-group.component.ts`): `<inspecto-query-condition-group>` — the recursive AND/OR filter editor, reused by the data-table's pro filter builder.
* **Panel** (`query-panel.component.ts`): `<inspecto-query-panel>` — the original all-in-one queryable table (projection + builder + SQL + live preview). Still used by the parser-config parsed-output; the data-table composes the pieces directly instead.

Note the offline split: the standalone panel **evaluates the structured model** (`evaluateRows`); the
data-table Pro editor **executes free SQL** via AlaSQL (see [data-table](data-table.md)).
