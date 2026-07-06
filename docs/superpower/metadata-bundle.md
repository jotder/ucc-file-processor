# Metadata Bundle — cross-instance export / import

Move **configuration, never data rows** between Inspecto instances (staging → production
promotion). UI: **Settings → Import & Export** (`/settings/transfer`,
`modules/admin/transfer/`). Pure format/closure/planning logic: `transfer/bundle.ts` (spec-pinned
in `bundle.spec.ts` + `transfer.component.spec.ts`).

Distinct from the **Space zip bundle** (Spaces page): that one clones a whole space with its data
sources; a Metadata Bundle is a *selective, artifact-level* transfer of definitions only.

## What it carries

One JSON file (`inspecto-bundle-<space>-<stamp>.json`):

```json
{
  "format": "inspecto-metadata-bundle",
  "version": 1,
  "exportedAt": "…ISO…",
  "sourceSpace": "staging" | null,
  "items": [ { "kind": "…", "id": "…", "content": { …full config map… } } ]
}
```

Kinds (import applies them in this order, referenced kinds first): `connection`, `grammar`,
`schema`, `transform`, `sink`, `dataset` *(metadata: columns/roles/measures/query — no rows)*,
`link-analysis-view`, `geo-map-view`, `widget`, `dashboard`, `authored-pipeline`, `job`
*(R2 — the upsert shape; runtime state like last status/run never travels)*.

`content` is the artifact's complete config — a widget's channel mapping + options, a Geo view's
query + display mode + camera, a pipeline's lossless node/edge graph — so an imported viz
**renders exactly as authored** once its references resolve. JSON is the parsed form of the
backend's `.toon` component content; a backend-served TOON export can reuse this envelope
1:1 when the ControlApi grows bundle endpoints (see `backend-backlog.md` — the closed
`ComponentStore.WRITABLE_TYPES` enum is the same blocker).

## Export

Pick artifacts per kind (all/none + per item). **Include dependencies** (default on) expands the
selection to its transitive closure — a dashboard pulls its widgets, widgets pull their dataset
and saved view, views pull their datasets, pipelines pull grammars/transforms/sinks/connections
(`refsOf`/`withDependencies`). Unresolvable references export anyway and are reported.

## Import

Upload → validated preview: every artifact shown with **New / Exists** against this instance and
a per-item action — new items default to **Import**, existing ones to **Skip**; the user opts
into **Overwrite** per item or via *Overwrite all existing* (the dataset-conflict decision the
feature exists for). Apply writes sequentially in reference order via the existing CRUD services
(components / connections / authored pipelines), reporting per-row imported / overwritten /
failed without aborting the rest. Import is gated on the Builder capability
(`lens.canAuthorWorkbench()`).

Notes:
- **Secrets never travel** (§0.6): connection secrets export masked (`***` / `${ENV:…}`) — set the
  environment values or re-enter secrets on the target after import.
- **Data does not travel**: an imported dataset is metadata; its rows come from whatever the
  target instance's sources provide (in mock mode, `SAMPLE_SOURCES` by `sourceName`).
- Acquisition **Sources ride with their pipeline** (the collector node + its connection binding);
  the Sources page's operational rows are runtime state, not bundle content.
