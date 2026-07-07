---
type: Component
title: Status Badge
description: The single sanctioned place to map a status/severity/level to a color (pill or cell renderer).
resource: inspecto-ui/src/app/inspecto/components/status-badge.component.ts
tags: [design-system, status, badge, color-owner]
timestamp: 2026-06-28T00:00:00Z
---

# Status Badge

`<inspecto-status-badge [value]="…">` renders a status/severity/level as a colored pill with **text + color**
(never color alone — see [accessibility](../conventions/accessibility.md)). It is one of the four sanctioned
[color owners](../conventions/design-system-tokens.md) allowed to hardcode colors. For ag-Grid cells use the
string-returning `statusBadgeHtml(value)` in a `cellRenderer`.

# Examples

```html
<inspecto-status-badge [value]="event.level" />
```
```ts
// in an ag-Grid cellRenderer:
cellRenderer: (p) => statusBadgeHtml(p.value)
```

Never re-roll a status pill or a `levelClass`/`severityClass` helper — the [token guard](../conventions/design-system-tokens.md) fails the build on those.
