---
type: Architecture
title: Inspecto UI Architecture
description: Feature-based layout, standalone components, signal state, and the shared-core boundary.
resource: inspecto-ui/src/app/
tags: [architecture, feature-based, standalone, signals, layering]
timestamp: 2026-06-28T00:00:00Z
---

# Architecture

inspecto-ui is **feature-based**. The goal is a frontend that stays maintainable, scalable, testable, and
consistent at enterprise scale: maintainability over cleverness, reusability over duplication, explicitness
over magic, accessibility over aesthetics.

## Layering

```
src/app/
  inspecto/                 # SHARED / CORE (cross-feature). Never import a feature from here.
    api/                    # @Injectable({providedIn:'root'}) services + barrel index.ts + interceptors
    components/             # shared UI: status-badge, alert, empty-state, skeleton, chart, connectivity-banner
    grid/                   # ag-Grid theme + helpers (index.ts)
    query/  rule/  data-table/   # the queryable-table stack (see the design system)
    theme/                  # chart-tokens.ts (the ONLY place canvas colors are hardcoded)
    testing/                # a11y.ts (expectNoA11yViolations)
    confirm.service.ts, ...
  modules/admin/<feature>/  # FEATURES: standalone component(s) + .html + <feature>.routes.ts
  layout/                   # app shell (connectivity-banner mounts here)
  mock-api/common/navigation/data.ts   # nav items
```

## Rules

* **Standalone components** + `ChangeDetectionStrategy.OnPush` (default for new components).
* **No cross-feature dependencies.** A feature never imports another feature; share via `inspecto/`. A pane may be reused across routes via `ActivatedRoute.snapshot.data` (e.g. Cases/Issues = one `ObjectsComponent` — see [objects](./features/objects.md)).
* **Container vs presentational**: containers inject services + hold signal state; shared presentational components in `inspecto/components` take `input()`/`output()` and do no HTTP.
* **Vendored code is out of scope**: `src/@gamma/**` and `modules/auth/**` — don't restyle, audit, or guard.

## State

Signals for component + shared service state; `computed()` for derived; `effect()` sparingly; `linkedSignal`
for "derived but locally editable". RxJS for async pipelines and HTTP. Shared cross-cutting state lives in a
root service exposing signals (pattern: `ConnectivityService`, `SpacesService`). See
[forms & state](./conventions/forms-and-state.md).

## The app shell

`layout.component` is a flex **column**; `<inspecto-connectivity-banner>` is its first child
(`display:contents` — consumes no space when hidden, stacks full-width on top when shown). Don't give
layout-level siblings a growing `flex` or they steal width from the content column. See
[errors & connectivity](./conventions/errors-and-connectivity.md).

## Source-of-truth docs

The shared, profile-independent sources of truth are the in-app `/design` gallery
(see [design-system gallery](./features/design-system-gallery.md)), the `angular-ui` rules, and
`docs/ui/accessibility-audit.md`. When code and docs disagree, fix one — don't silently diverge.
