# Review sheet — AI Assist console (Studio / Wave 2)

**Wave:** 2 (Builder: Studio) · **Date:** 2026-07-03 · **Files:**
`modules/admin/assist/{assist.component.ts, assist.routes.ts}` + new `assist.component.spec.ts` +
shared `inspecto/components/assist-panel.component.ts`.

The AI-assist console — a single screen exposing all 7 assist intents (KPI→SQL, Report→SQL,
NL→schedule, Suggest config, Diagnose→alert, Explain entity, Report narrative) via the reusable
`AssistPanelComponent`. Draft-only; degrades gracefully when the agent is absent (503). The smallest
Wave-2 pane (63 lines) — closes out the wave's named panes.

## R1 — Glossary

**Assist** (skill/intent), **Draft-only** (the standing caveat that assist output is never
auto-applied). Both canonical, matches `docs/GLOSSARY.md`. No change.

## R2 — Attribute audit

Not a form — an intent picker (`mat-select` over a static 7-entry list) + the shared panel's own
free-text input. `IntentMeta` (id/label/placeholder) matches what the panel needs; nothing speculative.

## R3 — UX pass

Single `<h1>` + subtitle ("Draft-only AI assist skills" — the caveat is visible up front, good), an
intent selector, and the panel below. `track s.id` on the `@for` deliberately recreates
`AssistPanelComponent` when the intent changes so its internal state (input text, prior result) resets —
a correct, minimal use of Angular's structural re-keying, not a workaround. No structural change needed.

## R4 — Reuse pass

Fully on the design system already: no bespoke UI at this level at all — the entire body is the shared
`<app-assist-panel>` (itself reusing Material form/button/spinner primitives, no hardcoded colors). The
console component itself has zero design-system surface to violate.

## R5 — Logic extraction

Nothing to extract — `AssistComponent` is a 4-line class (a static list + a selected signal-less field).
The actual logic (HTTP call, result rendering per intent, SQL sample toggle, 503/404 degradation) lives
entirely in the shared `AssistPanelComponent`, which is out of this pane's scope (it's a cross-cutting
primitive used elsewhere too — e.g. the catalog node-detail dialog's "explain this entity" embed).

## R6 — Mock contract

Runs on the unified `MockStore` via `AssistService.run()`; degrades on 503/404 per the panel's own
handling. No new endpoint from this review.

## R7 — Interview / decisions made

1. **`AssistComponent` had no spec** — added a thin one (initial intent, switching intents, a11y). The
   shared **`AssistPanelComponent` itself still has no spec** — flagged, not added this round: it's a
   heavier, cross-cutting primitive (HTTP + 7 intent-specific render branches + SQL sample preview) used
   by multiple panes (this console, the catalog node-detail dialog), so speccing it thoroughly is a
   larger, separate piece of work rather than something to fold into this small console's review.
2. **Read-only/utility by design** — nothing to author here; the panel's own free-text input is the only
   interaction, and it's draft-only (never persists). Consistent with every other read-only lens reviewed
   this wave.

## R8 — Verify (evidence)

- **Gap closed:** `AssistComponent` had no spec — added `assist.component.spec.ts` (initial selection,
  intent switching, `expectNoA11yViolations`).
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **501 passed / 0 failed / 5 skipped**
  (baseline 498/0/5; +3 new cases). Neither of the two standing flaky tests recurred this run.
- **Live smoke** (`:4204`): Assistant page loads on KPI→SQL; switching intents resets the panel and
  updates the placeholder; submitting a prompt returns a mock result. No new console errors.

**Definition of Done: met** — a11y gate added; `AssistPanelComponent`'s own spec flagged as a separate,
larger follow-up.

---

**Wave 2 (Builder: Studio) — all named panes now reviewed:** dataset-editor, widget-options (+ the
SchemaForm always-visible-optional enhancement), dashboard-editor, widgets/explore, Components/Registry,
Catalog + lineage graph, Assist. Remaining for the wave per plan §4: the **W4 lens-switcher shell** (still
not built — wires the confirmed Business-read-only Workbench gating).
