---
type: Convention
title: Accessibility (WCAG 2.2 AA)
description: One h1 per page, labelled icon buttons, focus-visible rings, and the axe-core test gate.
resource: inspecto-ui/src/app/inspecto/testing/a11y.ts
tags: [accessibility, wcag, axe, a11y, non-negotiable]
timestamp: 2026-06-28T00:00:00Z
---

# Accessibility

A11y is not optional. Target **WCAG 2.2 AA**.

* One semantic `<h1>` per page.
* `aria-label` on **every icon-only button** (e.g. the [data-table](../design-system/data-table.md) toolbar icons).
* Never `outline:none` without a `:focus-visible` ring. The global ring is 2px primary, 2px offset; on native controls inside a Material field the inner ring is suppressed and moved to the field wrapper (one clear focus affordance).
* `scope` on table headers. Status conveyed by **text + color**, never color alone (use `<inspecto-status-badge>`).
* Reactive forms surface errors inline via `<mat-error>`. Respect `prefers-reduced-motion`.
* Async / degraded states announce via `role="alert"` + `aria-live` (see the [connectivity banner](../design-system/connectivity-banner.md) and [alert](../design-system/alert.md)).

## The automated gate

Add `await expectNoA11yViolations(fixture.nativeElement)` (`inspecto/testing/a11y.ts`) to new component
specs. Runs in CI via `npm run test:ci`. `color-contrast` + page-level rules are excluded under jsdom —
contrast is covered by the [token guard](design-system-tokens.md) + the manual audit.

The living a11y findings (e.g. F1: chart `<canvas>` text alternative) are tracked in the repo at
`docs/ui/accessibility-audit.md`.
