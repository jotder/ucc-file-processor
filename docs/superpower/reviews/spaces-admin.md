# Review — Spaces admin (Wave 4)

**Date:** 2026-07-03 · **Pane:** `modules/admin/spaces/` (`/spaces`) · **Verdict:** minor fixes, largely compliant

Scope: `spaces.component` (list/cards, export, unload/purge, data-source expansion) +
`space-form.dialog` (empty create) + `import-bundle.dialog` (create-from-bundle / import-into with
dry-run preview). `space-template-gallery.dialog` was already reviewed & shipped under W5
(`reviews/space-templates.md`) — not re-reviewed.

## R1–R3 (structure, state, API)
- ✅ Feature-based module, standalone components, lazy route, nav item present.
- ✅ Uses `SpacesService` signals (`multiSpace`, `availableSpaces`, `currentSpaceId`); switch contract
  (hard reload) matches the header switcher.
- ✅ Export = HttpClient blob + object URL (§7 download pattern). Import dry-run preview with conflict
  acknowledgment (`overwrite` checkbox gates import) is the reference bulk-onboarding flow.
- ✅ Destructive remove uses `confirmDestructive` with `requireText: id`; purge vs unload wording is
  explicit; active-space removal drops the selection cleanly.

## R4 findings (design system) — FIXED
1. **Banned toast** — `spaces.component.ts` reload error said "is ControlApi running?" (§8: status-0
   is the connectivity banner's job). → `apiErrorMessage(e, 'Could not load spaces.')`. Same violation
   class fixed earlier in Alerts/Diagnoses.
2. **Bare empty div** — "No data sources in this space yet." was a hand-rolled `text-secondary` div
   (5th instance of this class across the track). → `<inspecto-empty-state>`.

## R7 findings (binding form rules) — FIXED
3. **Duplicate-id = inline block** was missing in `space-form.dialog` and the create-from-bundle path
   of `import-bundle.dialog` — both relied on the server 409 toast only. → local `uniqueNameValidator`
   (case-insensitive, trimmed) on the id control, `duplicate` `<mat-error>`, ids taken from
   `SpacesService.availableSpaces()`. The gallery dialog already had it (W5); Spaces is now the
   **9th pane** on this rule. Ask-the-minimum: N/A in its strict form — the space id IS the on-disk
   folder name and request key (meaningful user-authored identifier, per `reviews/jobs.md` reasoning).

## R5 (a11y) / R6 (mock)
- ✅ Specs with `expectNoA11yViolations` already existed for all four components; extended the two
  dialog specs with inline-dup tests (stubs gained `availableSpaces` signal).
- ✅ One `h1`; icon-only buttons all carry `aria-label`; expansion toggle sets `aria-expanded`.
- Mock `/spaces` surface (W5) covers list/create/template/delete; **bundle export/import remain
  UN-mocked by design** (real backend only — they toast cleanly in mock dev). Unchanged.

## R8 verification
- lint:tokens ✓ · test:ci ✓ (see session log for counts) · prod build ✓.

## Follow-ups
- None blocking. Config pane is the last plain Wave-4 review; then C2 Expectation builder.
