# Accessibility audit — `inspecto-ui` (WCAG 2.2 Level AA)

**Date:** 2026-06-16 · **App:** `inspecto-ui/` (Angular 21 + Material M2 + Tailwind on the gamma/Fuse shell)
**Standard:** WCAG 2.2, conformance target **Level AA** · **Scope:** the inspecto operator panes (`modules/admin/**`)
and shared components (`inspecto/**`); vendored Fuse auth/error scaffolding excluded.

This is the **manual** half of UI/UX-audit Long-term #2 — an **audit + findings report**. Fixes are **deferred** to a
separate prioritized pass (none were applied here). The **automated** half (axe-core regression gate) is described below
and is live in CI.

> This is a self-assessment by heuristic walkthrough + automated tooling, not a formal third-party VPAT/certification.

---

## 1. Already remediated (Immediate + Short-term + Medium-term batches)

Verified still in place; not re-listed as findings:

- **1.4.11 / 1.4.3 contrast** — semantic status tones consolidated in `status-badge.component.ts` (100/800 light,
  900/200 dark ≈ 6–8:1); `text-hint` tokens lifted to AA in `theming.js`.
- **2.4.6 / 1.3.1 headings** — exactly one semantic `<h1>` per page (17 pages).
- **4.1.2 names** — icon-only buttons carry `aria-label` (shared `InspectoActionsCell` + layout buttons).
- **2.4.7 focus visible** — global `outline:none` replaced with a `:focus-visible` ring.
- **1.3.1 tables** — `scope="col"/"row"` across data tables.
- **3.3.1 / 3.3.3 forms** — Reactive forms with inline `<mat-error>`; `markAllAsTouched()` on invalid submit.
- **1.3.1 status not by color alone** — status badges always render the text token alongside the color.
- **2.2.2 pause/stop/hide** — the Events live-tail auto-refresh is opt-in via a toggle (not auto-playing).
- **prefers-reduced-motion** — `<inspecto-skeleton>` disables its pulse under reduced-motion.

---

## 2. Automated gate (axe-core)

- **Wiring:** `axe-core` (devDependency) run inside the existing **vitest + jsdom** unit tests via
  `src/app/inspecto/testing/a11y.ts` → `expectNoA11yViolations(el)`. Runs in CI as part of `npm run test:ci`
  (the **Unit tests** step of `.github/workflows/ui.yml`) — no browser needed.
- **Covered today:** the shared design-system primitives — `status-badge`, `skeleton`, `empty-state`
  (`*.a11y.spec.ts`). Extend by adding an `expectNoA11yViolations(fixture.nativeElement)` assertion to any
  component spec.
- **Rules excluded in jsdom** (and why): `color-contrast` (needs real painting/layout — jsdom has none; contrast is
  covered manually above and the design-system token guard prevents off-palette colors), and the page-level rules
  `region` / `landmark-one-main` / `page-has-heading-one` / `html-has-lang` / `document-title` / `bypass` (meaningless
  against an isolated component fixture — they belong to a future route-level/browser pass).
- **Limitation:** unit-level axe verifies roles/names/aria/structure, **not** rendered contrast or full-page
  landmark/reflow behavior. Those need the real-browser pass noted in §5.

---

## 3. Findings (fixes deferred)

Severity: **Moderate** = a real AA gap affecting some users; **Low** = edge/needs-verification or minor.

| # | WCAG SC (level) | Severity | Location | Finding | Recommendation |
|---|---|---|---|---|---|
| F1 | 1.1.1 Non-text Content (A) | **Moderate** | `inspecto/components/chart.component.ts` (dashboard, sources) | The Chart.js `<canvas>` has no text alternative (`role`/`aria-label`/summary). A screen reader announces nothing for each chart. | Add `role="img"` + an `aria-label` summarizing the chart, and/or a visually-hidden data table / caption. Adjacent KPI cards partially mitigate but don't replace it. |
| F2 | 4.1.3 Status Messages (AA) | **Moderate** | Grids with async/live updates (Events live-tail, search result counts) | Result counts and live-tail row additions are not announced to assistive tech. (Toasts are fine — ngx-toastr emits `role="alert"`.) | Add a polite `aria-live` region announcing e.g. "N events, updated" after each load/tick. |
| F3 | 1.4.1 Use of Color (A) | **Low** | `chart-tokens.ts` series, multi-series charts | Line/bar series may be distinguishable by color only. | Ensure every series has a text label/legend entry; consider dash/pattern for line charts. |
| F4 | 2.4.11 Focus Not Obscured (AA, **new in 2.2**) | **Low** | Sticky page headers/toolbars over scrollable panes | A control focused via keyboard could be hidden behind a sticky header when the pane is scrolled. | Add `scroll-margin-top` to focusable content (or `scroll-padding-top` on the scroll container) equal to the sticky header height. |
| F5 | 2.5.8 Target Size (Minimum) (AA, **new in 2.2**) | **Low** | `gamma-mat-dense` toolbar controls; ag-Grid action icons | Standard `mat-icon-button` is 40×40 (pass ≥24×24). Dense filter controls and grid action icons need spot-verification at ≥24×24 with adequate spacing. | Measure dense controls; bump to ≥24×24 (or add spacing exception) where short. |
| F6 | 2.5.7 Dragging Movements (AA, **new in 2.2**) | **Low** | ag-Grid column resize/reorder | These are drag gestures. ag-Grid exposes header keyboard interaction + a column menu, so a non-drag path likely exists — **verify**. | Confirm sort/resize/reorder are reachable without dragging; document the keyboard path. |
| F7 | 1.4.10 Reflow (AA) | **Low** | Toolbars/forms at 320px | Wide data grids may scroll horizontally (data-table exception applies). Verify filter toolbars and forms reflow without loss at 320px / 400% zoom. | Manual check at 320 CSS px; the toolbars already use `flex-wrap`. |

### Conformant / notable (no action)

- **3.3.8 Accessible Authentication (AA, new in 2.2)** — `/connect` accepts a pasted operator token; no cognitive
  test, paste allowed → **conformant**.
- **2.1.2 No keyboard trap** — MatDialog manages focus trap/restore → **conformant**.
- **3.2.3 Consistent Navigation** — single shared nav across layouts → **conformant**.

---

## 4. Suggested fix priority (when the deferred pass is scheduled)

1. **F1** (chart text alternatives) — highest user impact, self-contained in `chart.component.ts`.
2. **F2** (live-region announcements) — a small shared `aria-live` helper reusable across grids.
3. **F4 / F5 / F6 / F7** — verification + small CSS/markup tweaks.
4. **F3** — design-token / legend review.

## 5. Out of scope here (future)

- **Real-browser axe pass** (e.g. Playwright) to cover `color-contrast`, landmarks, full-page reflow — deliberately
  not added now (keeps CI browser-free per the project's lean-deps stance).
- Vendored Fuse auth/error pages (`modules/auth/**`).
- A formal third-party audit / VPAT.
