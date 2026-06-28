---
type: Component
title: Chart
description: The Chart.js wrapper; canvas colors come exclusively from chart-tokens.ts.
resource: inspecto-ui/src/app/inspecto/components/chart.component.ts
tags: [design-system, chart, chartjs, canvas]
timestamp: 2026-06-28T00:00:00Z
---

# Chart

`<inspecto-chart>` wraps Chart.js for the dashboard and report visualizations. Because Chart.js draws to a
`<canvas>` and **cannot read CSS variables**, its palette is the one sanctioned exception to the
[no-hardcoded-color rule](../conventions/design-system-tokens.md): all canvas colors live in
`inspecto/theme/chart-tokens.ts` (an allowlisted file). A known deferred a11y item (F1) is the canvas text
alternative — see [accessibility](../conventions/accessibility.md).

Used primarily by the [dashboard](../features/dashboard.md) and report views.
