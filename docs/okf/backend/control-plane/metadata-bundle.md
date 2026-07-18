---
type: Concept
title: Metadata Bundle & Transportability
description: The cross-instance config bundle — v2 envelope (refs + provenance + requires), BundleRoutes export/preview/import, drift detection, idempotent re-promotion, and the config-not-data boundary.
resource: inspecto/src/main/java/com/gamma/control/BundleRoutes.java
tags: [control-plane, bundle, transportability, promotion, content-hash, drift]
timestamp: 2026-07-16T00:00:00Z
---

# Metadata Bundle & Transportability

A bundle moves **configuration, never data rows** between instances (staging → production promotion). It is a
*serialized, self-describing subgraph* of the component graph: v1 carried `{kind, id, content}` and re-derived
dependencies on both sides; **v2** (R6, shipped 2026-07-06) adds `items[].refs` (outgoing lineage edges, each
`included | external`), `items[].provenance` (`sourceSpace`, `exportedAt`, `contentHash` — SHA-256 of the
canonical JSON), and top-level `requires` (the deduped external refs — the bundle's contract with the target).
v1 files stay importable (refs/provenance optional ⇒ derived on the target). Schema:
[`metadata-bundle.schema.json`](../../../api/schemas/metadata-bundle.schema.json). Distinct from the
**Space zip bundle** (whole-space clone, [multi-space](multi-space.md)).

## Backend endpoints (SPC-4, shipped 2026-07-07)

`BundleRoutes` (`com.gamma.control`) serves the v2 envelope for the `ComponentStore.WRITABLE_TYPES` kinds
(grammar/schema/transform/sink/dataset/query/widget/dashboard) plus, since 2026-07-18, `authored-pipeline`
(`PipelineStore`, round-tripped through `PipelineCodec`), `job` (`JobService`'s live registry — import
hot-registers via `upsertJob`, exactly like the `/jobs` write routes), and `saved-view` (the event-viewer
`SavedViewStore`; **not** the run-generated `pipeline.ViewStore` `sink.view` definitions, which aren't
authored config). Every supported kind is read/written through the uniform `BundleSource` seam regardless
of its backing store:

* **`POST /bundle/export`** — `{items, provenance?, requires?}` → `{bundle, missing}`; real content + real
  `provenance.contentHash`; an unsupported kind is a **422** (honest boundary), never a silent omission.
* **`POST /bundle/preview`** — read-only fit-check: per item `new | unchanged | drifted | unsupported` (incoming
  hash, normalized to the stored form, vs the target's), each `requires` entry `satisfied | missing`. No writes.
* **`POST /bundle/import`** — sequential upsert in dependency order (referenced kinds first), gated
  `canAuthorWorkbench` → write-root 503 → **integrity pre-check 422** (MNT-16: `ComponentIntegrity` blocks only
  findings the import would *introduce* — computed over (registry ∪ incoming) minus pre-existing). Existing
  defaults to skip (per-item `overwrite` opt-in); identical hash ⇒ `unchanged` (idempotent re-promotion);
  per-item outcomes, the batch never aborts.

## Boundary & invariants

* **Secrets never travel** — connection secrets export masked (`${ENV:…}` references only).
* **Data never travels** — a dataset item is metadata (columns/roles/measures/query); runtime state (runs,
  batches, Incidents, watermarks) and server TOON config are out of scope by design.
* **Not yet server-side:** `connection` stays out of scope on purpose — its profiles carry secret references,
  and whether a bundle may carry a masked or reference-only credential is an unmade policy call; promote a
  connection via the UI path or the whole-space zip until that call is made.
* `requires` classify satisfied/missing only — *present-but-different* is a per-item (drift) concept; a
  deliberate cut (no origin hash on a ref).

The UI side (one derivation `deriveRefs`, one format, every surface — Settings workbench + editor/library
transfer menus) lives in the frontend bundle. Design history: `docs/archived-documents/plans-archive/`
(`metadata-network-design.md`, `transportability-plan.md`, `metadata-bundle.md`).
