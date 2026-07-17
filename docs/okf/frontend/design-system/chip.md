---
type: Component
title: Chip
description: Shared tag / token / filter pill (outline|soft × neutral|primary, optional removable ✕).
resource: inspecto-ui/src/app/inspecto/components/chip.component.ts
tags: [design-system, chip, tag, filter]
timestamp: 2026-07-17T00:00:00Z
---

# Chip

`<inspecto-chip>` is the single primitive for the small labelled pills scattered across the app
(widget tags & the Type/Tag filter toggles, the events correlation filter, collector glob patterns,
query tokens, reconciliation match keys). It replaces the per-component hand-rolled
`rounded-full … text-xs` spans. Content is **projected**, so a leading `<mat-icon>` or a
`font-mono` span just goes inside.

* `variant`: `outline` (hollow bordered) | `soft` (filled grey/primary tint). Default `outline`.
* `tone`: `neutral` | `primary` (a selected/active token). Default `neutral`.
* `removable` (bare boolean attribute) → renders a trailing ✕ that emits `(removed)`;
  `removeLabel` sets its `aria-label`.

The chip is the **visual, not the control**: for a clickable filter toggle, keep a real
`<button>` (so `aria-pressed` + keyboard focus stay on it) and put the chip inside, binding
`[tone]` to the active state.

# Examples

```html
<!-- a static tag -->
<inspecto-chip variant="soft">{{ tag }}</inspecto-chip>

<!-- a selectable filter toggle -->
<button type="button" [attr.aria-pressed]="active(t)" (click)="toggle(t)">
    <inspecto-chip [tone]="active(t) ? 'primary' : 'neutral'">{{ t }}</inspecto-chip>
</button>

<!-- a removable active filter -->
<inspecto-chip variant="soft" tone="primary" removable removeLabel="Clear correlation filter"
               (removed)="clearCorrelation()">
    correlation: <span class="font-mono">{{ id }}</span>
</inspecto-chip>
```
