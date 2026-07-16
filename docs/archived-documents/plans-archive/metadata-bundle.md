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

## Backend endpoints (SPC-4, shipped 2026-07-07)

`BundleRoutes` (`com.gamma.control`) serves the v2 envelope for the `ComponentStore.WRITABLE_TYPES`
kinds (grammar/schema/transform/sink/dataset/query/widget/dashboard). The UI keeps closure + ref +
`requires` derivation (instance-independent); the backend is the authority the mock can't be — real
content, real `contentHash`, a real fit-check, real persistence:

- **`POST /bundle/export`** — body `{items:[{kind,id,refs?}], provenance?, sourceSpace?, requires?}` →
  `{bundle:<v2 envelope>, missing:[…]}`. Each item carries real `content` + `provenance.contentHash`
  (`sha256:` over the stored form); requested items absent here are reported under `missing`. An
  unsupported kind is a `422` (the honest boundary), not a silent omission.
- **`POST /bundle/preview`** — body = an envelope → read-only fit-check: per item
  `new | unchanged | drifted | unsupported` (hash of the incoming content, normalized to the stored
  form, vs the target's current hash), and each top-level `requires` entry `satisfied | missing`. No writes.
- **`POST /bundle/import`** — body `{bundle, actions?}` → sequential upsert in dependency order
  (referenced kinds first), gated on `canAuthorWorkbench` then the write-root 503. Existing defaults to
  skip (opt into `overwrite` per item); identical content is `unchanged` (idempotent re-promotion);
  per-item `imported | overwritten | skipped | unchanged | failed`, batch never aborts.

**Not yet server-side:** `connection` (secret-aware CRUD), `authored-pipeline`, `job`, and the
saved-view kinds live in their own stores — promote them via the UI mock path or the whole-space zip
(SPC-2) until a later slice teaches `BundleRoutes` their stores.

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
