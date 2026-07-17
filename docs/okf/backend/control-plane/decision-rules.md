---
type: Concept
title: Decision Rules & the Condition-Tree Evaluator
description: /decision-rules routes — sample-driven simulate over a query-types condition tree, evaluated by the shared ConditionTree engine.
resource: inspecto/src/main/java/com/gamma/control/DecisionRoutes.java
tags: [control-plane, decision-rule, condition-tree, simulate, rules, signal-network]
timestamp: 2026-07-18T00:00:00Z
---

# Decision Rules & the Condition-Tree Evaluator

Decision Rules are the business-logic/routing third of the Rules triad (Expectation = data-quality,
Alert Rule = alerting). They are authored objects with full CRUD, persisted as `decision-rule`
components under `<write-root>/registry` via `ComponentStore`, exactly like Expectations — same
fail-closed gates (503 no write root · 422 bad body · 409 duplicate create · 404 unknown). Routes
live in `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java`. A rule is
`{name, targetType: pipeline|job, target, when, consequences[], priority, enabled}`.

## The condition tree (`when`) and its evaluator

`when` is the **`query-types` condition tree** the UI authors — a group
`{kind:'group', op:'AND'|'OR', items:[…]}` nesting leaf conditions
`{kind:'condition', field, operator, value?, value2?}`. Operators: `= != < <= > >= contains
startsWith endsWith in between isNull isNotNull`.

`com.gamma.query.ConditionTree` (`inspecto/src/main/java/com/gamma/query/ConditionTree.java`) is a
**pure, dependency-free port of the browser evaluator** (`inspecto-ui/.../query/query-eval.ts`) plus
its type inference (`query-columns.ts`). It exists so the backend counts row matches with *exactly*
the semantics the authoring UI previews offline — same case-insensitive substring ops, same
`in`/`between`, same typed comparison, and the same **"empty / incomplete group ⇒ no constraint ⇒
matches all"** rule. Column types are inferred from the sample rows (numeric strings compare
numerically, ids don't read as dates). Entry point: `int matched(Object when, List<Map> rows)`.
It's a reusable seam — Expectations / Alert Rules can adopt it when they promote to the same
condition-tree contract.

## `simulate` is sample-driven (the row-source decision)

`POST /decision-rules/{name}/simulate` evaluates the rule's `when` over a caller-supplied
`sampleRows` batch (the established `ApiContext.sampleRows(body)` convention, shared with
component/config/pipeline dry-run) and returns real `{matched, total, checkedAt}` stamped onto
`lastSimulation`.

**Why a sample, not live records:** a decision rule's `target` is a *pipeline/job*, not a queryable
dataset, so there is no ambient row source to evaluate against (the mock says as much — "there are no
real records to route"). The sample *is* the row source — the natural "test your rule against example
records" contract. A request with no `sampleRows` yields `0/0` (backward-compatible with the prior
0-matched stub). The stamp is written non-authoring (`store.write(…, false)`) so simulate cadence
never churns config versions (MET-5 parity).

The `normalize()` default for an absent `when` was corrected to the canonical
`{kind:'group', op:'AND', items:[]}` (was `{op:'and', conditions:[]}`) so a filter-less rule reads
identically to what the UI authors and `ConditionTree` evaluates.

## Deliberate deferral

`apply` executes real platform consequences (`emit-signal`, `start-job`, `trigger-pipeline`; stub
signals for `create-alert`/`render-widget`/`generate-report`/`invoke-api`). The **record-routing**
consequences (`route`/`tag`/`quarantine`/`drop`) are now *counted* by `simulate`, but applying them
takes effect only once wired into live pipeline execution — that wiring is the remaining open piece
(`docs/BACKLOG.md` §3 Signal/Decision networks).

Tests: `com.gamma.query.ConditionTreeTest` (evaluator semantics) and
`com.gamma.control.ControlApiDecisionRulesTest` (CRUD gates + real simulate).
