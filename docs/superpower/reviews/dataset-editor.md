# Review sheet — Dataset editor (Studio / Wave 2)

**Wave:** 2 (Builder: Studio) · **Date:** 2026-07-02 · **Files:**
`modules/admin/studio/datasets/{dataset-editor.component.ts,.html,.spec.ts, dataset-types.ts,
dataset-columns.component.ts, dataset-measures.component.ts, datasets.service.ts}`.

First Wave-2 pane. Create/edit a Studio **Dataset** (the first component-model kind): identity + kind +
source, an embedded **Query Core** panel for virtual datasets, a column role/format tagger, and a
calculated-measures editor. Persists through the component registry as the `dataset` component type
(mock-served; real once the backend storage enum widens).

**Context that reframes Wave 2:** unlike the Wave-1 Sources/Enrichment panes (which had no specs), **every
Studio component already ships with a spec.** These panes are mature. So Wave-2 reviews are about the
SchemaForm-conversion question + the lens shell + rule consistency — not filling missing gates.

## R1 — Glossary

Canonical: **Dataset** (never "Data Store"/relation), **Widget** (Type→instance), **Measure** (never BI
"Metric"), **Dimension/Temporal** roles. All correct; `NamedMeasure` uses "Measure". No GLOSSARY change.

## R2 — Attribute audit

Dataset identity/config attributes: `name` (id, required, pattern), `kind` (virtual/physical/materialized),
`sourceName`, `physicalRef` (only for non-virtual), plus the structured `query` (Query Core model),
`columns[]` (role/format tagger) and `measures[]` (calculated). Roles are auto-inferred from column type
(temporal/measure/dimension) and user-overridable. Nothing speculative; the shape matches `DatasetConfig`.

## R3 — UX pass

Breadcrumb (Datasets / id) + single `<h1>`; Cancel/Save with a saving spinner; `<inspecto-alert>`
writes-disabled banner; a 3-col identity form with `mat-error`; progressive sections (Query only for
virtual, then Columns, then Measures). Clean. No structural change.

## R4 — Reuse pass + the SchemaForm-conversion call

Already on the design system: `<inspecto-alert>`, the shared **Query Core** (`QueryPanelComponent`), the
`dataset-columns`/`dataset-measures` sub-components, reactive form + `mat-error`. No hardcoded colors.

**SchemaForm conversion — evaluated, intentionally deferred (not a mechanical yes).** The plan named
"dataset editor → SchemaForm," but the identity form is a poor fit for `<inspecto-schema-form>` today:
1. **Bespoke cross-field reactivity** — `sourceName` changes re-infer the column tagger and reset the Query
   Core model; `kind` toggles the Query panel. The form is a controller for three sibling components, not a
   standalone scalar sheet. SchemaForm would still need the host to subscribe to its control `valueChanges`,
   saving nothing.
2. **`dependsOn` can't express it** — `physicalRef` shows when `kind !== 'virtual'` (physical **or**
   materialized). SchemaForm's `dependsOn` only matches a single `equals` value; a faithful conversion would
   require extending it (`in`/`notEquals`) for this one pane — scope on a shared component for no user gain.
3. It's a **4-field, already-tested, already-clean** form.

Per the project's own "no abstractions for single-use code" rule (and mirroring the Wave-1 jobs
name-at-save decision), forcing the conversion here would be over-engineering. **Recommendation:** target
the **widget-options dialog** (a flatter options sheet) as the real Wave-2 SchemaForm conversion, and
revisit dataset config if/when a `dependsOn` predicate upgrade is warranted by multiple panes.

## R5 — Logic extraction

Pure logic already extracted to `dataset-types.ts` (`inferRoles`, `roleFor`, `buildDataset`,
vitest-tested) and the column/measure sub-components. `dataset-editor.component.ts` (≈185 lines post-change)
is orchestration only.

## R6 — Mock contract

Runs on the unified `MockStore` via `ComponentsService` (`dataset` kind): list/get/create/remove round-trip;
503 → writes-disabled alert; **409 on a duplicate id** is now pre-empted inline (R7 #1).

## R7 — Interview / decisions made

1. **Inline duplicate-id guard added** (this review) per the confirmed product-wide rule #1: on **create**,
   the editor fetches existing dataset ids and the `name` control rejects a duplicate (case-insensitive)
   with an inline `mat-error`, instead of relying on the server 409 → toast. On **edit** the id is disabled
   (immutable), so the guard is create-only. Same `uniqueNameValidator` shape used in jobs.
2. **SchemaForm conversion deferred with rationale** (R4) — flag if you want it forced anyway.
3. **Latent note (not changed — pre-existing, out of scope):** `save()` calls `components.create` even on
   edit; the mock upserts so it round-trips, but a real backend PUT/update path may be wanted once storage
   is real. Flagged, not touched.

## R8 — Verify (evidence)

- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **483 passed / 0 failed / 5 skipped**
  (baseline 482/0/5; +1 dup-guard case). Existing create/edit/save/a11y cases unchanged (spec's
  `DatasetsService` stub gained a `list`).
- **Live smoke** (`:4204`): New dataset → typed an existing id → inline "A dataset with this id already
  exists", save no-ops; unique id saves and returns to the list. No new console errors.

**Definition of Done: met** — dup-guard closes the rule gap; SchemaForm conversion evaluated and deferred
with rationale (widget-options is the better target).
