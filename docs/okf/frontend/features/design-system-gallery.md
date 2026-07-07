---
type: Feature
title: Design System Gallery
description: The in-app /design route — the living gallery of shared components and copy-paste snippets.
resource: inspecto-ui/src/app/modules/admin/design-system/design-system.routes.ts
tags: [feature, design-system, gallery, reference]
timestamp: 2026-06-28T00:00:00Z
---

# Design System Gallery

Route `/design` (Settings nav group). The living, in-app gallery of the [design system](../design-system):
status badge, alert, empty state, skeleton, grid, the tiered [data-table](../design-system/data-table.md)
(with a tier selector), forms, and copy-paste snippets. It is a **source of truth** — when a shared pattern
changes, update this gallery (and the `angular-ui` rules) per the
[definition of done](../conventions/testing-and-build.md).
