# Per-space branding, merged into Spaces

**Status:** shipped (UI-only, mock-backed; master-only) · **Date:** 2026-07-07

## Context
Let users personalize the product brand — sidebar **logo**, **caption**, **footer text** — per space.
Branding editing is **merged into the Spaces admin page** (no separate Settings pane). Also fixes the
New Space form and adds a single-select **Activate** action.

## What shipped

### Branding storage + service
- Per-space doc `GET|PUT /settings/branding` → `{ logoDataUrl, caption, footerText }` (empty → `null` =
  use shipped default). Mock: `settings.handler.ts` (`spaceOf()` scopes to `/spaces/<id>/…` when present,
  else the active space).
- `BrandingService` (`inspecto/api/branding.service.ts`): signal-backed `logoUrl()/caption()/footerText()`
  with default fallback; an `effect` reloads on active-space change so the layout header stays live.
  `get()/save()` target the active space (header); `getFor(id)/saveFor(id)` target any space (Spaces edit).
- Header bindings: `classic.component` (logo + caption + footer) and `dense.component` (logo) bind to the
  service.

### Spaces page (merge + bug fix + activate)
- **`SpaceFormDialog`** is now create **and** edit, including branding (logo upload ≤200 KB + preview,
  caption, footer). **Create** asks a **Display name** and auto-derives the SpaceId slug (editable under
  "Edit id") — this fixes the bug where typing a normal name left **Create** disabled (the only field was a
  strict slug). **Edit** prefills name/description + branding; the id is immutable. Persists via
  `POST /spaces` / `PUT /spaces/{id}` (+ mock `updateSpace`) and `saveFor(id, branding)`.
- **`SpacesComponent`**: each card gets **Activate** (non-active only; single-select — reuses
  `currentSpaceId`, hard-reloads to re-scope) and **Edit** (non-`default` only). The **`default`** space is
  **not editable** and cannot be removed.
- The standalone `Settings → Branding` route/pane + both menu entries were removed.

## Verification
- GAUNTLET green: lint:tokens, `test:ci` (1069 passed), production build. Specs:
  `branding.service.spec` (defaults + save + getFor/saveFor), `space-form.dialog.spec` (slug derivation,
  duplicate/pattern, edit prefill), `settings.handler.spec` (per-space scoping), `spaces.handler.spec`
  (PUT update).
- Live: New Space name→id→Create works; created space carries its branding; Edit round-trips name + branding;
  Activate re-scopes and the header reflects the active space's branding; `default` has no Edit; no console errors.
