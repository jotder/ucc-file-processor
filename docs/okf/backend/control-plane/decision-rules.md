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
numerically, ids don't read as dates). Entry points: `int matched(Object when, List<Map> rows)` and
`List<Map> filter(Object when, List<Map> rows)` (the row-level form `matched` now delegates to —
added for Alert Rules' `when`, which needs the matching rows themselves, not just a count).

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

## Record-routing consequences run during live pipeline execution

`apply` executes real platform consequences on demand (`emit-signal`, `start-job`,
`trigger-pipeline`; stub signals for `create-alert`/`render-widget`/`generate-report`/`invoke-api`).
The **record-routing** consequences (`route`/`tag`/`quarantine`/`drop`) are applied by the engine
itself, per batch, via `com.gamma.etl.DecisionRuleApplier` — invoked from
`BatchIngestStrategy.writeAndTrace`, the shared tail of every ingest path (Java parse engine +
all three native `read_csv` streaming paths), between `DataTransformer` and `PartitionWriter`:

- **Rule loading** — `com.gamma.pipeline.DecisionRules` maps each space to its component-registry
  root (registered by `SpaceBootstrap`, forgotten on space deletion — the `ConnectionRegistry`
  per-space-singleton idiom, resolved by the space MDC the batch worker inherits). Rules are re-read
  per batch, so authoring/toggling takes effect on the next batch. Matching: enabled +
  `targetType: pipeline` + `target` equals the pipeline's authored or normalised name
  (case-insensitive), in priority order.
- **Predicate compilation** — `com.gamma.query.ConditionSql` renders the `when` tree as one DuckDB
  predicate (same walk/operators as `ConditionTree`; typing is operand-driven — numeric operand ⇒
  `TRY_CAST(col AS DOUBLE)`, ISO date ⇒ `TRY_CAST(col AS TIMESTAMP)`, else case-sensitive VARCHAR;
  substring ops case-insensitive). Parity is test-enforced (`ConditionSqlTest` asserts SQL counts ==
  `ConditionTree.matched` over the same data).
- **Consequences over the matched set** (tags first so copies carry them; then copies; then one
  removal if anything moves/drops): `tag` appends to a `__tags` VARCHAR column (added on first use —
  `SqlViews.reader` now sets `union_by_name=true` for Parquet so older un-tagged files stay readable
  alongside), `route` writes matched rows Hive-partitioned under `dirs.database/<destination>`
  (the `Batch.table` subdir convention) with their own outputs + lineage, `quarantine` copies them to
  `dirs.quarantine/records/<rule>/<baseName>_records.parquet` (record-level analogue of the
  whole-file `QuarantineManager`), `drop` removes them. Each applied rule emits one
  `decision-rule.applied` signal (rule/pipeline/batch/matched/actions) onto the space's ledger.
- **Routing is not a failure** — a broken rule (e.g. `when` references an unmapped column) is logged
  and skipped; it never fails the batch.

## Job and Stage-2 enrichment outputs are rule-checked too

There is no job-side analogue of `writeAndTrace` (job output materialization is per-type), so each
tabular producer hooks the shared applier itself via its general form —
`DecisionRuleApplier.apply(conn, table, Subject, quarantineRoot, baseName, RouteSink)` — where a
`Subject` says how rules match (`targetType` + candidate names) and how the
`decision-rule.applied` signal labels the run, and a `RouteSink` materializes routed rows in the
subject's own output discipline. `targetType: job` rules (the UI's job target picker) hook in two
places:

- **`sql.template` jobs** (`SqlTemplateJob`) — rules matching the job's name apply to the
  materialized template result between `CREATE TABLE` and the snapshot `COPY`, so the sink
  snapshot, the Run Artifact row count, and the recorded output shape are all post-rule. `route`
  writes matched rows as their own snapshot Parquet Dataset under `<dataDir>/<destination>` (same
  stage-and-atomic-swap discipline as the sink); `quarantine` copies them under
  `<dataDir>/.quarantine/records/<rule>/` (a dot-dir, invisible to store globs — the `.staging`
  convention).
- **Stage-2 enrichment** (`EnrichmentEngine.runResult`) — rules apply to `__enriched` between the
  transform and `PartitionWriter`, on **every** recompute trigger (enrich job, scheduled/event
  `EnrichmentService`, CLI). Matching is by the enrichment's **own name** — deliberately, so an
  idempotent scheduled recompute can't resurrect rows a job-triggered run removed — plus the
  wrapping job's name when run via an `enrich` job (`EnrichJob` passes it). `route` lands
  Hive-partitioned under `output.database/<destination>` (the pipeline subdir convention, same
  partition grain); `quarantine` under the sibling `<output.database>_quarantine` (the
  `EnrichmentAuditWriter` `_audit` suffix convention). Routed files are reported in the run's
  outputs, so audit/lineage see them.

Other built-in job types (`report`, `maintenance`, …) don't materialize rule-checkable tabular
output; Job-Pack types can adopt the same applier seam when they do.

## The other two-thirds of the triad now share the condition-tree contract too (2026-07-18)

Expectation and Alert Rule each adopted `ConditionTree`/`ConditionSql` in the shape that fits their
own domain — neither became a Decision Rule clone:

- **Expectation gains a `condition` kind** (`com.gamma.expectation.Expectation`,
  `ExpectationEvaluator`) alongside the original four (`non_null`/`range`/`regex`/`referential`).
  Unlike those, `condition`'s `when` tree **is** the violation predicate directly (a record matching
  it is a violation), compiled via `ConditionSql.predicate(when)` straight into the
  `SELECT count(*) … WHERE <predicate>` the other kinds also build — so it needs no `column` (the
  tree names its own field(s)) and can span multiple columns or use operators the four typed kinds
  can't (`contains`, multi-column `AND`/`OR`). `when` is required for this kind; the other four keep
  their own hand-built predicates unchanged.
- **Alert Rule gains an optional `when`** (`com.gamma.alert.AlertRule`) that row-scopes a
  ledger-metric rule: after the window selects ledger rows and before the metric aggregates them,
  `AlertService` filters via `ConditionTree.filter` (in-JVM — ledger rows never touch DuckDB) so an
  alert can watch e.g. only batches matching a condition, not just a bare metric/threshold. Not
  applicable to a BI-5 measure rule (no ledger rows to scope) — rejected at construction.
- **Alert Rule storage promoted off raw files** (`com.gamma.control.AlertRoutes`) — was the one
  member of the triad still living as raw `*_alert.toon` files (`ServiceBootstrap`'s suffix-scan at
  boot, hand-built TOON writes at CRUD time). Now an `alert-rule` `ComponentStore` component under
  `<write-root>/registry/alert-rules/`, matching Expectation/Decision Rule's CRUD contract exactly
  (503/422/409/404). `AlertService`'s in-memory rule list — the evaluation-time source of truth,
  since it's read on every terminal batch event — is unchanged; only *where it's loaded from* at
  boot and *where CRUD persists to* moved. Boot resolves the registry root the same way
  `DecisionRules.forPipeline` does (`root.config()/registry`, falling back to the legacy
  `-Dassist.write.root/registry` for the default space), so a restart re-arms exactly what
  `AlertRoutes` wrote. `AlertRule.load(Path)` (the raw `{alert:{…}}` wrapper) stays — the agent's
  `diagnose-and-alert` draft shape and a generic repo-config sanity sweep still parse it directly.

**UI**: `expectation-form.dialog.ts` and `alert-rule-form.dialog.ts` both wire the shared
`<inspecto-query-condition-group>` editor (the same component Decision Rule's form uses) — Expectation
shows it only for `kind: 'condition'` (a new `AttributeSpec.dependsOn.notEquals` variant hides
`column` for that kind; `dependsOnMatches` is the one place `equals`/`notEquals` is interpreted,
shared by `visibleSpecs` and `<inspecto-schema-form>`'s live show/hide), Alert Rule shows it always
(the form doesn't yet author BI-5 measure rules, so `when` is never inapplicable there). Alert
Rule's field list is the fixed ledger-row shape (`status`/`total_input_rows`/…), unlike Decision
Rule/Expectation which probe the target's real columns via `DbBrowserService`.

Tests: `com.gamma.query.ConditionTreeTest` (evaluator semantics, incl. `filter`),
`com.gamma.query.ConditionSqlTest` (SQL↔evaluator parity),
`com.gamma.inspector.DecisionRuleWiringTest` (all four consequences through `writeAndTrace`),
`com.gamma.enrich.EnrichmentEngineTest` (drop/route+tag/quarantine over `__enriched`, enrichment-
and job-name matching), `com.gamma.job.SqlTemplateJobTest` (rules before the snapshot; post-rule
artifact count; hidden-quarantine layout), `com.gamma.control.ControlApiDecisionRulesTest` (CRUD
gates + real simulate), `com.gamma.control.ControlApiExpectationTest` (`condition` kind),
`com.gamma.alert.AlertRuleTest`/`AlertServiceTest` (`when` validation + row-scoping), and
`com.gamma.control.ControlApiAlertRuleWriteTest` (ComponentStore-backed CRUD gates). UI:
`attribute-spec.spec.ts` (`notEquals`), `expectation-form.dialog.spec.ts`/
`alert-rule-form.dialog.spec.ts` (condition-tree wiring + save payload).
