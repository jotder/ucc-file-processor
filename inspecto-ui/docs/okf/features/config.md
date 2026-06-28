---
type: Feature
title: Config
description: The TOON configuration view for the active space.
resource: inspecto-ui/src/app/modules/admin/config/config.routes.ts
tags: [feature, config, settings, toon]
timestamp: 2026-06-28T00:00:00Z
---

# Config

Route `/config` (Settings nav group). Views/edits the TOON configuration for the active
[space](../conventions/multi-space.md). Backed by `ConfigService`. Secrets are references only (`${ENV:…}`) and
never round-tripped raw — see [API & data](../conventions/api-and-data.md).
