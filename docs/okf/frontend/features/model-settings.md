---
type: Feature
title: Model Settings
description: AI model / provider settings for the assistant and AI-backed features.
resource: inspecto-ui/src/app/modules/admin/model-settings/model-settings.routes.ts
tags: [feature, model-settings, settings, ai]
timestamp: 2026-06-28T00:00:00Z
---

# Model Settings

Route `/model-settings` (Settings nav group). Configures the AI model / provider used by the
[assistant](assist.md) and AI-backed features. Backed by `ConfigService`. When AI is disabled the backend
returns a per-screen `503` (not backend-down) — see [errors & connectivity](../conventions/errors-and-connectivity.md).
