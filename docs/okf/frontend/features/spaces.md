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

* **Per-space branding lives here** (no separate Settings pane): `SpaceFormDialog` covers create **and**
  edit including logo (≤200 KB upload) / caption / footer, persisted via `GET|PUT /settings/branding`
  (per-space doc; empty → shipped default). The signal-backed `BrandingService`
  (`inspecto/api/branding.service.ts`) reloads on active-space change so the layout header stays live.
* Create asks a **Display name** and auto-derives the SpaceId slug (editable); the space id is immutable
  on edit. Each card gets **Activate** (single-select, hard-reloads to re-scope); the `default` space is
  not editable.

**"New space from template"** opens the template gallery (two-step ask-the-minimum: pick a card, then name
— id pre-filled from the template id). Templates are a server-global catalog (`GET /spaces/templates` +
`POST /spaces {template}`), deliberately *not* a Component kind. **Bundle export/import are
real-backend-only by design** (blob/zip round-trips are un-mocked; they toast cleanly in mock dev).
