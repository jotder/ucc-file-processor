# Review sheet — Parser config dialog (Pipelines / Workbench)

**Wave:** 1 (Builder/Workbench) · **Date:** 2026-07-02 · **Files:** `modules/admin/pipelines/{parser-config.dialog.ts,.html,.spec.ts}` + `parser-types.ts` (+ new `parser-types.spec.ts`)

Per `docs/superpower/frontend-review-and-completion-plan.md` §3, this is the Wave-1 designated first
big `<inspecto-schema-form>` conversion — the parser config editor covers 9 file formats behind one
dialog and was the single largest hand-rolled property sheet in the app.

## R1 — Glossary

No new concepts. Uses existing canonical terms: **Parser**, **Component** (`kind: grammar`), **Pipeline**
Step. No banned synonyms introduced.

## R2 — Attribute audit (the core of this review)

Every property of every one of the 9 parser types was reclassified into `required | optional | advanced`
(previously all fields sat in one flat "Properties" section + a separate "Sampling" section, with no
distinction between "defines whether this parses at all" and "rarely-touched tuning knob"). Rationale
applied uniformly:

- **required** — the property changes whether the format parses correctly at all (e.g. DSV's
  `column_delimiter`/`header_position`; JSON's `mode`/`root_path`; XML's `record_xpath`; ASN.1's
  `encoding_rules` + the schema module).
- **optional** — commonly adjusted but has a working default (`extension`, `encoding`, `datetime_format`,
  `quote_char`, …).
- **advanced** — rarely touched tuning knobs, including the shared Sampling controls
  (`sample_rows`/`default_column_length`/`count_length_in_bytes`) which are now advanced on **every**
  type (previously always visible, unconditionally, for all 9 formats).

Full per-type table lives as code comments + literal `tier:` values in `parser-types.ts` (the source of
truth — this sheet doesn't duplicate all ~70 field classifications). Verified by
`parser-types.spec.ts` (`nothing left unclassified`, spot-checks per type, the Sampling-is-always-advanced
invariant).

**New capability used:** `AttributeSpec.dependsOn` — TXT's `record_length` now only shows while
`frontend === 'fixedwidth'` (previously always visible, useless in `line` mode). One dependency net new;
no others were compelling enough to add without real backend semantics to validate against.

## R3 — UX pass

- **Progressive disclosure** replaces flat sections: required fields visible immediately, optional
  behind a "Optional settings (N)" toggle, advanced behind the ⚙ gear — consistent with `/design` and
  the jobs-dialog pilot.
- **Ask-the-minimum rule applied** (binding, 2026-07-02): the grammar **name is now asked only at save
  time** via a two-step flow (config → save), mirroring `connections/connection-form.dialog` exactly —
  pre-filled `<type>_grammar`, duplicate-checked. Previously the name field sat at the top of the dialog
  from the first click, before the user had configured anything.
- Editing an existing grammar (Grammar dropdown, or the node's prior `use` binding) **skips the two-step**
  and saves straight through with the id locked — matches the connections pattern's `isEdit` branch.
- Minor UX hardening found in passing: selecting "＋ New grammar" from the dropdown now also clears the
  stale Test-parse preview/error from whatever was previously loaded (old code left it stranded).

## R4 — Reuse pass

`<inspecto-schema-form>` replaces ~65 lines of repeated `@switch (p.control)` Material markup per type.
The one bespoke exception — the ASN.1 schema-module picker (network-backed dropdown + local upload) —
is intentionally kept outside the generic renderer (a project-wide form component shouldn't grow a
network-picker control for one format) and rendered directly by the dialog, positioned above the
schema-form so all "no disclosure needed" content reads together at the top of the pane.

## R5 — Logic extraction

- `toAttributeSpecs(type)` — pure conversion `ParserProp[] → AttributeSpec[]` (excludes the `module`
  control), added to `parser-types.ts` (already framework-free).
- `modulePropFor(type)` — pure lookup for the one bespoke property.
- `sectionsFor`/`propsInSection` deleted (dead code once section-grouping was replaced by tiers).
- The dialog component itself lost its hand-built `propsForm` FormGroup lifecycle (~15 lines) in favor of
  binding `[specs]`/`[initial]` to the shared renderer.

## R6 — Mock contract

No handler changes needed — `componentsHandler` (unified mock store, Wave 0/1) already serves grammar
CRUD, `previewParse`, and the ASN.1 module library. Live-verified: create persists to
`localStorage['inspecto.mock.v3']` under `component:grammar`, survives reload, and shows in the Grammar
dropdown on the next dialog open.

## R7 — Interview / decisions made without asking (flagged for the batched review)

1. **Exact tier per property** is my classification (R2) — reasonable defaults, not user-validated field
   by field. Flag if a specific format's defaults feel wrong in practice.
2. **New-name duplicate guard**: typing a name at the save step that collides with an existing grammar is
   now **blocked** (`mat-error`, matches connections) rather than the old silent-overwrite behavior. This
   is a deliberate consistency call with the just-established connections pattern, not something asked
   about explicitly — worth confirming this is the desired behavior product-wide.
3. **Type-switch while bound to an existing grammar** still preserves the old semantics (redefine the same
   saved grammar under a new parser type, id unchanged) — not revisited, since changing it would be a
   larger behavior change outside this review's scope.

**Answered (batched to product owner, 2026-07-02):** #2 — the duplicate-name **block** (mat-error, no
silent overwrite) is confirmed the **product-wide rule** for all authoring forms; no change, and it's the
standing default for every future form. #1/#3 stand as recorded assumptions (flag if a specific format's
tier feels wrong once real backend parser config lands).

## R8 — Verify (evidence)

- **Live smoke** (`:4204`, this session): opened the dialog on the `cdr_ingest` pipeline's real `parse`
  node via the app's own `PipelineEditorComponent.openNodeConfig` (canvas double-click isn't scriptable —
  G6 renders to `<canvas>`, per the angular-ui skill's documented G6 testing limitation). Verified:
  - DSV required tier shows exactly `column_delimiter` + `header_position`; "Optional settings (5)"
    collapsed; advanced gear present.
  - Two-step save: "Continue" → title "Save parser", name pre-filled `dsv_grammar`, actions
    `Back / Cancel / Create parser`; entering a name and confirming **persists to the mock store** and
    closes the dialog with the node bound via `use: grammar/<name>`.
  - **Found + fixed a real bug during this smoke**: the initial implementation called
    `this.schemaForm.validate()` unconditionally in `save()`, but `<inspecto-schema-form>` unmounts once
    the dialog moves to the `save` step (it lives inside the `@if (step()==='config')` block) — the second
    `save()` click threw `Cannot read properties of undefined (reading 'validate')`. Fixed by snapshotting
    `buildContent()` once, at the moment of leaving the config step, and reusing that snapshot to persist
    (see `persist()` in `parser-config.dialog.ts`). Re-verified the full flow afterward with zero console
    errors.
  - Selecting an existing grammar from the dropdown → title stays "Configure parser · parse", actions
    `Cancel / Save parser` (no "Back", two-step correctly skipped).
  - Switching to ASN.1 renders the bespoke module picker above the schema-form's `Encoding rules`;
    attempting Save with no module selected blocks with an inline `mat-error` and does **not** crash or
    advance — confirms the module-required gate added in this review.
- **Automated**: `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **441 passed / 0 failed / 5 skipped**
  (baseline 432/0/5 — +9 net new from `parser-types.spec.ts` + the rewritten
  `parser-config.dialog.spec.ts`).

**Definition of Done: met** — all 8 steps evidenced above.
