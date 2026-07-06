# Backend Backlog — Widget-Library M2, Matrices (Phase C), Job Templates (Phase D)

> **Status:** Consolidated backlog, not started. Written after the UI-only passes of the widget library
> (`widget-library-spec.md`, M1 shipped) and the Platform IA reorg (`ia-vocabulary-reorg.md`, Phases A/B/E
> shipped) both hit the same wall: **one closed backend enum**. This doc exists so the three backlogs are
> reviewed together instead of three separate one-line mentions. Facts below are grounded in the current
> Java source (file:line), not aspirational.

## The shared seam

> **✅ WIDENED 2026-07-06 (API-contract W3).** `WRITABLE_TYPES` now includes `dataset`/`widget`/`dashboard`
> and `ComponentRegistry.TYPE_BY_DIR` gained matching `datasets/`/`widgets/`/`dashboards/` dirs — the single
> change below is **done**. Verified no external special-casing of the closed set (`WRITABLE_TYPES` is read
> only inside `ComponentStore`; `isComponentType` is unused). Persistence, ETag/versioning and CRUD for the
> new kinds now work through the same `/components/{type}/{id}` routes. **Still open below:** the
> `QuerySpec→DuckDB` exec endpoint (W4), Matrices (net-new materialization), and job templates — the
> *storage* blocker is cleared; those remain their own work.

`com.gamma.pipeline.ComponentStore.java:36` (now widened):
```java
public static final Set<String> WRITABLE_TYPES =
        Set.of("grammar", "schema", "transform", "sink", "dataset", "widget", "dashboard");
```
Enforced in `validateType()` (`ComponentStore.java:93-97`), called from `write()`/`list()`/`get()`
(`:74,54,60`) and reached via `ComponentRoutes.java:47`. `dataset` / `widget` / `dashboard` are **not** in
the set — every Studio dataset/widget/dashboard persisted today is Angular-mock-only
(`studio-mock.interceptor.ts`, resets on hard reload). `connection` is deliberately excluded (its own
secret-aware CRUD) — not part of this widening.

**Widening this one `Set.of(...)` (+ `ComponentRegistry.TYPE_BY_DIR` + a registry dir per type) is the
single change every item below is blocked on.** It's mechanical (add 3 strings, add matching storage dirs,
verify existing validation/tests don't special-case the closed set elsewhere) — do it once, first.

## 1. Widget-Library M2 (from `widget-library-spec.md` §7)

Already scoped there; repeated here only for sequencing:
- Widen `WRITABLE_TYPES` (above) so `dataset`/`widget`/`dashboard` persist for real.
- `QuerySpec → DuckDB` exec endpoint, replacing the offline `runSpec` seam.
- `DatasetResultService` M2 form — same interface as M1.4, now backed by real network calls.
- Materialized-dataset refresh / scheduled delivery (adoption-plan P4 territory).
- *(Tracked, not owned by this work):* sharing/RBAC once `inspecto-security` exists.

## 2. Phase C — Matrices (persisted summary Derived Tables)

**Current state:** nothing exists. A repo-wide grep for `DerivedTable`/`materializ`/`rollup`/`cube`
returns zero matches in `inspecto/src/main/java`. Adjacent-but-not-equivalent building blocks:
`com.gamma.etl.TransformCompiler`/`DataTransformer` run a Transform **inline within a pipeline** (no
persistence step); `com.gamma.report.ReportJob`/`ReportService` persist *report* output, not a generic
derived table. **There is no reusable seam to widen here — a Matrix is net-new**, not a closed-enum problem
like the other two items.

**Shape of the work** (sequencing, not a full design):
1. A `DerivedTable`/materialization concept: run a `transform` component's config once (not per-pipeline-
   batch) and persist the output as a new DuckDB table, distinct from a pipeline's per-batch output.
2. Register it in the (now-widened) `ComponentStore` as a `dataset` (kind: derived/materialized) so it's
   selectable as a Studio Dataset — the UI-facing "Matrix" label wraps this, per `GLOSSARY.md` §6-B (a
   Matrix **is** a Derived Table; no new UI vocabulary work needed, that's Phase A, already shipped).
3. A refresh trigger — manual now, `JobService` template hook later (see §3) once that exists.
4. Surfaces in Catalog's Tables tab (kind: `DERIVED_TABLE`) — `catalog.service.ts`/`catalog-graph.ts`
   already model `DERIVED_TABLE` as a `NodeKind` (frontend is ready; only the backend row is missing).

## 3. Phase D — Job templates (trigger-condition-action)

**Current state:** `com.gamma.job.JobService.java:59` supports scheduling (`CronExpression`, `:90,151,349`),
manual trigger (`:217,226`), run history (`:361,366`), and space-scoping (`spaceId()`, `:295`) — but every
job is an individually-authored `JobConfig` (type: ingest/enrich/report/maintenance). **No template or
parameterization concept exists** (grep for "template" in `JobConfig.java`/`JobService.java`: no matches).
`CronExpression.java:55` is a pure cron parser (`parse`/`next`/`expression`) with no template hook either.

**Shape of the work:**
1. A `JobTemplate` model: trigger (cron | event) → condition (optional, e.g. "row count > N") → action
   (reference to a `pipeline`/`transform`/`sink` component — now resolvable once §1's widened
   `ComponentStore` covers the relevant types).
2. An authoring UI under Workbench > Jobs (a form over the template, not a raw `JobConfig` editor) —
   UI-feasible once the backend model exists; no UI blocker beyond that.
3. `JobService` gains "instantiate a job from a template" alongside today's direct `JobConfig` authoring
   (additive — existing individually-authored jobs keep working unchanged).

## Sequencing recommendation

1. Widen `ComponentStore.WRITABLE_TYPES` (+ registry dirs) — unblocks Widget-Library M2 and half of Matrices.
2. Widget-Library M2 (`QuerySpec → DuckDB`, real dataset/widget/dashboard persistence) — proves the widened
   store end-to-end on the *simplest* case (no new runtime concept, just real storage).
3. Matrices — needs the new materialization runner (net-new), but reuses the now-proven storage path.
4. Job templates — independent of the above three technically, but naturally sequenced last since it's the
   lowest-payoff item today (manual triggering already works; templates are an authoring-ergonomics win).

## Non-negotiables carried forward

- Core stays **auth-free** (`docs/PROJECT_NOTES.md:59,63,155`) — none of this reintroduces auth/RBAC.
  Space-scoping (`JobService.spaceId()`) is orthogonal to auth and should be respected in any new
  persistence path (Matrices, job templates) the same way `ComponentStore` already is.
- `connection` stays outside `WRITABLE_TYPES` — its secret-aware CRUD is intentionally separate.
- Backend work gets `mvn -o test` in the Definition of Done, on top of the UI's lint/build/test:ci trio.
