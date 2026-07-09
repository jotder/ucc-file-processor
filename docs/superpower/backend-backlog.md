# Backend Backlog — Widget-Library M2, Matrices (Phase C), Job Templates (Phase D)

> **Status (reconciled 2026-07-10):** Matrices (§2 = DAT-4), Job templates (§3 = PIP-6), and Alert-Rule
> write endpoints (§4) have all SHIPPED since this doc's "not started" framing was written — see each
> section's own status line. **Only §1 Widget-Library M2 remains open.** Written after the UI-only passes of the widget library
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
- Widen `WRITABLE_TYPES` (above) so `dataset`/`widget`/`dashboard` persist for real. **✅ DONE** (API-contract W3;
  `query` added in W4).
- `QuerySpec → DuckDB` exec endpoint, replacing the offline `runSpec` seam. **✅ DONE** (API-contract W4):
  `POST /queries/{id}/run` executes a persisted `query` component against its dataset in a DuckDB sandbox
  (`com.gamma.query.QueryExecutor`) with server-side `$`-parameter resolution + the Result Set contract. This
  is the query-time **read** path; it does **not** materialize (Matrices, §2 below, remains net-new and separate).
- `DatasetResultService` M2 form — same interface as M1.4, now backed by real network calls.
- Materialized-dataset refresh / scheduled delivery (adoption-plan P4 territory).
- *(Tracked, not owned by this work):* sharing/RBAC once `inspecto-security` exists.

## 2. Phase C — Matrices (persisted summary Derived Tables) — ✅ SHIPPED 2026-07-08 (= DAT-4)

**Reconciled 2026-07-10** — this section's "nothing exists" snapshot predates the work; verify against
current code (not this doc) if in doubt. As-built (per `REQUIREMENTS.md` DAT-4): `task: materialize` on
the maintenance runner (`com.gamma.job.MaterializeTask`) — a BI-7 spec-compiled `SELECT` (or raw snapshot)
over the source Dataset's trusted relation, `COPY TO` Parquet with PIP-7's hide-old/reveal-new atomic swap
(a crash leaves only glob-invisible leftovers, self-cleaning), and the target registered/refreshed as a
normal `dataset` component — so a Matrix is queryable everywhere a Dataset is, with zero net-new read
paths (point 2 of the original shape-of-work below). Refresh today is manual/job-triggered (point 3);
job-framework scheduling (§3 note below) can drive it on a cadence. Catalog surfacing (point 4) — verify
separately if picking up follow-on polish.

<details><summary>Original shape-of-work (superseded — kept for the sequencing rationale, not as a task list)</summary>

1. A `DerivedTable`/materialization concept: run a `transform` component's config once (not per-pipeline-
   batch) and persist the output as a new DuckDB table, distinct from a pipeline's per-batch output.
2. Register it in the (now-widened) `ComponentStore` as a `dataset` (kind: derived/materialized) so it's
   selectable as a Studio Dataset — the UI-facing "Matrix" label wraps this, per `GLOSSARY.md` §6-B (a
   Matrix **is** a Derived Table; no new UI vocabulary work needed, that's Phase A, already shipped).
3. A refresh trigger — manual now, `JobService` template hook later (see §3) once that exists.
4. Surfaces in Catalog's Tables tab (kind: `DERIVED_TABLE`) — `catalog.service.ts`/`catalog-graph.ts`
   already model `DERIVED_TABLE` as a `NodeKind` (frontend is ready; only the backend row is missing).

</details>

## 3. Phase D — Job templates (trigger-condition-action)

> **2026-07-08:** expanded and superseded by [`../job-framework-design.md`](../job-framework-design.md) —
> trigger `when:` conditions, parameter declarations, and the descriptor-driven authoring form are specified
> there (§7, §8.2, §14). This section stays as the minimal-scope framing. Note: `JobTemplate.java`
> (`*_job_template.toon`, `${param}` authoring-time substitution) has landed since this section's
> "no template concept exists" snapshot was written.

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

## 4. Alert-Rule write endpoints (from `alert-rule-authoring-plan.md`, audit C3) — ✅ SHIPPED 2026-07-09

`ControlApi` POST `/alerts/rules` + PUT/DELETE `/alerts/rules/{name}` (`AlertRoutes.java`) per the
`endpoint` skill's fail-closed gate order: write-root 503 → invalid body 422 (`AlertRule.fromMap`) →
unsafe name 422 (`WriteGates.safeName`) → duplicate 409 (create) / absent 404 (update/delete). Gated on
`canAuthorAlertRules` (a no-op on Personal). Each write persists `<name>_alert.toon` (wrapped `alert{}`
block via `ConfigCodec` + `AtomicFiles`) under the write root **and** arms the rule in the running
`AlertService` so `GET /alerts/rules` + evaluation reflect it immediately. Name is the storage key
(immutable on PUT — bound from the path, not the body). Covered by `ControlApiAlertRuleWriteTest` (7
gate/happy-path cases, real HTTP).

**Two as-built decisions the original framing missed** (the plan said "the engine hot-loads them" — it
did *not*): (1) alert rules had **no** hot-reload — `AlertService` held an immutable boot-time list — so
the routes now mutate it in place (`upsert`/`remove`, volatile swap). (2) `AlertService` was `null` when
no `*_alert.toon` loaded at boot; it is **now always created** (empty until armed, matching the
always-present object store) so authoring works on a fresh space. Restart re-arms from the persisted
files as before. Path-jail (403) is defensive-only here (the name is the sole path component; `safeName`
422s any traversal first).

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
