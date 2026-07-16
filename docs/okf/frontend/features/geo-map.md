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
* **Status** — UI shipped, metadata-first (mock-backed data); the DuckDB-spatial backend + geo Widget
  via the `VizPlugin` seam are Phase 4.

Plan + gotchas (rAF hidden-page stall, absolute style URLs, seed-once localStorage) — archived:
[`geo-map-analysis-plan.md`](../../../archived-documents/plans-archive/geo-map-analysis-plan.md) ·
case-study pack: [`geo-map-case-studies.md`](../../../superpower/geo-map-case-studies.md).
