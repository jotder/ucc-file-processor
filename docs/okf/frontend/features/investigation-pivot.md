---
type: Feature
title: Investigation Pivot
description: The shared contract for switching the investigation *view* (table/graph/map/timeline) on a selection without losing it — the R8 half of ui-design-review's investigation pivots.
resource: inspecto-ui/src/app/inspecto/investigation/pivot.service.ts
tags: [feature, investigation, link-analysis, geo-map, cross-cutting]
timestamp: 2026-07-20T00:00:00Z
---

# Investigation Pivot

Cross-cutting seam (not a routed screen) used by [Link Analysis](link-analysis.md) and
[Geo Map Analysis](geo-map.md): the ui-design-review R8 "pivot bar" — switching the *view*
(table/graph/map/timeline) on the same selection, the natural growth of the drill-drawer pattern.
Shipped 2026-07-20, scoped exactly to what the doc specified (design source:
[`ui-design-review.md`](../../../archived-documents/plans-archive/ui-design-review.md) R8;
BACKLOG §4), wired into the two hosts that exist today. `table` was already covered by
`ElementDetailDialog`'s pre-existing "Open record" action (R8's first half); `timeline` has no host
yet and was not invented.

* **`PivotService`** (`inspecto/investigation/pivot.service.ts`, root-provided):
  * `pivotTo(view, ref)` — navigates to the target host (`/studio/link-analysis` for `'graph'`,
    `/studio/geo-map` for `'map'`) carrying the selection's `ElementObjectRef` (Incident/Case id) as
    `pivotId`/`pivotType` query params.
  * `readIncoming(route)` — reads a valid incoming pivot off the route's query params, or `undefined`.
* **`ElementDetailDialog`** gained `pivotViews?: PivotView[]` on `ElementDetailData` — a button per
  offered view, shown only alongside `objectRef` (the pivot travels the record reference, so it needs
  one). Clicking a pivot button calls `PivotService.pivotTo` directly and closes the dialog with no
  result — pivoting is self-contained in the dialog; hosts don't handle a new `ElementDetailResult` kind.
* **No new backend query.** The selection travels by id; the *target* host resolves it against its own
  next-loaded data (studios are query-builder-first — nothing is loaded until the user runs a query, so
  there is no "already on screen" data to scan at navigation time):
  * **link-analysis**: offers `pivotViews:['map']` when a clicked node carries `data.objectRef`. On
    `ngOnInit` it stashes an incoming pivot; once the next graph query resolves, it scans
    `nodes[].data.objectRef` for a match and calls `focusNode(id)` (sets the `emphasis` signal) — found
    or not, the pending pivot is cleared after one attempt.
  * **geo-map**: offers `pivotViews:['graph']` when a clicked point's row resolves an `objectRef` (the
    existing `caseId`/`incidentId`/`objectId` convention). On `ngOnInit` it stashes the incoming pivot;
    once the next query's points resolve, it scans each point's `attrs` via the same `objectRefFromAttrs`
    helper and, on a match, sets `selectedId` + calls `mapView.setCamera({center, zoom})` directly —
    **not** `mapView.flyTo(id)`, because `flyTo` reads the map child's own `@Input data`, which is still
    the *previous* value in the same synchronous tick as `this.geo.set(...)`; `setCamera` takes
    coordinates straight from the freshly-resolved `ProjectedGeo` instead of depending on that binding
    having flushed.
  * **Not found** (the record isn't in the target view's current/next load) is a graceful no-op: a
    `toastr.info` and the pane stays put — no error state, no retry loop.
* **Deliberately not built:** a persistent "bar" widget, a `timeline` target (no host), or any
  auto-loading/pre-fetch of the pivoted-to record — all out of what R8 specified.

Tests: `pivot.service.spec.ts` (navigation + query-param parsing), `element-detail.dialog.spec.ts`
(button rendered/hidden, delegates to the service, closes), and additions to
`link-analysis.component.spec.ts` / `geo-map.component.spec.ts` (pivot offered on an objectRef'd
element; incoming pivot resolves to focus/select; incoming pivot with no match toasts).
