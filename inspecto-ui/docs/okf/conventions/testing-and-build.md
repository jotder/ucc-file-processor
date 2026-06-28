---
type: Convention
title: Testing, Build & Definition of Done
description: vitest + axe, the lint:tokens guard, the production build, preview verification — run before claiming done.
resource: inspecto-ui/package.json
tags: [testing, build, vitest, ci, definition-of-done]
timestamp: 2026-06-28T00:00:00Z
---

# Testing, Build & Definition of Done

## Commands

* `npm run lint:tokens` — the [design-token guard](design-system-tokens.md) (no hardcoded colors).
* `npm run build` — production build: AOT type-check + budgets (initial ≤ 3mb warn / 5mb error). New CommonJS deps must be added to `allowedCommonJsDependencies` in `angular.json` (e.g. `alasql`).
* `npm run test:ci` — vitest (jsdom) + `TestBed`; **add `expectNoA11yViolations` to new component specs** (see [accessibility](accessibility.md)).

## Testing notes

* Signal inputs are set with `fixture.componentRef.setInput('name', value)`.
* A component containing a **`@defer`** block requires `await TestBed.compileComponents()` before `createComponent` (e.g. the [data-table](../design-system/data-table.md), whose SQL editor is deferred).
* **G6** can't instantiate in jsdom — unit-test graph hosts on the empty/no-graph path.
* Framework-free logic (the data-table `core/`/`sql/`, [query](../design-system/query.md)) is unit-tested directly without `TestBed`.

## Definition of Done (run before claiming completion)

1. `lint:tokens` green. 2. `build` green (AOT + budgets). 3. `test:ci` green (unit + a11y).
4. **Verify in the browser preview** — load the route, confirm behavior in the DOM, check console for errors (don't ask the user to check manually).
5. If a pattern changed, update the [`/design` gallery](../features/design-system-gallery.md) and the shared `angular-ui` rules.
6. Commit per the repo's branch policy: `feat:`/`test:`/`docs:` → `master` only; `fix:` → oldest supported branch then merge-forward. Never push without an explicit ask.
