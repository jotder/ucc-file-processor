# Review sheet — Geo Map Analysis Studio (Phases 1–3b)

**Route:** `/studio/geo-map` · **Plan:** [`../geo-map-analysis-plan.md`](../geo-map-analysis-plan.md) §Phase 0–3
· **Reviewed:** 2026-07-05 · **Status:** ✅ shipped (mock-first, like Link Analysis MVP)

## Phase 3b addendum (tools, annotations, layers)

- **Tools menu** (wrench toggle): *Measure* (click a path, running great-circle total labels the
  last vertex) · *Radius* (click a center, drawn circle + "N within X km" label, hits feed the
  nearby list) · *Polygon* (click corners, close → filters the displayed subset via
  `pointInPolygon`) · *Note* (type text, click to pin — amber-accented labels).
- **Notes persist in the saved view** (`GeoMapView.notes`); `clearInvestigation` drops tool
  drawings but keeps annotations. Standalone bookmarks folded into saved views (documented
  decision — a view already captures camera/display/notes).
- **Layer manager** (stack toggle): place labels · country boundaries · point labels, plus a
  custom **GeoJSON overlay upload** (validated, feature count toasted, removable).
- **Parallel-route separation**: corridors sharing an endpoint pair bow apart (sin-weighted
  perpendicular offset), keeping both clickable.
- **Seam kept lean**: the map host only gained `mapClick` + a generic `overlay` FeatureCollection
  input (fill/line/point/label layers); all tool geometry is pure and studio-composed.
- **Verification**: build + `test:ci` green (see gate totals in the commit); tool flows spec'd
  (measure sum, radius hits, polygon filter, note persistence, tool-active click guard). Live
  (CS4): measure Dhaka→Comilla = 87.4 km, radius 2 km → 107 depot pings, polygon box 576→114
  points, note pinned + overlay rendered, place-label toggle applied — zero console errors.

## Case-study pack addendum (CS1–CS5)

Five boundary-pushing seeded scenarios — catalog, per-case drive instructions, and pinned spec
invariants in [`../geo-map-case-studies.md`](../geo-map-case-studies.md)
(`geo-case-studies.spec.ts`). Live-verified 2026-07-05: CS1 truncates at exactly 5,000 points with
25 skipped and both kinds; CS2's search finds ACC-007's 3 logins (the only >1,800 km/h account);
CS3 folds 901 legs into 24 corridors (weight 3–150, 4 channels, 5 skipped); CS4 detects 20 dwells
including exactly the 2 staged roadside stops and depots for all 6 trucks; CS5 opens as heatmap
with its saved camera and the staged pairs (B01↔B07×4, B03↔B11×3) top the co-location list.
GAUNTLET green (841/0/5); the new spec file runs in ~66 ms despite the 5.6k-row projection.

## Phase 3a addendum (geo intelligence)

- **Toolbox** (beaker toggle): Co-location (radius + time window) · Frequent locations (radius) ·
  Stay points (radius + min dwell) — pure functions in `geo-analysis.ts`, results are clickable
  (emphasis + fly-to). Hint shown when entity/time columns aren't mapped.
- **Graph bridge**: "View as graph" opens the co-location pairs as an Entity/Link graph through
  the **shared `GraphViewComponent`** (same renderer as Link Analysis; weighted `co-located`
  edges). Full navigation into `/studio/link-analysis` deferred — its sources are dataset-backed
  (V1 multi-mapping line).
- **Playback**: play/pause on the timeline sweeps the time-window end across the extent (~30
  steps at 400 ms); stops at the end.
- **Seeds**: the Dhaka example now contains a 2-hour dwell and a repeated two-device meeting, so
  all three tools produce results at default parameters.
- **Verification**: GAUNTLET green first-run — build PASS, `test:ci` **835 passed / 0 failed**
  (5 net new). Live: co-location returns "IMEI-4411 ↔ IMEI-9902 · met 4×", the bridge dialog
  mounts the G6 host with a painted canvas, playback starts/pauses.

## Phase 2 addendum (routes & time — completes the MUST scope)

- **`od-routes` GeoSource**: origin/destination column mapping → endpoint points + routes
  deduplicated per (origin, destination, kind) with summed weight; broken legs skip + count.
- **Route rendering**: great-circle arcs (slerp) with along-line `>` direction glyphs (bundled
  0-255 glyph range — no sprite needed), kind-coloured, weight → line width, click → detail with
  great-circle distance + movement count.
- **Heatmap mode**: MapLibre native heatmap over an unclustered twin source; toolbar toggle;
  persisted with saved views.
- **Time**: `timeExtent` drives a MatSlider range (start/end thumbs) filtering points AND routes;
  untimed elements always survive.
- **Region filter**: "filter to view" captures the viewport bbox (`withinBBox`).
- **Saved views** now capture camera (center/zoom) + display mode; loading restores both.
- **Seeds**: `money_moves` O/D sample (repeat legs fold to weight, one broken leg) +
  "Example — Remittance corridors".
- **Verification**: GAUNTLET green — build PASS, `test:ci` **830 passed / 0 failed / 5 skipped**
  (11 new tests; the projection spec caught a real slerp-epsilon bug in `greatCircleArc`). Live:
  corridors view loads via the saved-views menu (summary `money_moves: from_lat/from_lon →
  to_lat/to_lon`), time slider renders with labelled thumbs, skipped-leg banner shows, and pixel
  sampling confirms both display modes paint (markers/routes 600 saturated px → heatmap 1208).
- **Flake note**: one unrelated first-run failure (`dashboard.kind.spec.ts`, kind-registry
  isolation — second manifestation of the tracked `task_a7ab593f` flake class); green on re-run.

## What shipped

| Area | Delivered |
|---|---|
| Offline basemap | MapLibre GL + bundled Natural Earth 1:50m GeoJSON + Noto glyphs (`assets/basemap/`, 2.7 MB, zero network); `pmtiles://` protocol pre-registered for customer archives (Phase 4); regeneration via `inspecto-ui/tools/fetch-basemap.mjs` |
| Map host | `inspecto/geo/MapViewComponent` — pan/zoom, fullscreen, scale bar, attribution, light/dark via `theme/map-tokens.ts`, clustering (count badges, click-to-expand), per-kind swatch colours, zoom-in labels, search emphasis (dim others), `flyTo`, PNG export. Live reference card in `/design` |
| GeoSource seam | `inspecto/geo/geo-source.ts` (types-only, mirrors GraphSource); `DatasetGeoSource` projects a Dataset's lat/lon/entity/kind/time columns client-side (`geo-projection.ts`), 5k-point cap + skipped-invalid counting (null/''-coordinate rows stay off the map — no "null island") |
| Pure analysis lib | `inspecto/geo/geo-analysis.ts` — haversine, WGS84 validation, bbox/nearby, search, kind/time filters, grid density, DBSCAN-style clustering; spec'd from day one |
| Studio | Query builder with column auto-guessing, collapses to a summary after run; truncation + skipped-rows banners; search + type filter; point detail sheet (attrs + 3 nearest with distances) via the shared `ElementDetailDialog`; Data panel (shared `inspecto-data-table` standard tier, row click → flyTo + detail); save/load `geo-map-view` (ask-the-minimum + duplicate-name inline block); PNG/GeoJSON export (CSV via the table) |
| Shared lib | `inspecto/investigation/` — `SavedViewStore<TView>` + `ElementDetailDialog` + `uniqueNameValidator`, extracted from Link Analysis behavior-preservingly (both studios now delegate) |
| Seeds | `cell_sites` sample source + dataset + "Example — Dhaka cell network" saved Geo View (21 valid points, 2 deliberately broken rows exercising the skip banner) |

## Verification evidence

- **GAUNTLET:** `lint:tokens` green (map-tokens allowlisted); production build green (initial ~444 kB
  transfer, no budget errors); `test:ci` **824 passed / 0 failed / 5 skipped** (23 new tests this phase).
- **Live (preview, mock mode):** seeded view loads → 21 points / 2 skipped / kinds `device|tower`;
  search `IMEI-9902` → 3 hits, table narrows; type filter `tower` → 12; skipped-rows banner renders;
  data panel renders; zero console errors.
- **Offline proof:** basemap + glyphs render from bundled assets with zero external requests
  (land/water pixels match the exact `map-tokens` colours).
- **Responsive:** no horizontal overflow at 375 px and 768 px with the query form and data panel open
  (data card = `min-w-0 overflow-x-auto`).
- **A11y:** axe specs on the studio (empty state) and map host; `sr-only` `role="status"` announces
  point counts; `aria-label` on every icon-only control; keyboard operability lives in the
  panels/table (WebGL canvas itself is not keyboard-operable — same posture as the G6 hosts).
- **Spec catch worth noting:** the projection spec caught the `Number(null) === 0` null-island bug
  before it shipped.

## Known limitations (deliberate MVP cuts — plan §Phase 2+)

- Routes/O-D rendering, heatmap/density layer, time filter + timeline slider → **Phase 2**.
- Measure/draw tools, stay-points, co-location, playback, layer manager → **Phase 3**.
- Backend DuckDB-spatial projection, geo Widget, tile-server config, geocoding → **Phase 4**.
- Saved views don't capture camera position yet (query + mapping only).
- Basemap labels cover Latin/Cyrillic/Greek glyph ranges; other scripts need more ranges copied
  into `assets/basemap/fonts/`.
- Headless-testing gotcha (documented in the plan): MapLibre needs `requestAnimationFrame`; hidden
  pages must shim it — jsdom specs run against the WebGL-guarded unmounted path.

## Environment note for future verifiers

The preview browser is a hidden page: RAF never fires, so the map (and mat-menu open animations)
stall there. Verify map pixels via the RAF-shim trick (see plan §Phase 0 actuals) or a visible
browser; drive component state via `window.ng.getComponent` when menus won't open.
