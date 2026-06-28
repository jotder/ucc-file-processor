---
type: Component
title: Alert
description: Inline per-screen notice banner (info/warning/error/success) with projected message content.
resource: inspecto-ui/src/app/inspecto/components/alert.component.ts
tags: [design-system, alert, banner, color-owner]
timestamp: 2026-06-28T00:00:00Z
---

# Alert

`<inspecto-alert variant="info|warning|error|success" [title]>` is the inline, per-screen notice banner —
for writes-disabled / feature-unavailable / test-result notices. The message is content-projected; it
announces via `status`/`alert` role by variant (see [accessibility](../conventions/accessibility.md)). One of
the four sanctioned [color owners](../conventions/design-system-tokens.md).

Distinct from the app-wide [connectivity banner](connectivity-banner.md) (which is for backend-down, not
per-screen). The Pro [data-table](data-table.md) SQL editor uses `<inspecto-alert variant="error">` to show a
failed-query message.

# Examples

```html
<inspecto-alert variant="warning" title="Read-only">
  Editing is disabled (no write root configured).
</inspecto-alert>
```
