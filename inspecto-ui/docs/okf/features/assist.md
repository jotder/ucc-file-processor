---
type: Feature
title: Assistant
description: The AI assist panel/dialog; degrades to a per-screen notice when disabled.
resource: inspecto-ui/src/app/modules/admin/assist/assist.routes.ts
tags: [feature, assist, ai, assistant]
timestamp: 2026-06-28T00:00:00Z
---

# Assistant

Route `/assistant` (plus the shared `assist-panel` / `assist.dialog` components in `inspecto/components`).
The AI assistant surface, backed by `AssistService`. When assist is disabled the backend returns a
per-screen `503` (surfaced via an [alert](../design-system/alert.md), not the connectivity banner). Model/provider
configured in [model settings](model-settings.md).
