---
type: Feature
title: Spaces
description: Multi-space (project) administration — the only space-aware admin view.
resource: inspecto-ui/src/app/modules/admin/spaces/spaces.routes.ts
tags: [feature, spaces, settings, multi-space]
timestamp: 2026-06-28T00:00:00Z
---

# Spaces

Route `/spaces` (Settings nav group). Administers the server's isolated spaces (projects). Together with the
header **space-switcher**, this is the only space-aware UI; everything else stays space-agnostic via the
`spaceInterceptor` (see [multi-space](../conventions/multi-space.md)). Backed by `SpacesService`; switching the
active space reloads the app.
