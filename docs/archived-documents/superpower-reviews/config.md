# Review — Config (Wave 4, final pane)

**Date:** 2026-07-03 · **Pane:** `modules/admin/config/` (`/config`) · **Verdict:** minor fixes + first spec

Scope: spec-driven config authoring (GET `/config/spec/{type}` → dynamic field grid → POST `/validate`)
plus the validate-a-saved-file mode. Ported from inspector-ui onto the gamma shell.

## R1–R3 (structure, state, API)
- ✅ Feature module, standalone, lazy route, nav item. `ConfigService` in `inspecto/api` with barrel export.
- ✅ Skeleton grid during spec load (`aria-busy`), assembled-config preview with copy-to-clipboard.
- ⚠️ **Template-driven forms (`ngModel`) throughout** — legacy per skill §2. The form is fully dynamic
  (rendered from `FieldSpec[]`), so the honest fix is a port onto `<inspecto-schema-form>` (map
  `FieldSpec` → `AttributeSpec`), not a mechanical reactive-forms rewrite. **Deferred follow-up** —
  out of surgical-review scope; recorded here so it isn't lost.

## R4 findings (design system) — FIXED
1. **Severity as plain text** in the findings table → `<inspecto-status-badge [value]="f.severity" />`
   (same class as the Alerts/Diagnoses fixes).
2. **Generic error toasts** ("Validation failed") → `apiErrorMessage(e, …)` on both validate paths.
3. **Silent spec-load failure** — error left the draft tab blank with no signal → warning toast +
   `<inspecto-empty-state>` ("Spec unavailable") for the `!spec && !specLoading` state.

## R5 (a11y) — FIXED
4. **No editor in the dynamic grid had an accessible name** — the field name is a sibling div, not a
   `mat-label`, so every `mat-select` / text / number / tags input and the bare `<mat-checkbox>`
   failed axe (`aria-input-field-name` + `label`, caught by the new spec's gate) →
   `aria-label`/`[attr.aria-label]` = `f.path` on all five editor kinds.
- ✅ One `h1`; findings table headers carry `scope="col"`; required marker is text-tone (allowed).
- **First spec added** (`config.component.spec.ts`): axe on the loaded form AND the spec-unavailable
  empty state; dotted-path assembly + tags normalization; findings render with a badge.

## R6 (mock) — IMPROVED
5. Mock `POST /validate` was a hardcoded `{clean: true}` — the findings table was unreachable in demo.
   Draft mode now checks the type's `CONFIG_SPECS` for **missing required fields** (walking dotted
   paths through the assembled config) → ERROR findings; `safetyChecked` echoes the flag; file mode
   stays always-clean. Handler spec case added.

## R7 (form rules)
- N/A: the pane authors a *draft for manual commit* (copy the .toon), it never persists by name —
  no save step, so ask-the-minimum/dup-guard don't apply.

## R8 verification
- lint:tokens ✓ · test:ci ✓ · prod build ✓ (see session log for counts).

## Follow-ups
- Port the dynamic field grid onto `<inspecto-schema-form>` (FieldSpec → AttributeSpec), removing the
  last `ngModel` usage in a reviewed pane.
- Config is the 9th of 9 Wave-4 panes — **Wave 4 review coverage complete**; C2 Expectation builder
  (P1) is the remaining Wave-4 completion item.
