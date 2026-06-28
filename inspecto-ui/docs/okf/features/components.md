---
type: Feature
title: Components Registry
description: The reusable-component registry — grammar, schema, transform, sink (and rule) definitions.
resource: inspecto-ui/src/app/modules/admin/components/components.routes.ts
tags: [feature, components, settings, registry]
timestamp: 2026-06-28T00:00:00Z
---

# Components Registry

Route under the Settings nav group. Manages reusable component definitions via `ComponentsService` —
`ComponentType` = `grammar` · `schema` · `transform` · `sink` (the palette `COMPONENT_TYPES`), plus `rule`
(used by the data-table [rule](../design-system/rule.md) save, but intentionally **not** in the palette).
Grammars are created/edited from the [flows](flows.md) parser-config dialog. Offline via the `mockFlows`
`/components/{type}` CRUD store ([mock backends](../conventions/mock-backends.md)).
