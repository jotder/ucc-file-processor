---
type: Feature
title: Geo Map Analysis
description: The offline geographic investigation studio — GeoSource/GeoQuery over Datasets on a fully-bundled MapLibre basemap, with intelligence toolboxes and saved Geo Views.
resource: inspecto-ui/src/app/modules/admin/studio/geo-map/
tags: [feature, studio, geo, maplibre, investigation]
timestamp: 2026-07-07T00:00:00Z
---

# Geo Map Analysis

The Builder-lens studio at `/studio/geo-map` for the *where* of an investigation (sibling of
[Link Analysis](link-analysis.md)'s *who-connects-to-whom*). Vocabulary:
[`GLOSSARY.md`](../../../GLOSSARY.md) §11-Geo (GeoSource, GeoQuery, GeoPoint/GeoRoute, Geo View, Layer,
Geocoder — never "marker/pin" in model names).

* **Fully offline** — MapLibre GL host (`src/app/inspecto/geo/`) over a bundled PMTiles basemap
  (`assets/basemap/`, ~2.7 MB); the offline place-table **Geocoder** resolves name→point behind a
  pluggable seam.
* **Data plane** — a **GeoSource** projects Dataset rows to GeoPoints (lat/lon column mapping) or
  weighted great-circle **od-routes**; display modes include heatmap, a time slider + timeline playback,
  and filter-to-view.
* **Intelligence toolbox** — co-location, frequent-location, and stay-point analyses; the co-location
  result bridges to a graph dialog via the shared G6 host.
* **Tools & layers** — measure / radius / polygon / notes-in-view; a layer manager with custom GeoJSON
  overlay upload; parallel-route bows.
* **Saved investigations** — a **Geo View** (Component kind `geo-map-view`: GeoSource + GeoQuery +
  display options + camera) via the shared `inspecto/investigation` lib (SavedViewStore, detail dialog,
  `uniqueNameValidator`).
* **Status** — UI shipped; the geo Widget via the `VizPlugin` seam shipped (Phase 4a). **Server-side
  projection shipped** — `GeoRoutes` (`POST /geo/projection`, `POST /geo/routes`) is the DuckDB-side
  fold of `projectPoints`/`projectRoutes` (mirrors [Link Analysis](link-analysis.md)'s
  `POST /inv/projection`): valid-WGS84 point projection with a `skipped` count, and the O/D route fold
  as a `GROUP BY` (summed weight), scaling past the ~5k-point browser cap. Plain SQL — the DuckDB
  `spatial` extension is **deliberately deferred** (no geometry op is needed, and the hardened
  `SqlSandbox` disables extension loading); see `docs/BACKLOG.md`. **Client perf follow-ons
  (progressive loading, worker-side binning) CLOSED as obsoleted 2026-07-24** — the aggregation fold +
  the hard `GEO_POINT_CAP = 5000` (`geo-projection.ts`, both paths) keep the client at ≤5k features set
  in one `setData`, which MapLibre's native GPU heatmap + internal clustering worker handle with no
  render-path bottleneck; `gridDensity()` is a tested-but-unwired pure binner (the live heatmap is 100%
  MapLibre-native) so there is nothing to offload, and the UI has no Web Worker infra. Revisit only if
  the 5k cap is deliberately raised (a product call that would defeat the aggregation's purpose); the
  modest candidate then is worker-izing the O(n²) toolbox analyses, not binning. See `docs/BACKLOG.md`. **2026-07-24 SHIPPED the UI wiring**
  — `DatasetGeoSource`/`RouteProjectionGeoSource` (`geo-projection.ts`) are now **backend-first**,
  mirroring `EntityProjectionGraphSource`/`InvService`: each calls the new `GeoService`
  (`inspecto/api/geo.service.ts`, `POST /geo/projection`|`/geo/routes`) first, folding the server's
  aggregated rows into the identical `GeoPoint`/`GeoRoute` shapes; on any failure (offline demo — the
  mock `geo.handler.ts` answers 501, or a pre-Phase-4 backend) it falls back to the original
  client-side sample fold, byte-identical to the prior behaviour. No point-count threshold — same
  simple try/backend-then/catch-fallback shape as the Link Analysis precedent, not a size-gated switch.
* **Investigation pivot** (ui-design-review R8, 2026-07-20) — a point resolving an `objectRef` offers
  "View in graph" (pivots to Link Analysis with the same record); see
  [Investigation Pivot](investigation-pivot.md) for the shared contract.

Plan + gotchas (rAF hidden-page stall, absolute style URLs, seed-once localStorage) — archived:
[`geo-map-analysis-plan.md`](../../../archived-documents/plans-archive/geo-map-analysis-plan.md) ·
case-study pack: [`geo-map-case-studies.md`](../../../superpower/geo-map-case-studies.md).
