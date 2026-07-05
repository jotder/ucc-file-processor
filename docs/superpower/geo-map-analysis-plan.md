# Geo Map Analysis — Revised MoSCoW & Phased Plan

> On approval, persist this plan in-repo as `docs/superpower/geo-map-analysis-plan.md` (per the
> "working artifacts stay in the repo" rule) — that is implementation step 0.

## Context

We shipped the **Link Analysis studio** (`studio/link-analysis`) with a clean, compositional
architecture: a pluggable `GraphSource` seam, a pure framework-free `graph-analysis.ts` library, the
`GraphViewComponent` G6 host, the tiered `inspecto-data-table`, and saved views persisted as
Components (kind `link-analysis-view`, mock-served). The user wants a sibling **Geo Map Analysis**
studio that answers *where* (locations, movement, spatial relationships over time) the way Link
Analysis answers *who-connects-to-whom* — and wants the common machinery factored into shared
TypeScript libraries rather than copied.

**Ground truth from exploration:** the app has zero geo capability today (no map library, no spatial
SQL, no lat/lon column types). The product is offline/air-gapped-oriented.

**Decisions locked with user (2026-07-05):**
- **D1 Map engine:** MapLibre GL JS + PMTiles — fully offline, basemap bundled in the artifact.
- **D2 Tiles:** fully bundled offline (Protomaps/Natural Earth world extract z0–6 in `assets/`,
  plus glyphs/sprite). No network dependency; consequence: no satellite/terrain imagery in-bundle.
- **D3 Delivery:** mock-first UI, exactly like Link Analysis MVP (client-side dataset rows, saved
  views as mock-served Component kind `geo-map-view`; backend/DuckDB-spatial in a later phase).
- **D4 Geocoding:** demoted out of MVP. MVP requires lat/lon columns (fits core use cases: CDRs,
  IP-geo, GPS logs, tower dumps). Geocoding returns later as a pluggable seam with an offline
  lookup-table option.

Canonical vocabulary applies throughout (`docs/GLOSSARY.md`): **Dataset**, **Component**, **Widget**,
**Pipeline** — new terms introduced here: **GeoSource**, **GeoQuery**, **Geo View** (saved), and
**Layer**; these must be added to the glossary in Phase 1.

---

## Reuse inventory — what we share

### Shared AS-IS (no modification)
| Asset | Path | Use in Geo |
|---|---|---|
| Design system (empty-state, skeleton, alert, badges) | `inspecto-ui/src/app/inspecto/` | identical states/banners |
| `ICON_COLOR_SWATCHES` / chart tokens | `inspecto/theme/chart-tokens` | marker/route coloring (no-hardcoded-color rule) |
| `DataTableComponent` tier=standard | `app/inspecto/data-table/` | "Data" tab (points/routes as rows, `rowClick` → focus map) |
| Query lib (`query-types`, `query-eval`, `QueryConditionGroupComponent`) | `app/inspecto/query/` | attribute filters in GeoQuery builder |
| `DatasetsService` + `datasetRows`/`evaluateRows` sample pipeline | `app/inspecto/api/`, studio datasets | GeoSource data feed (mirrors entity-projection) |
| `ComponentsService` CRUD | `app/inspecto/api/components.service.ts` | persist `geo-map-view` |
| `uniqueNameValidator`, ask-the-minimum save form | angular-ui SKILL §Forms | save-view dialog |
| Signals + OnPush patterns | Link Analysis component | copy the approach |
| `graph-analysis.ts` pure functions (communities, connected components, search) | `app/inspecto/graph/graph-analysis.ts` | Phase 3 co-location graphs & movement clustering |

### Shared WITH modification — extract a common "investigation shell" lib
New shared lib `app/inspecto/investigation/` (barrel `index.ts`), extracted **from** Link Analysis in
Phase 0 with zero behavior change (Link Analysis refactored onto it, its specs stay green):

1. **Saved-view helper** — generalize `LinkAnalysisService`'s list/save/remove-as-Component pattern
   into `SavedViewStore<TView>` (component kind + config shape are type params). Both studios' thin
   services delegate to it.
2. **Element-detail sheet** — generalize `element-detail.dialog.ts` into a key/value detail dialog
   taking `{title, kindBadge, entries[]}`; Link Analysis nodes/edges and Geo markers/routes both feed it.
3. **Investigation search model** — extract the search-box + highlight/emphasis + results-list wiring
   (`GraphEmphasis`-style `{focusIds, dimOthers}` selection model, generic over element ids).
4. **Toolbox panel shell** — the accordion/toolbox chrome (header, collapse, disabled+tooltip gating)
   used by Layout/Algorithm toolboxes, without the graph-specific contents.
5. **Results side panel** — the selected-elements / analysis-results panel pattern with
   click-to-focus callback.

Do **not** over-abstract the source seam: `GeoSource` mirrors the `GraphSource` interface shape
(`{id, label, query(q): Promise<GeoData>}`) rather than forcing a generic `AnalysisSource<TQuery,TData>`
— two similar interfaces beat one premature abstraction (CLAUDE.md simplicity rule).

### New geo-specific (no counterpart to reuse)
- `app/inspecto/geo/` — pure framework-free lib (mirror of `inspecto/graph/`):
  - `geo-types.ts` — `GeoData {points: GeoPoint[], routes: GeoRoute[]}`, `GeoPoint {id, lat, lon,
    kind, label, time?, attrs}`, `GeoRoute {id, from, to | path[], kind, weight?, time?}`, `GeoQuery`.
  - `geo-analysis.ts` — haversine distance, WGS84 coordinate validation, bbox/radius/polygon
    predicates, grid & hex binning (density), DBSCAN-style location clustering, bearing; Phase 3
    adds stay-points, co-location, frequent-locations. Caps mirror `ANALYSIS_NODE_CAP`.
  - `geo-analysis.spec.ts` from day one.
- `MapViewComponent` — MapLibre host (analog of `GraphViewComponent`): inputs `data`, `emphasis`,
  `display`, `layers`; outputs `pointClick`, `routeClick`, `areaSelect`. Owns clustering, tooltips,
  light/dark style switch, fit-to-data, PNG export.
- `studio/geo-map/` feature module: `GeoMapComponent` (studio shell), `geo-sources.ts`
  (`DatasetGeoSource` — the entity-projection analog: dataset + lat/lon cols + entity col + optional
  kind/time cols; `RouteProjectionGeoSource` — origin/destination lat/lon pairs → routes),
  `geo-map.service.ts` (kind `geo-map-view`).
- Offline basemap assets: PMTiles world extract + glyphs + sprite under `inspecto-ui/src/assets/basemap/`;
  `pmtiles` npm package wired as MapLibre protocol.

---

## Revised MoSCoW (Inspecto context)

Key deltas from the generic list, with rationale:
- **Satellite/terrain views: MUST → COULD** — impossible fully-offline (D2); available only when a
  customer supplies their own tile server (config seam ships in Phase 4).
- **Geocoding: MUST → SHOULD (Phase 4+)** — per D4.
- **Mini-map, compass: MUST → COULD** — low investigation value; scale bar + fit-to-data suffice.
- **Event playback/animation: MUST → split** — time filter + timeline slider are MUST (Phase 2);
  animated playback is SHOULD (Phase 3), matching Link Analysis which shipped without playback.
- **Dashboard Widget binding: MUST → Phase 4** — Link Analysis itself deferred widget binding; the
  two studios should get widget-ification **together** via one shared `VizPlugin` render-kind
  (`render: 'component'`) so we build the binding seam once.
- **SVG export: dropped** — MapLibre is WebGL/canvas; PNG + GeoJSON + CSV cover the need.
- **Route optimization / isochrones / road network: SHOULD → COULD/WON'T** — require routing engines
  (OSRM/Valhalla) that can't ship in an offline bundle cheaply; straight-line/great-circle analysis
  covers investigation use cases (impossible travel, co-location).
- **Collaboration (comments/versions/permissions): → WON'T (this release)** — the platform has no
  auth/security module yet (known constraint); reproducibility comes free via saved GeoQuery views.
- **Cross-cutting RBAC/audit/dataset permissions: → platform backlog**, not this module.

### MUST (Phases 0–2)
Data & projection: Dataset GeoSource; lat/lon column mapping; entity & kind mapping; time field
mapping; route (origin→destination) mapping; WGS84 validation; preview mapped points; projection
error/truncation banners (cap like `PROJECTION_NODE_CAP`).
Map: MapLibre interactive map (pan/zoom/fit/fullscreen); bundled offline basemap; light/dark styles;
scale bar.
Points: markers, kind→icon/color, clustering, size/color by attribute, labels, tooltips,
selection + multi-select, highlight.
Routes: lines between points, direction arrows, kind coloring, weight thickness, curved parallel
routes, route highlight.
Investigation: click point/route → detail sheet; radius & bbox search; nearby points; heatmap/density
layer; search (entity + attribute); filters (kind, time, attribute, region); results side panel with
click-to-focus; time filter + timeline slider.
Persistence/export: save/load `geo-map-view` (source + query + display + camera); PNG, GeoJSON, CSV;
Data tab via shared table.

### SHOULD (Phase 3)
Distance & area measurement; draw circle/polygon selection; polygon filtering; stay-point detection;
frequent locations; movement clustering; co-location detection (shared time+place); travel pattern
summary (feeds Link Analysis via co-location graph → `G6GraphData`!); animated playback with speed
control; layer manager (bundled admin-boundaries GeoJSON, custom GeoJSON overlay upload); bookmarks
& annotations; convex hull / buffer; hotspot (grid/KDE-lite).

### COULD (Phase 4+)
DuckDB spatial backend (server-side projection/aggregation for large datasets); geo Widget on
dashboards (shared VizPlugin seam with Link Analysis); customer tile-server config (unlocks
satellite); pluggable geocoding (offline lookup table / Nominatim URL); origin-destination matrix &
flow maps; hex-bin analytics; impossible-travel & tower-hopping detection; geo alerts/geo-fencing
(needs Alert Rule integration); vector-tile data layers for millions of points; AI assist
(explain movement, NL geo search).

### WON'T (this release)
Everything in the generic WON'T list, plus: online-only imagery as a requirement, real-time GPS
streaming, routing-engine features (turn-by-turn, isochrones), collaboration/RBAC (blocked on the
unbuilt security module), 3D terrain.

---

## Phases

### Phase 0 — Foundation & extraction (no user-visible geo yet)
1. **Spike/verify offline basemap**: add `maplibre-gl` + `pmtiles` deps; produce the z0–6 world
   PMTiles extract + glyphs/sprite into `assets/basemap/`; prove it renders with zero network
   (devtools offline). Budget check: target ≤ 30 MB added to the bundle; record actual in the plan doc.
   > **DONE 2026-07-05 — actuals.** A PMTiles planet extract at z0–6 would have been ~100+ MB, so the
   > bundled basemap is instead **slimmed Natural Earth 1:50m GeoJSON** (land/boundaries/lakes/places,
   > properties stripped, coords rounded to 4 dp) + 5 Noto Sans glyph ranges (Latin/Cyrillic/Greek):
   > **`assets/basemap/` = 2.7 MB** (maplibre-gl adds ~1 MB raw / ~250 KB gz JS). The `pmtiles://`
   > protocol is registered in `MapViewComponent`, so a customer vector-tile archive drops in later
   > (Phase 4) without consumer changes. Regeneration: `inspecto-ui/tools/fetch-basemap.mjs`.
   > Render proof: land/water painted with the exact `map-tokens` colours + glyphs fetched from
   > bundled assets, zero external requests. **Headless gotcha:** MapLibre's whole pipeline runs on
   > `requestAnimationFrame`; in a hidden/headless page RAF never fires and the map stalls silently
   > (no events, no fetches) — shim RAF with `setTimeout` when driving it in such an environment.
   > Inline style objects also need **absolute** resource URLs (`basemapStyle()` derives them from
   > `document.baseURI`).
2. **Extract `app/inspecto/investigation/` shared lib** from Link Analysis (saved-view store,
   detail sheet, search/emphasis model, toolbox shell, results panel). Refactor Link Analysis onto
   it. **Gate: all existing Link Analysis specs green, GAUNTLET green — zero behavior change.**
   > **Scope note (2026-07-05):** Phase 0 extracts the two pieces that are already generic —
   > `savedViewStore<TView>` + the element-detail dialog. The toolbox shell, results panel, and
   > search wiring are inline in the Link Analysis component; they get extracted **in Phase 1 with
   > the geo studio as the concrete second consumer** (extract-on-second-use, not speculatively).
3. Map-style carve-out for the no-hardcoded-color CI guard (style JSON contains colors by nature —
   same exemption pattern as chart tokens).
4. Glossary: add GeoSource / GeoQuery / Geo View / Layer to `docs/GLOSSARY.md`.

### Phase 1 — MVP studio (demo-able)
> **DONE 2026-07-05** — review sheet: [`reviews/geo-map-studio.md`](reviews/geo-map-studio.md).
> Deviations from the sketch: saved views don't capture camera yet; the Link-Analysis toolbox-shell/
> results-panel extraction was partially superseded (search/filter/data-panel were rebuilt lean in the
> geo studio; `uniqueNameValidator` joined the investigation lib; the accordion shell extraction is
> deferred until a third consumer needs it). The seeded example is `cell_sites` → "Example — Dhaka
> cell network".
Route `studio/geo-map`; `GeoMapComponent` mirroring the Link Analysis shell (source picker → query
form → run → map). `DatasetGeoSource` with lat/lon/entity/kind/time mapping + validation + preview.
`MapViewComponent` with markers, clustering, tooltips, selection, fit-to-data, light/dark, scale bar,
fullscreen. Search + kind/attribute filters (shared query lib). Detail sheet + results side panel
(shared investigation lib). Data tab (shared table, rowClick→flyTo). Save/load `geo-map-view`
(shared SavedViewStore; add kind to `ComponentType` union + mock API). PNG/GeoJSON/CSV export.
A11y per house rules (canvas alt-text pattern from charts, keyboard focus for panels), axe specs,
responsive at 375/768. **Gate: GAUNTLET + live SMOKE, review sheet under `docs/superpower/reviews/`.**

### Phase 2 — Routes & time (completes MUST)
`RouteProjectionGeoSource` (O/D pairs → great-circle routes, arrows, weight, curved parallels).
Radius/bbox nearby search + heatmap/density layer (`geo-analysis` binning). Time filter + timeline
slider filtering points/routes. Region filter. Distance readout in detail sheet.
> **DONE 2026-07-05** — shipped: `od-routes` GeoSource (O/D fold → endpoint points + weighted
> routes, `projectRoutes`), great-circle arcs (`greatCircleArc`, slerp) with along-line `>` arrows,
> MapLibre native heatmap mode (unclustered twin source, toolbar toggle), time filter via a
> MatSlider range over `timeExtent`, region filter = "filter to view" (viewport bbox), route detail
> with great-circle distance + folded movement count, camera + display mode captured in saved
> views. Seeded `money_moves` + "Example — Remittance corridors". Deviations: curved *parallel*
> route separation and radius-circle search UI deferred to Phase 3 (arcs make parallel overlap
> rare; nearby is in the point detail). GAUNTLET 830/0/5; live-verified incl. pixel proof of both
> display modes.

### Phase 3 — Geo intelligence (SHOULD)
Measure/draw tools; polygon selection; stay-points, frequent locations, movement clustering,
co-location detection; **bridge to Link Analysis**: "project co-locations as graph" button emits
`G6GraphData` and opens Link Analysis — the flagship cross-studio story. Animated playback. Layer
manager + bundled boundaries. Bookmarks/annotations (persisted in the view config).
> **Phase 3a DONE 2026-07-05** — shipped: geo-intelligence toolbox (co-location / frequent
> locations / stay points, param-driven, clickable results → emphasis + fly-to; pure fns in
> `geo-analysis.ts`), **co-location → graph bridge** ("View as graph" renders the pair graph
> through the shared `GraphViewComponent` — deviation: inline dialog instead of navigating to
> Link Analysis, whose dataset-backed source model can't take an ad-hoc graph until the V1
> multi-mapping line), and **event playback** (play/pause sweeping the time window). The Dhaka
> seed now demos all three tools at default params. GAUNTLET 835/0/5.
> **Phase 3b remaining:** measure/draw tools + polygon selection, layer manager + bundled
> boundaries, bookmarks/annotations, curved parallel-route separation, radius-circle search UI.

### Phase 4 — Platform & scale (COULD)
Geo Widget + Link-Analysis Widget via one shared VizPlugin component-render seam; dataset binding +
parameters. Backend: DuckDB spatial extension (note: native extension binary must be **bundled** for
offline installs — same class of problem as the DuckDB native-access flag), server-side
projection/aggregation endpoint (ENDPOINT skill, fail-closed gates), widen backend `ComponentStore`
enum (`geo-map-view` + `link-analysis-view` + friends together). Tile-server config seam. Pluggable
geocoding. Performance track (progressive loading, worker-side binning).

---

## Notable risks & constraints
- **Bundle size**: MapLibre (~250 KB gz) + PMTiles extract (10–30 MB). The tile archive should be a
  lazily-fetched asset (not in the JS bundle) so initial load is unaffected; verify `package.ps1`
  bundle copies `assets/basemap/` intact (remember the jlink rough edge).
- **License/attribution**: MapLibre BSD, pmtiles MIT — fine. Basemap data (OSM/Natural Earth)
  requires attribution text on the map — include the attribution control.
- **A11y on a WebGL canvas**: reuse the chart-canvas alt-text approach; keyboard operability lives in
  panels/table, not on-canvas — document in the review sheet.
- **Angular integration**: MapLibre is imperative; wrap it once in `MapViewComponent` with
  signals-in/outputs-out, `NgZone.runOutsideAngular` for map events (same discipline as the G6 host).
- **Mock-first ceiling**: client-side projection caps (~5k points MVP, mirroring the 500-node graph
  cap) — truncation banners, honest about scale until Phase 4 backend.
- **Vocabulary**: "GeoSource"/"GeoQuery" naming deliberately parallels GraphSource/GraphQuery;
  glossary update is binding and in Phase 0.

## Verification
- Each phase ends with **GAUNTLET** (UI lint + vitest + build; backend reactor when touched) and a
  **live SMOKE** of the packaged bundle for phases that add assets (Phase 0 tile bundling, Phase 4
  backend), following build-verify skill.
- Phase 0 extraction gate: Link Analysis spec suite green before/after — the refactor is
  behavior-preserving by test.
- New pure lib `geo-analysis.ts` gets specs from day one (mirror `graph-analysis.spec.ts`).
- Offline proof for the basemap: run the built bundle with network disabled; map must render.
- MVP review sheet under `docs/superpower/reviews/geo-map-studio.md` (house pattern).
