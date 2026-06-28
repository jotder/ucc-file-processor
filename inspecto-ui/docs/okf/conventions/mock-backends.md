---
type: Convention
title: Mock Backends
description: Env-gated HTTP interceptors that let the UI run fully offline against seeded in-memory stores.
resource: inspecto-ui/src/app/inspecto/api/flow-mock.interceptor.ts
tags: [mock, interceptor, offline, environment]
timestamp: 2026-06-28T00:00:00Z
---

# Mock Backends

The UI can run **fully offline** via env-gated HTTP interceptors registered in `app.config.ts`. Each is
gated by an `environment.ts` flag so production builds exclude them. Build philosophy for the recent
authoring redesign: *build UI first, full mock backend, wire the real backend later.*

| Interceptor | Flag | Serves |
|---|---|---|
| `connectionMockInterceptor` | `mockConnectionProbe` | connection test/probe/sample ([connections](../features/connections.md)) |
| `flowMockInterceptor` | `mockFlows` | flow node taxonomy, component CRUD (`/components/{type}`), parser preview, ASN.1 modules ([flows](../features/flows.md)) |
| `opsMockInterceptor` | `mockOps` | operational data: events, alerts, objects, enrichment ([events](../features/events.md), [alerts](../features/alerts.md), [objects](../features/objects.md)) |

## Notes

* The generic `/components/{type}` CRUD store backs reusable component types: `grammar`, `schema`, `transform`, `sink`, and `rule` (the [rule](../design-system/rule.md) templates — `'rule'` is a `ComponentType` but is **not** in the `COMPONENT_TYPES` palette list).
* **Gotcha**: a full browser reload reseeds all in-memory stores. Test choose-existing round-trips via in-app navigation, not a reload.
* The Pro [data-table](../design-system/data-table.md) SQL editor runs SQL **in-browser** via AlaSQL (no backend) — independent of these interceptors.
