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

The pane authors a *draft for manual commit* (copy the assembled `.toon`) — it never persists by name, so
the dup-guard/name-at-save form rules don't apply. Known deferral: the dynamic field grid is still
template-driven (`ngModel`) rendered from `FieldSpec[]`; the honest fix is a `FieldSpec → AttributeSpec`
port onto `<inspecto-schema-form>`, not a mechanical reactive rewrite — still open.
