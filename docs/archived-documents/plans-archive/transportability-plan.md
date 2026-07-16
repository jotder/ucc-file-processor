# R6 — Transportability everywhere (bundle v2 + surfaces everywhere)

> Plan, 2026-07-06. Closes the last slice of the Living Operational System roadmap
> ([living-operational-system.md](living-operational-system.md) §5). Companion to
> [metadata-network-design.md](metadata-network-design.md) §3–§5 and
> [metadata-bundle.md](metadata-bundle.md) (v1). Scope agreed with the product owner: **Full — every
> surface** (bundle v2 format + shared import dialog + export/import affordance on every editor and
> library page).

## 1. Thesis

A bundle is a *serialized, self-describing subgraph* of the metadata network. v1 carried `{kind, id,
content}` and re-derived dependencies on both sides; v2 makes the subgraph explicit (its lineage
edges + provenance) so a target can **fit-check** a bundle without re-deriving refs for kinds it may
not know, detect **drift** ("exists but differs"), and skip **idempotent** re-promotion. And there
is **one format, three surfaces** — a single export is a 1-item bundle — so the same shared machinery
serves the Settings promotion tool, every library/list page, and every editor.

## 2. Bundle format v2 (metadata-network-design §4)

Additive and backward-compatible — v1 files still import (refs/provenance optional ⇒ derive on the
target as today). Bump `BUNDLE_VERSION` 1 → 2.

- **`items[].refs: BundleRef[]`** — the item's outgoing lineage edges (from the R1
  `refsForComponent` derivation), each `{ kind, id, rel, resolution }` where `resolution` is
  `"included"` (the referent travels in this bundle) or `"external"` (must already exist on the
  target). `rel` is the R1 `RefRel` (`binds|tiles|renders|projects|triggers|loads|emits|invokes`).
- **`items[].provenance: { sourceSpace, exportedAt, contentHash }`** — origin space, export time, and
  a SHA-256 (hex) of the item content's canonical JSON. Enables drift detection, idempotent
  re-promotion, and a target-side audit trail.
- **top-level `requires: BundleRef[]`** — the deduped aggregate of every `external` ref: the bundle's
  contract with the target.

### Fit-check (target side)

`planImport(bundle, target)` where `target: TargetIndex = Map<BundleKind, Map<id, contentHash>>`
(the target's existing ids + content hashes). Per item classify **new / exists / drifted** (exists
AND content hash ≠ target's), default action `import` for new, `skip` for exists/drifted (user opts
into overwrite). `resolveRequires(bundle, target)` classifies each `requires` entry **satisfied /
missing** and drives a "requires" panel shown *before* any write.

**Deliberate cut:** `requires` are external refs with no travelling content, so they classify
satisfied/missing only — `present-but-different` is a per-item (drift) concept, not a per-require
one (we don't carry an origin hash on the ref). Documented, not a gap.

## 3. One format, three surfaces (metadata-network-design §3)

The import preview/apply is extracted from the Settings Transfer pane into a **shared lib**
`inspecto/transfer/` (feature pages can't import another feature):

- `bundle.ts` (moved here + v2), `content-hash.ts` (`canonicalJson` + sync `sha256Hex`).
- `BundleTransferService` — `loadAll()` (every exportable artifact + its content), `targetIndex()`,
  `write(row)`, `exportAndDownload(selected, available, {includeDeps})`. The load/write logic the
  Transfer pane used to inline, now single-sourced so the dialog and the menu reuse it.
- `ImportBundleDialog` — the shared preview: parse → fit-check → per-row New/Exists/Drifted + action
  + requires panel → apply (via the service). Optional `allowedKinds` scopes a library import.
- `<inspecto-transfer-menu [items] [allowedKinds] (changed)>` — the reusable affordance (an
  icon-button `mat-menu`): **Export (with dependencies)** · **Export (this only)** · **Import…**
  (opens the dialog). `items` = the artifact(s) this surface offers; `changed` fires after an import
  so the host reloads.

Surfaces (drop in the menu):

| Surface | `items` |
|---|---|
| Settings → Import & Export | keeps its full selection workbench; now builds v2 + shows the fit-check |
| **Editors** — Widget Builder, Dashboard Builder, Pipeline editor, dataset editor | the one artifact being edited |
| **Studios** — Geo Map / Link Analysis saved-view rows | the saved view row |
| **Libraries** — Viz Library, Pipelines list, Datasets list | the filtered rows |

**Deliberate cut:** editor Import uses the *same* preview-and-apply dialog as everywhere else (writes
through the normal create/update handlers, so each kind's validator still runs) rather than a
bespoke "load as editor draft" flow — one component, uniform behavior, honoring "no abstraction
without a second consumer." Noted for a future per-editor draft-load if a real need appears.

## 4. Steps → verify

1. `content-hash.ts` (+ spec: canonical + stable + order-independent). → `test:ci` green.
2. `bundle.ts` moved to `inspecto/transfer/` + v2 (refs/provenance/requires/fit-check drift). Extend
   `bundle.spec.ts`. → round-trip + drift + requires invariants pass.
3. `BundleTransferService` (+ spec). Rewire the Transfer pane to use it. → pane spec still green.
4. `ImportBundleDialog` (+ spec, incl. a11y) and `transfer-menu.component` (+ spec, incl. a11y).
5. Wire `<inspecto-transfer-menu>` into every editor/studio/library surface.
6. Docs: flip R6 → SHIPPED in the roadmap; GLOSSARY (Bundle v2 / Provenance / drift); this plan.
7. GAUNTLET (lint:tokens + build + test:ci), live smoke of one editor export + one library import.
8. One `feat(ui):` commit on master.
