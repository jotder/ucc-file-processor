---
type: Convention
title: Mock Backends
description: The unified, flag-gated mockApiInterceptor (inspecto/mock/) — per-space handlers + seed packs — that lets the UI run fully offline, enveloping v1 responses at its edge.
resource: inspecto-ui/src/app/inspecto/mock/mock-api.interceptor.ts
tags: [mock, interceptor, offline, environment, seeds, v1-envelope]
timestamp: 2026-07-07T00:00:00Z
---

# Mock Backends

The UI can run **fully offline** via the unified **`mockApiInterceptor`** (`inspecto/mock/`), registered in
`app.config.ts` right after the `v1Interceptor`. It is a persistent, per-space in-memory backend: `MockFlags`
from `environment.ts` (`mockSpaces`, `mockStudio`, `mockFlows`, `mockJobs`, `mockOps`, `mockDemo`,
`mockConnectionProbe`, `mockAuthMode`) gate which of the ~12 **handlers** (`mock/handlers/`) answer —
production builds enable none. Build philosophy: *build UI first, full mock backend, wire the real backend
later.*

## v1 envelope at the mock edge

Because the real backend serves `/api/v1` with an envelope and the first-position `v1Interceptor` unwraps it,
the mock layer **envelopes its responses at its own edge**: `v1SuccessBody` / `v1ErrorBody` in
`mock/mock-http.ts` shape the reply exactly like the backend's `Envelope`. The handlers themselves stay
**raw-DTO** — only the edge wraps. See [API & data](api-and-data.md).

## Seed packs

`mock/seeds/` ships realistic per-space packs: the default space seeds the **Studio** (demo dataset,
**Link Analysis** link-table + saved view, **Geo Map** coordinate dataset + saved geo view), plus
`link-analysis`, `pipeline-case-studies`, `financial-audit`, `fraud-mgmt`, `telecom-ra`, and `operations`
packs.

## Notes

* The `/components/{type}` CRUD store backs the reusable component types, incl. `rule` (the
  [rule](../design-system/rule.md) templates — `'rule'` is a `ComponentType` but is **not** in the
  `COMPONENT_TYPES` palette list).
* **Persistence**: the `MockStore` snapshots every mutation to `localStorage` (memory in tests), so authored
  mock data **survives a reload**; each space is seeded exactly once, and `reset()` restores pristine seeds
  (a schema-version bump discards old snapshots).
* The Pro [data-table](../design-system/data-table.md) SQL editor runs SQL **in-browser** via AlaSQL (no
  backend) — independent of the mock layer.
