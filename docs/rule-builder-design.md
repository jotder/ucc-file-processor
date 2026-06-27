# Rule Builder — design (north star)

> Status: **design** (2026-06-27). UI-first, mock-backed, phased. Decisions below are locked with the user;
> open questions are flagged. Grounded in the real engine seams (DuckDB-as-engine, `RowShaper` `where:`,
> `EnrichmentConfig.transform`, `AlertRule`, `OperationalObject`).
>
> **SHIPPED (the save-less "Query Core"):** the reusable **queryable-table** — `inspecto-ui/src/app/inspecto/query/`
> (`QueryPanelComponent`: projection + nested AND/OR filter + generated SQL + one-way Advanced-SQL override +
> live in-browser preview). Fully offline; embedded in the `/design` gallery, the parser **parsed-output**
> viewer, and the **events / alerts / cases / enrichment** surfaces (offline via `ops-mock.interceptor`,
> `environment.mockOps`). NOT yet built: rule **save/templates/parameters**, the aggregation **builder**
> (measures/dimensions — agg functions reach via the Advanced SQL panel only), and real server-side SQL
> execution.

## 1. Vision

One **visual authoring surface** (the Record Viewer evolved into a query/rule builder with **live preview**)
that produces a **Rule**: a reusable, parameterized, structured artifact. Shape is fixed at design time;
**values are bound at runtime** by a rule engine or job. The same Rule core is reused across many contexts
(pipeline filter/route, alert-from-event, issue/case generation, aggregation/report, revenue assurance,
fraud, custom jobs) — *as-is or with a per-binding overload (override) layer*.

Key insight: **DuckDB is already the execution engine**, and the engine already accepts the strings a rule
compiles to. So this is **not a new rule engine** — it is a visual front-end that *compiles to SQL the engine
already runs*, plus a library to save / version / parameterize those rules. "Visualize the result at creation
time" = run the compiled SQL over sampled rows in DuckDB.

## 2. Locked decisions

1. **Representation:** a **structured rule AST** is the source of truth; it **compiles to DuckDB SQL**. Raw SQL
   is an escape hatch only (see #2). Rationale: validatable, safe typed runtime params (no string-concat
   injection), portable to a streaming evaluator later, round-trippable into the visual builder.
2. **Manual SQL edits = one-way Advanced Override.** Hand-editing the generated SQL makes the SQL
   authoritative for that rule; the visual builder goes **read-only** for it (clearly flagged, reversible by
   discarding the override). No SQL-parser/two-way-sync project. Preview still runs the override SQL.
   → Put nested groups + AND/OR in the builder model so the override is rarely needed.
3. **v1 scope = projection + row filters** (SELECT cols + WHERE with grouped AND/OR + live preview).
   Aggregation (measures/dimensions → GROUP BY/HAVING) is Phase 2.
4. **Reusable, binding-agnostic core.** Build the Query Core as an embeddable component; do NOT couple it to
   a single context. Bind it later **as-is or with an overload layer**. Source is an abstraction (store /
   view / **join of tables**), not a single physical table.

## 3. Anatomy of a Rule

| Layer | UI | Compiles to | v1? |
|---|---|---|---|
| Source binding | store / view / join picker (or `:source` param) | `FROM …` | single source |
| Projection | column chips (or `*`) | `SELECT …` | ✅ |
| Selection | per-column condition rows, nested groups, AND/OR | `WHERE …` | ✅ |
| Aggregation | measures + dimensions + having | `GROUP BY … HAVING …` | Phase 2 |
| Parameters | `:name` typed contract (required/default) | typed runtime bind | ✅ (extract) |
| **Action (head)** | per context | open alert / route rows / write report… | per phase |

First four + params = the **shared Query Core**. Action = the **per-context head**.

## 4. The Rule AST (saved shape)

Sketch (TS-ish; persisted as TOON `rule` component):

```
RuleTemplate {
  id, name, kind: 'predicate' | 'aggregate'      // v1 = predicate
  source: RuleSource
  projection: string[] | '*'
  where?: Group
  // Phase 2:
  dimensions?: string[]
  measures?: { col: string, agg: 'sum'|'count'|'avg'|'min'|'max', as?: string }[]
  having?: Group
  params: Param[]
  sqlOverride?: string                            // set ⇒ authoritative, builder read-only
}
RuleSource =
  | { kind: 'store' | 'view', ref: string }
  | { kind: 'join', tables: {ref, alias}[], on: JoinCond[] }   // schema allows it; v1 may resolve to one
Group { op: 'AND' | 'OR', items: (Condition | Group)[] }       // nesting ⇒ grouping + OR
Condition { field, operator, value: Literal | ParamRef, value2?: ... } // value2 for BETWEEN
Param { name, type, required, default? }
```

Operators (typed per column): `= != < <= > >=  IN  NOT IN  LIKE  ILIKE  BETWEEN  IS NULL  IS NOT NULL`
(regex `~` later). Each `Condition.value` is a literal **or** a `:param` reference.

## 5. Source + schema/sample contract

The builder is fed by an injected **provider** (so it's binding-agnostic and mock-friendly):

```
RuleDataProvider {
  columns(source): Observable<ColumnMeta[]>     // name + type, drives operator menus & validation
  preview(sql, limit): Observable<PreviewRows>  // runs compiled SQL over sample data (DuckDB or mock)
}
```

`ColumnMeta.type` gates which operators/value editors appear (numeric vs text vs date vs bool). The **join**
source is in the type from day one; v1 may only *resolve* a single store/view, with joins landing later.

## 6. SQL compilation (DuckDB target)

- **Projection:** `SELECT a, b, c` (or `*`).
- **Selection:** recursive `Group` → `( leaf <AND|OR> leaf <AND|OR> ( nested ) )`. Each leaf is
  `"<field>" <op> <value>`. Identifiers are validated against `columns()` (reject unknown fields).
- **Parameters:** a `ParamRef` emits a `:name` placeholder. Two runtime paths (see §8 nuance):
  - **Preview:** substitute the param's `default` (or prompt) — typed, escaped.
  - **Runtime:** the engine/job binds typed values; the AST guarantees position + type so substitution is
    safe (no free-form concatenation).
- **Override:** if `sqlOverride` set, it *is* the SQL; builder fields ignored + locked.

## 7. Reusable component contract

```
<rule-builder
   [source]   = "RuleSource"
   [provider] = "RuleDataProvider"     // columns + preview (mock now)
   [rule]     = "RuleTemplate | null"  // edit existing
   [kind]     = "'predicate' | 'aggregate'"
   (sqlChange)= "..."                   // live compiled SQL
   (save)     = "RuleTemplate" />
```

Embeddable into: the parser/transform test panel, a standalone **Rule Library** page, the filter/route node
config, alert/issue/case authoring, enrichment/report authoring. Live preview reuses the existing ag-Grid
record-viewer pattern (`parser-config.dialog`).

## 8. Reuse model — bind & overload

A saved `RuleTemplate` is referenced by a **binding** that may carry an **overload layer**:

```
RuleBinding {
  ruleRef: 'rule/<id>'
  paramValues?: { [name]: value }       // bind runtime params for this use
  extraWhere?: Group                     // overload: AND-ed onto the template's WHERE
  projectionOverride?: string[]          // narrow/replace columns for this use
}
```

"As-is" = ref only. "With overload" = ref + extras. Compilation = template AST ⊕ overload → SQL.

## 9. Application-context map

| Context | Trigger | Rule kind | Action (head) | Eval | Seam |
|---|---|---|---|---|---|
| Pipeline filter / route | per batch | predicate | keep/route rows | batch | `RowShaper` `where:` ✅ exists |
| Alert from event | on event/batch | predicate / aggregate threshold | open `ALERT` | stream/batch | extend `AlertRule` |
| Issue from alert | alert correlation | predicate over alerts | open `ISSUE` | batch | `ObjectService.open(ISSUE)` (unbuilt) |
| Case generation | issue/alert group | grouping predicate | open `CASE` | batch | `ObjectService.open(CASE)` (unbuilt) |
| Aggregation / report | schedule / on_pipeline | aggregate | write store / report | batch | `EnrichmentConfig.transform` ✅ exists |
| Revenue assurance | schedule | aggregate + ref-join + tolerance | open `ISSUE`/report | batch | enrichment + ref join |
| Fraud rule | per event / window | predicate (+ velocity) | open `ALERT`/`CASE` | stream | new evaluator |
| Custom job | cron / manual | any | job action | batch | job runner |

## 10. Backend seams & nuances (grounded)

- **Filter/route:** `transform.filter.where` / `transform.route.branches[].where` accept a **verbatim DuckDB
  boolean string** (`RowShaper`). A predicate rule plugs in with **zero backend change**.
  - **Param nuance:** that `where:` is emitted *verbatim* — no prepared-statement binding today. So runtime
    param values need either (a) **safe server-side substitution** of the typed AST into the string at bind
    time, or (b) a new prepared/bind path. The structured AST makes (a) safe; this is the one real backend
    item for parameterized filter rules.
- **Aggregation:** `EnrichmentConfig.transform` takes freeform aggregate SQL → a measures/dimensions
  generator writes it. No structured measures/dims model exists yet (net-new, Phase 2).
- **Operational objects:** `ObjectType {ALERT, ISSUE, CASE, TASK}` + `OperationalObject` (status/severity/
  assignee/correlationId/attributes) exist; only ALERT is wired. `AlertRule` is threshold-only (4 metrics ×
  4 comparators). Heads for ISSUE/CASE and row-level alert predicates are the genuine backend build.
- **Rule store:** none today (rules are flat TOON). Add a `rule` component type (mirrors grammar/schema/
  transform/sink in `ComponentsService`) or a dedicated versioned rule store.

## 11. Phased plan (each phase has a verifiable DoD)

- **Phase 0 — builder spike (mock, binding-agnostic):** projection chips + per-column conditions (AND only)
  → live compiled SQL + live ag-Grid preview over mock sample rows. No groups/OR/save yet.
  *DoD: preview rows match the SQL for a hand-checked case; lint/build/test green.*
- **Phase 1 — full predicate + save:** nested groups + OR + typed operators + param extraction + the
  Advanced-SQL override (read-only builder when overridden); save as a reusable `rule` template (mock store,
  then `/components/rule`). *DoD: rule round-trips (save→reload→same SQL); override locks the builder.*
- **Phase 2 — aggregation:** measures/dimensions/having → GROUP BY SQL, same preview; bind to enrichment.
- **Phase 3 — heads:** filter/route binding (free) → alert-from-event (extend `AlertRule` to take a row
  predicate + safe param substitution) → issue/case creation (`ObjectService.open`).
- **Phase 4 — specialized:** RA (reference-join + tolerance) and fraud (windowed predicates) as templates.

## 12. Open questions (secondary)

- Design-time schema/sample source per context (pipeline output schema vs connection-explore sampler vs a
  chosen store) — the `RuleDataProvider` abstracts it, but each binding must supply one.
- Parameter contract details (typed, required/default, preview prompt vs default).
- Governance: versioning + audit when a bound rule is edited; per-space scoping (assume yes).
- Naming & overlap: is a filter rule just a nicer authoring UI over `transform.filter`, or a distinct artifact
  that *generates* transforms/alerts/enrichments? (Leaning: distinct artifact, compiles to those.)
- Synergy with the existing `diagnose-and-alert` assist skill (builder as the manual counterpart that can also
  accept AI-drafted rules).
```
