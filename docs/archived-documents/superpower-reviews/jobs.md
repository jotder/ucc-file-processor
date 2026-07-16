# Review sheet — Jobs / Scheduler pane + job-form dialog (Builder / Workbench)

**Wave:** 1 (Builder/Workbench) · **Date:** 2026-07-02 · **Files:**
`modules/admin/jobs/{jobs.component.ts,.html, job-form.dialog.ts,.html,.spec.ts, job-attributes.ts,
job-display.ts, job-detail/*, job-run-detail.dialog.ts, job-runs.dialog.ts}` +
`inspecto/components/schema-form.component.ts` (shared, +duplicate error message).

The Jobs pane was the **W2 pilot** of `<inspecto-schema-form>` (Wave 0), so it entered this review already
well past the debt of the other Workbench panes. The review confirms compliance and closes the one real
gap against the product-wide form rules confirmed 2026-07-02.

## R1 — Glossary

Canonical: **Job** (a scheduled unit of work), **Scheduler** (the pane / registry), **Run** (one
execution), **Trigger** (cron / event / manual). No banned synonyms; `Schedule`/`Reporting` are the two
lenses, not new concepts. No GLOSSARY change.

## R2 — Attribute audit

Job attributes are already declared as `AttributeSpec[]` in `job-attributes.ts`, tiered:
- **required:** `name` (id), `type`, `scheduleMode` (Trigger), plus `cron` **or** `onPipeline` via
  `dependsOn: scheduleMode`.
- **optional:** `enabled` (armed).
- **advanced:** `catchUp` (restored during the pilot — it was silently missing from the old hand-built form).
- **bespoke:** the `params` key/value array (arbitrary per-job parameters — no fixed shape, stays
  hand-built below the schema-form), and the cron-preset quick-pick (a convenience input, not an attribute).

No speculative attributes to remove; nothing missing after the pilot's `catchUp` restore.

## R3 — UX pass

Already strong: single `<h1>` "Scheduler"; a two-lens `mat-button-toggle` (Schedules / Reporting);
icon-only Refresh with `aria-label` + tooltip; primary "New job" only on the Schedules lens; per-row icon
actions (run / enable-disable / reschedule / edit / delete) with hints. Reporting lens: metric cards,
failure-trend `<inspecto-chart>`, a filter toolbar, and a "Live tail" slide-toggle driving
`visibleInterval(5s)` (pauses when hidden). Empty/loading states present on both lenses. No change needed.

## R4 — Reuse pass

Fully on the design system: `<inspecto-data-table tier="standard">` (both grids, CSV export + column
chooser), `statusBadgeHtml()` cell renderers, `<inspecto-empty-state>`, `<inspecto-chart>`,
`InspectoConfirmService` (destructive delete), `<inspecto-alert variant="warning">` (writes-disabled),
and `<inspecto-schema-form>` for the form. No one-off pills, banners, or bare ag-Grid hosts. No hardcoded
colors (chart series via `CHART_SERIES`).

## R5 — Logic extraction

Display helpers (`fmtDuration`, `scheduleSummary`, `whatScheduled`) already live in the pure `job-display.ts`
(shared with the detail view, vitest-tested). `jobs.component.ts` is 330 lines but, like the pipeline editor
(see that sheet's R7), the bulk is legitimate two-lens orchestration — 5 CRUD/trigger flows, the reporting
forkJoin + chart mapping, and live-tail wiring — not extractable shaping. Left as-is; splitting would
fragment cohesive signal state for no gain (project rule: no abstractions for single-use code).

## R6 — Mock contract

Runs fully on the unified `MockStore` jobs handler: list / get / create / update / setEnabled / trigger /
remove round-trip and survive reload; `/jobs/{metrics,runs,failures}` fall through to real-backend
behavior (reserved reporting). Writes-disabled surfaces via 503 → the alert banner. No new endpoint.

## R7 — Interview / decisions made

1. **name-at-save two-step does NOT apply to jobs — deliberate.** The ask-the-minimum rule's create-flow
   mechanism (name the artifact at SAVE time, pre-filled `<type>_<host>`-style) presupposes the name is an
   *incidental label derivable from config* (a connection's host). A **job id is a meaningful,
   user-authored identifier** (`cdr_ingest_daily`) — the very thing the user came to create — with no
   natural config to derive it from. Forcing a save-step rename with an auto-prefill like `ingest_cron`
   would be *worse* UX, not leaner. The rule's **principle** (ask only what's needed now) is already met by
   the tiered schema-form: required fields up front, `enabled` under Optional, `catchUp` behind the gear.
   → Keep the id up front. Flag if the product owner wants the connection-style two-step here anyway.
2. **Inline duplicate-id guard added** (this review) to honor the confirmed product-wide rule #1: on
   **create**, the id control now rejects a name already in use (case-insensitive) with an inline
   `mat-error` ("Job id already exists"), instead of relying on the server 409 → toast. On **edit** the id
   stays disabled (immutable storage key), so the guard is create-only. Implemented generically: the host
   attaches a `uniqueNameValidator` to the schema-form's `name` control (same seam it already uses to
   disable on edit), and the shared `errorFor()` gained a generic `duplicate` → "{label} already exists"
   message — reusable by any future SchemaForm host.

## R8 — Verify (evidence)

- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **475 passed / 0 failed / 5 skipped**
  (baseline 474/0/5; +1 = the new dup-id case). New spec case: dup-id blocks save then creates once unique
  (case-insensitive); existing edit/cron/a11y cases unchanged.
- **Live smoke** (`:4204`): New job → typed an existing id → inline "Job id already exists", Create disabled
  path (save no-ops); changed to a unique id → created; reload persisted. No new console errors.

**Definition of Done: met.** name-at-save intentionally not applied to jobs (R7 #1, flagged); dup-guard
closes the one rule gap.
