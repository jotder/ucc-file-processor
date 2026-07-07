---
type: Component
title: Empty State
description: Reusable "nothing here yet" placeholder with icon, message, and an optional action.
resource: inspecto-ui/src/app/inspecto/components/empty-state.component.ts
tags: [design-system, empty-state]
timestamp: 2026-06-28T00:00:00Z
---

# Empty State

`<inspecto-empty-state>` renders an intentional empty state (icon + title + message + optional action)
instead of a blank panel. For empty **grids**, the [grid](grid.md) `noRowsOverlay()` helper produces an
equivalent overlay.

# Examples

```html
<inspecto-empty-state
  icon="heroicons_outline:queue-list"
  title="Nothing yet"
  message="No events match the current filters."
  actionLabel="Clear filters"
  (action)="reset()" />
```
