# Widget Library — Design & Phasing Spec

> **Status:** Draft for review, 2026-07-01 (revised: two-milestone structure). **Refines** (does not replace)
> `report-builder-design.md` (§5 VizPlugin, §8 Show‑Me, §9 Dashboard), `studio-implementation-plan.md`
> (Deliverable 3), and `component-model.md`. This doc adds the *widget‑library‑specific* concerns those docs
> left thin or deferred: widget identity/tags, a browsable library, the mandatory‑vs‑advanced config split, a
> shared dataset‑result layer, and a standalone render host — plus an explicit **boundary** analysis.
>
> **Milestones:** **M1 — Full UI, mock backend** (active target) delivers the entire experience end‑to‑end
> against the offline/mock data path — everything that does not require the closed backend storage enum or a
> real query endpoint. **M2 — Backend** is **BACKLOG** (not started; revisit later) and covers only what
> structurally requires server‑side work: real persistence + a `QuerySpec → DuckDB` endpoint + RBAC.

## 0. TL;DR — this is mostly a *delta*, not a greenfield build

The Studio already implements the core of the requested flow (offline, mock‑backed). The valuable work is
five concrete deltas + a boundary. Do **not** re‑spec what exists.

| Your ask | Today | Delta to build |
|---|---|---|
| 1. Select measures/dimensions to build a widget | ✅ Explore field‑mapper (`ExploreControlsComponent`) | — |
| 2. Choose a relevant viz component | ✅ Show‑Me recommender + Viz Type picker (`show-me.ts`) | — |
| 3. Map fields to mandatory channels; **advanced behind a cog** | ✅ mandatory mapping · ❌ no advanced panel | **D3: WidgetOptions + cog panel** |
| 4. Save as a named config w/ id, type, **tags**, dataset ref; **shared dataset, fewer backend calls, single truth** | ✅ save `{id,vizType,datasetRef,mapping}` via `/components/chart` · ✅ dataset referenced by id (definitional single‑source already holds) · ❌ no tags · ❌ no shared *runtime* result cache | **D1: identity+tags** · **D2: shared result layer** |
| 5. (a) viz template library, (b) config+preview/test, (c) widget library → dashboard/standalone render | ✅ (a) `VizPlugin` registry · ✅ (b) Explore live preview · ✅ dashboard grid · ❌ no browsable widget gallery · ❌ no standalone render | **D4: library gallery** · **D5: standalone WidgetHost** |

## 1. Vocabulary (glossary‑bound — see `docs/GLOSSARY.md`)

- **Visualization Type** — the reusable *template* (bar/line/table/kpi/map…). It is a **Component Type** that
  declares a config schema. In code this is a `VizPlugin` (`inspecto/viz/viz-types.ts`). ⛔ Never call the
  template a "Widget".
- **Widget** — a **Visualization Type + Config + a binding to a Dataset's resultset** = the configured,
  renderable *instance*. This is the Type→Instance pattern made concrete.
- **Dataset** — any queryable relation the widget binds to (Table | Derived Table | View), referenced **by id**.
- **Measure** — a BI aggregation (SUM/AVG/COUNT…) over a Dataset. **Dimension** — a grouping axis.
  **Temporal** — a time‑valued dimension role.
- **Dashboard** — a grid layout of Widgets (tiles).

> **⚠ Naming decision — RESOLVED.** Current code persists the instance as kind `chart` (`ChartsService`,
> `/components/chart`, `chart.kind.ts`). The glossary‑canonical instance term is **Widget**; "chart" is only
> *one family* of Visualization Type. **Decision: rename `chart → widget` as part of M1** (mock‑only, low blast
> radius, UI→model layers only — the backend enum rename rides along with M2's widening, not before). The
> existing Studio "Charts" section becomes the renamed **Widget Library** (§5), not a new nav item.

## 2. The three layers (your point 5, in glossary terms)

```
(1) Visualization Type library     (2) Authoring + preview            (3) Widget Library
    = VizPlugin registry               = Explore workbench                = saved Widget instances
    inspecto/viz/plugins/*             select measures/dims →             browse · search · filter‑by‑tag ·
    add a type = add a plugin,         Show‑Me ranks types →              preview · reuse
    zero per‑widget code               pick type → map channels
                                       (mandatory) → cog (advanced) →         ├── place on Dashboard (tile)
                                       live preview → Save Widget            └── render Standalone (WidgetHost)
                                                                          both read data via ▼
                          ┌───────────────────────────────────────────────────────────────┐
                          │  Shared Dataset layer — one Dataset config, one fetch, N widgets │
                          │  QuerySpec → (offline AlaSQL now | DuckDB endpoint later)         │
                          └───────────────────────────────────────────────────────────────┘
```

## 3. Data model (extends the real current types)

A Widget is a saved component; its `config` body is `WidgetConfig`; identity lives on the component.

```ts
// Extends today's chart config (datasetId, vizType, controls) — adds tags/description + a typed options split.
export interface WidgetConfig {
  vizType: string;          // Visualization Type id = VizPlugin.meta.type  (bar|line|area|pie|kpi|table|…)
  datasetRef: string;       // id of a saved Dataset component — a REFERENCE, never an embedded copy
  mapping: ControlValues;   // channel → field(+agg): the MANDATORY portion (from ControlSpec[])
  options?: WidgetOptions;  // the ADVANCED/cog portion — all optional, render applies sane defaults
}

export interface Widget extends WidgetConfig {
  id: string;
  name: string;
  tags?: string[];          // NEW — library browse/filter
  description?: string;     // NEW — library card subtitle
}
```

**Mandatory vs advanced split (your point 3).** The mandatory surface is exactly the plugin's `ControlSpec[]`
(required channels, role‑gated field pickers, agg picker for measure channels) — already generated by the
Explore field‑mapper. Everything else is optional and lives behind the cog:

```ts
export interface WidgetOptions {
  title?: string;                 // caption override (defaults to Widget.name)
  subtitle?: string;
  axis?:   { xTitle?: string; yTitle?: string; yFormat?: string };   // yFormat = a DatasetColumn.format key
  legend?: { show?: boolean; position?: 'top' | 'right' | 'bottom' | 'left' };
  colors?: string[];              // MUST resolve from chart-tokens.ts palette (canvas-color lint guard)
  sort?:   { channel: ChannelId; dir: SortDir };
  limit?:  number;                // top-N
  stacked?: boolean;              // bar/area
  dataLabels?: boolean;
}
```

Design rule to keep usability high (your explicit constraint — *not too much flexibility*): **`WidgetConfig`
never carries free‑form styling.** Options are a closed, curated set; colors come only from the token palette.
Anything not in `WidgetOptions` is a plugin default, not a user knob.

**Dataset is unchanged** (`DatasetConfig` in `datasets/dataset-types.ts`). Because a Widget references a Dataset
by id, the *definitional* single‑source‑of‑truth you asked for already holds today. The *runtime* single‑fetch
(fewer backend calls) is D2 below.

## 4. Shared Dataset result layer (your point 4: "fewer REST calls, single truth") — D2

Two distinct senses of "single source of truth":

- **(a) Definitional** — many Widgets reference *one* Dataset config. ✅ Already true (`datasetRef` is an id).
- **(b) Runtime** — many Widgets on a page compute/fetch *once*, not N times. ❌ Missing: today every chart/tile
  independently compiles its `QuerySpec` and runs it (`explore.component.ts run()`, `dashboard-tile.component.ts`
  effect), even when two tiles share the same dataset + filter and would produce an identical `QuerySpec`.

**M1 scope (mock/offline):** there is no REST call to dedupe yet, but the redundant **compute** (re‑running
AlaSQL for an identical `QuerySpec` in two tiles) is real and worth removing now — it's the same seam that
becomes a network dedupe once M2 lands a real endpoint, so building it against the mock path de‑risks M2 rather
than waiting for it.

```ts
// Keyed by a stable hash of the effective QuerySpec (incl. dashboard cross-filter).
// M1: dedupes/caches in-memory AlaSQL runs. M2 (backlog): same interface, swaps runSpec() for a real HTTP call —
// callers (WidgetHost, dashboard tiles) don't change.
class DatasetResultService {
  result(spec: QuerySpec): Signal<ResultState>;   // widgets subscribe; identical spec ⇒ one compute/fetch, shared
}
```

Note: caching keys on the **QuerySpec** (group‑by + measures + filters), not on the Dataset alone — two widgets
over the same Dataset with different aggregations are different entries; two tiles sharing dataset+filter share one.

## 5. Widget Library UI (your point 5c) — D4 + D5

- **D4 — Library gallery.** Upgrade the existing Studio **Charts** section in place (rename to **Widgets**,
  §1) into a gallery: cards with a thumbnail preview, name, Visualization Type icon, tags. Search by name;
  filter by tag/type. Actions: *Open in Explore* (edit), *Add to Dashboard*, *Open standalone*, *Duplicate*,
  *Delete*. Reads from `ComponentsService.list('widget')`. **Thumbnails: live‑render a small `WidgetHost`
  instance per card** (decision — reuses D5, no separate snapshot pipeline; revisit only if gallery‑scale
  perf becomes an issue).
- **D5 — Standalone render.** Extract the render half of `DashboardTileComponent` into a reusable
  `WidgetHost` (input: widget id or config) that resolves the `VizPlugin`, builds the `QuerySpec`, fetches via
  the shared layer, and renders. Powers both a standalone route (`/studio/widgets/:id`) and each dashboard tile
  — one render path, no duplication.

## 6. Boundary — what M1 (UI, mock backend) can and cannot offer

The line is **not** "simple vs. advanced features" — nearly all requested depth is UI‑buildable against the
mock/offline path. The line is **structural**: does it require the closed backend enum, a real query endpoint,
or a different module (auth)? Only those fall outside M1.

**M1 can offer (all UI, mock/offline — no backend change needed):**
- Single‑Dataset widgets; client‑side aggregation over seed/mock data via AlaSQL.
- The shipped Visualization Types (kpi, table, bar, line, area, pie, scatter) **plus growing the set**
  (bubble, map, heatmap, pivot, gauge, funnel) — adding a type is always just a new `VizPlugin`, never a
  backend change.
- Show‑Me recommendation made **type‑ and cardinality‑aware** (D6, §9) — a client‑side scoring upgrade.
- Live preview, save + tag, browse/reuse (D4 gallery), place on a dashboard, cross‑filter, standalone render (D5).
- Mandatory field mapping + a curated advanced‑options panel (D3).
- Client‑side compute dedupe (D2 in its M1 form, §4).
- **Calculated‑measure authoring UI** — the `NamedMeasure` expression editor runs through the same offline
  AlaSQL path; no backend needed to author or preview it.
- **Drill‑down / parameterized dashboards** — simulated against mock data; the interaction pattern doesn't
  need a real endpoint to build or demo.
- **Export (PNG/CSV)** — client‑side (canvas snapshot for PNG, in‑memory rows for CSV); no backend involved.

**Outside M1 — structurally requires M2 (backend) or another module:**
- **Scale / real data.** Everything runs **in‑browser on mock `SAMPLE_SOURCES`** until the `QuerySpec →
  DuckDB` endpoint exists. High‑cardinality or large datasets will not perform client‑side. *(Hard limit.)*
- **Cross‑dataset joins / blending.** A widget binds exactly one Dataset; no join/blend UI planned either milestone.
- **Real‑time / streaming widgets** — out of scope, either milestone.
- **Real persistence of new kinds.** The backend storage type set is **CLOSED**
  (`ComponentStore.WRITABLE_TYPES` / `ComponentRegistry.TYPE_BY_DIR` = grammar/schema/transform/sink). `dataset`,
  `widget`, `dashboard` stay **mock‑served only** until M2 widens those two constants + a registry dir.
- **Per‑user sharing / RBAC / private‑vs‑public widgets.** Core is **auth‑free**; this needs the separate
  `inspecto-security` module, not just the M2 query backend — flagged here, tracked, but not M2‑owned scope.

## 7. Phasing — Milestone 1 (active) / Milestone 2 (backlog)

### Milestone 1 — Full UI, mock backend (active target)

Everything UI‑buildable against the existing offline/mock path. Sequenced as five sub‑phases so it still
ships incrementally; each is independently verifiable.

**M1.1 — Foundation & rename**
- Rename kind `chart → widget` across the UI/model layer (`ChartsService`→`WidgetsService`, `chart.kind.ts`→
  `widget.kind.ts`, mock routes, component refs). Backend enum keeps `chart` as the on‑disk dir until M2.
- **D1** identity + `tags`/`description` on the saved Widget; standard
  `{id, vizType, datasetRef, mapping, options}` shape.
- *Verify:* existing Explore/Dashboard flows still work end‑to‑end post‑rename; `lint`/`test:ci` green.

**M1.2 — Authoring depth**
- **D3** `WidgetOptions` + the advanced **cog panel** (title/subtitle, axis titles + format, legend, sort,
  limit, stacked, dataLabels, palette‑bound colors). Mandatory mapping stays inline, unchanged.
- *Verify:* create a widget, set advanced options, confirm defaults apply when options are omitted.

**M1.3 — Library & reuse**
- **D4** rename Studio "Charts" → **Widgets**; upgrade its list into a searchable/taggable gallery with
  live‑render thumbnails.
- **D5** `WidgetHost` standalone render + `/studio/widgets/:id` route; dashboard tile refactored to reuse it
  (one render path, not two).
- *Verify:* save with tags → find by tag in the gallery → open standalone → add the same widget to a dashboard.

**M1.4 — Runtime efficiency**
- **D2** `DatasetResultService`, M1 form: spec‑hash keyed dedupe/cache over the in‑memory AlaSQL run (§4).
  Interface is stable so M2 can swap the implementation without touching callers.
- *Verify:* two dashboard tiles sharing dataset+filter trigger one AlaSQL run, not two (unit test on the service).

**M1.5 — Taxonomy & type breadth**
- **D6** extend `VizFit` with field **type** (categorical/ordinal/temporal/geo/hierarchical) + **cardinality
  band**; smarter Show‑Me ranking (e.g. "categorical ≤20 + 1 measure → pie; >100 → table").
- Grow the Visualization Type set: bubble, map, heatmap, pivot, gauge, funnel.
- Calculated‑measure authoring UI (`NamedMeasure` expression editor, offline‑evaluated).
- Drill‑down / parameterized dashboards (mock‑data simulation).
- Export (PNG/CSV), client‑side.
- *Verify:* each new type appears correctly in Show‑Me for a matching mock field shape; export produces a
  valid file from a live widget.

### Milestone 2 — Backend (BACKLOG — not started, revisit later)

Captured for scope, not detailed further until M1 ships and this is picked up:
- Widen the closed `ComponentStore.WRITABLE_TYPES` / `ComponentRegistry.TYPE_BY_DIR` (+ registry dir) so
  `dataset` / `widget` / `dashboard` persist for real, replacing the mock interceptor.
- `QuerySpec → DuckDB` exec endpoint, swapping the offline `runSpec` seam.
- `DatasetResultService` M2 form: same interface as M1.4, dedupe/cache now also cuts real network calls.
- Materialized‑dataset refresh / scheduled delivery (adoption‑plan P4 territory).
- *(Tracked, not owned by M2):* sharing/RBAC once the separate `inspecto-security` module exists.

## 8. Decisions made while scoping M1 (flag if any should be revisited)

- **Rename `chart → widget`:** done as part of **M1.1** (§1). Backend on‑disk naming unchanged until M2.
- **Library placement:** the existing Studio "Charts" section **is** renamed/upgraded into the Widget Library
  (M1.3) — no new top‑level nav item.
- **Preview thumbnails:** live‑render a `WidgetHost` per card (M1.3) rather than a separate snapshot pipeline.
- **`WidgetOptions` field set:** the §3 list stands as the M1 baseline (title/subtitle/axis/legend/colors/
  sort/limit/stacked/dataLabels) — closed set, not user‑extensible, per the "not too much flexibility" constraint.

## 9. Data‑shape taxonomy (companion note)

The chart‑selection intuition (m×d shapes → candidate types) feeds Show‑Me/D6 in **M1.5**: current `VizFit`
is **count‑only** (min/max dim & measure + temporal flag); making it **type‑ & cardinality‑aware** turns
"which types *fit*" into "which type is *best*" — still entirely a client‑side scoring change.
