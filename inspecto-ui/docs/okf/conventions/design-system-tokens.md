---
type: Convention
title: Design Tokens & Styling
description: No hardcoded colors — use gamma --gamma-* vars; enforced by the lint:tokens guard.
resource: inspecto-ui/tools/check-design-tokens.mjs
tags: [styling, design-tokens, gamma, lint, ci-gate, non-negotiable]
timestamp: 2026-06-28T00:00:00Z
---

# Design Tokens & Styling

**No hardcoded colors** (hex / literal `rgb()`/`rgba()`), no `levelClass`-style status-color helpers, and no
status-tinted background fills (`bg-{red|amber|green|…}-NNN`) in `src/app/inspecto/**` or
`src/app/modules/admin/**`. Use Tailwind utilities + gamma `--gamma-*` CSS vars (scheme-aware, set on
`body.light`/`.dark`), the shared `<inspecto-status-badge>` / `<inspecto-alert>`, or the grid theme.

## The guard

`npm run lint:tokens` (`tools/check-design-tokens.mjs`, zero-dep Node; CI step in `.github/workflows/ui.yml`)
scans `.ts/.html/.scss/.css` under the two roots and fails on:

* `hardcoded-hex` — `#rgb`/`#rrggbb`/`#rrggbbaa`.
* `hardcoded-rgb` — literal `rgb(`/`rgba(` followed by a digit. **`rgba(var(--gamma-…), .12)` is allowed** (var, not a literal channel).
* `status-color-helper` — `levelClass`/`severityClass`/`toneClass`/`statusColorClass`.
* `status-bg-fill` — `bg-(red|orange|amber|yellow|lime|green|emerald|teal|rose)-N`.

`text-*`/`border-*` tones are **not** flagged (legit inline emphasis / required-field asterisks). **Escape
hatch**: append `ds-allow` in a comment on the offending line.

# Examples

* **Sanctioned color owners** (the only allowlisted files that may hardcode colors): `theme/chart-tokens.ts` (Chart.js can't read CSS vars), `components/status-badge.component.ts`, `components/alert.component.ts`, `components/connectivity-banner.component.ts`.
* **Status/severity/level color → only** `status-badge.component.ts`. **Canvas color → only** `theme/chart-tokens.ts`. **ag-Grid theme → only** `InspectoGridThemeService`.
* **CodeMirror** (the [data-table](../design-system/data-table.md) SQL editor) themes entirely with `var(--gamma-*)` — guard-clean and scheme-aware. `src/styles/**` is not scanned by the guard.

See also [accessibility](accessibility.md) (status conveyed by text + color, never color alone).
