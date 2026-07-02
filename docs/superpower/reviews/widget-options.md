# Review sheet — Widget options dialog + SchemaForm capability (Studio / Wave 2)

**Wave:** 2 (Builder: Studio) · **Date:** 2026-07-02 · **Files:**
`inspecto/component-model/attribute-spec.ts` (+spec) · `inspecto/components/schema-form.component.ts` ·
`modules/admin/studio/widgets/{widget-options.dialog.ts, widget-option-attributes.ts,
widget-options.dialog.spec.ts}` · `.claude/skills/angular-ui/SKILL.md`.

The Wave-2 designated first **SchemaForm conversion**. The dataset-editor review (R4) found that neither
named conversion target fit `<inspecto-schema-form>` as built; the product owner chose **enhance the
renderer, then convert**. This sheet delivers the enhancement + the widget-options conversion.

## The enhancement (shared, unblocks all option-sheet conversions)

`<inspecto-schema-form>`'s `tier` coupled **visibility** with **required-validation**: the only
always-visible tier (`required`) force-added `Validators.required`, so an always-visible *optional* field
(the whole widget-options dialog) was inexpressible.

**Fix — decouple validation from the visibility tier:** `AttributeSpec` gains an optional `required?: boolean`
defaulting to `tier === 'required'`; a shared `isRequired(spec)` helper is now the single source of truth,
used by **both** the renderer's `validatorsFor` and the framework-free `validateAttributes`. So
`tier: 'required', required: false` = *always visible, not mandatory*. Backward-compatible: every existing
spec (jobs/parser/node-config) is unchanged (no `required` flag ⇒ derived from tier). Documented in the
angular-ui skill Forms row. (The richer `dependsOn` — `in`/`notEquals` — is **not** added here: nothing in
this conversion needs it; it lands when the dataset-config conversion actually consumes it.)

## R1 — Glossary

**Widget** (viz instance), **Measure**, palette/legend/axis — all canonical. No change.

## R2 — Attribute audit

The cog dialog's curated option set → `WIDGET_OPTION_ATTRIBUTES`: title, subtitle, xTitle, yTitle,
legendShow, legendPosition, palette, sort, limit, stacked. All `tier: 'required'` (always visible) +
`required: false`. A closed set — no free-form styling (colors resolve only from a named palette), matching
the `WidgetOptions`/`VizRenderOptions` shape. `axis`/`legend` are flat keys here, re-nested on save.

## R3 — UX pass

Same knobs, now rendered by the shared renderer. Two minor, acceptable cosmetic shifts: booleans render as
slide-toggles (were checkboxes) and fields are single-column (were some 2-col rows) — consistent with every
other schema-form. Title/Save/Cancel unchanged.

## R4 — Reuse pass

The dialog dropped its bespoke reactive form + template for `<inspecto-schema-form>`; the only host logic
left is the flat→nested `save()` mapping (axis/legend) — genuinely bespoke, correctly kept. No hardcoded
colors (palette keys come from `CHART_PALETTES`).

## R5 — Logic extraction

The option set is now the pure, data-only `widget-option-attributes.ts`; the dialog is a thin host
(specs + initial mapping + save mapping).

## R6 — Mock contract

Unchanged — options are embedded in the `widget` component's `config.options`; the dialog just edits and
returns them. No endpoint.

## R7 — Interview / decisions made

1. **`required: false` combined with `tier: 'required'`** reads slightly oddly but is the minimal,
   backward-compatible way to express "always visible, optional." The alternative (a 4th tier, or renaming
   the tiers) was heavier churn for the same result. Flag if you'd prefer a dedicated tier name later.
2. **Checkbox → slide-toggle** for the two booleans is a deliberate consequence of standardizing on the
   renderer. Flag if the checkbox affordance matters here.

## R8 — Verify (evidence)

- **New capability tested:** `attribute-spec.spec.ts` gains a case proving `isRequired` + that a
  `tier:'required', required:false` field is not flagged blank. The dialog **had no spec** (a11y-gate gap) —
  added `widget-options.dialog.spec.ts`: the flat→nested save round-trip, empty-options defaults, and
  `expectNoA11yViolations`.
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **487 passed / 0 failed / 5 skipped**
  (baseline 483/0/5; +4 new cases).
- **Live smoke** (`:4204`): open a widget's cog dialog → all options show, none blocks save; edit legend
  position + limit + palette, save, reopen — values round-trip. No new console errors.

**Definition of Done: met** — renderer capability added (tested, backward-compatible, skill updated) and the
first Studio SchemaForm conversion shipped.
