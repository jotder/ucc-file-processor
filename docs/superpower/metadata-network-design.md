# The Metadata Network — configuration-graph design & bundle schema (v2 proposal)

> Discussion draft, 2026-07-06. Companion to [`metadata-bundle.md`](metadata-bundle.md) (shipped v1).
> Schema: [`schemas/metadata-bundle.schema.json`](schemas/metadata-bundle.schema.json) ·
> samples: [`schemas/samples/`](schemas/samples/).

## 1. The thesis: Inspecto *is* its metadata network

As a meta-product, every artifact is a **Component** — `{ kind, id, config }` — and the product's
real substance is the **typed graph over those configs**:

```
connection ←─ binds ── pipeline ── binds ─→ grammar/schema/transform/sink
                          │ feeds (physicalRef / sourceName)
                          ▼
                       dataset ←─ binds ── widget ←─ tiles ── dashboard
                          ▲                  │ renders
                          └── projects ── geo-map-view / link-analysis-view
```

Everything the platform does — the reuse graph, delete protection, impact analysis ("what breaks
if I change this dataset?"), Space Templates, and now cross-instance promotion — is a traversal of
this one graph. **Lineage** = the edges (what an artifact is built from). **Provenance** = where a
given config came from (which instance/space exported it, when, what it looked like). A bundle is
nothing more than a *serialized, self-describing subgraph*.

## 2. Honest audit: is the relationship model clean today?

The **model** is clean — `Component { kind, config, parts?, wiring? }` with a pure graph derivation
(`inspecto/component-model/component-graph.ts`, ghost nodes for missing refs). The **derivation of
edges is not**: four sites independently re-derive "what does X reference?", with different
completeness:

| Derivation site | widget→dataset | widget→view | dashboard→widget | view→dataset | pipeline→use refs |
|---|---|---|---|---|---|
| `transfer/bundle.ts` `refsOf()` (bundle closure) | ✔ | ✔ | ✔ | ✔ | ✔ |
| `catalog/registry.component.ts` `partsFor()`/`pipelineParts()` (reuse-graph) | ✔ | ✘ | ✔ | ✘ | ✔ |
| `mock/integrity.ts` RefRules (409-on-delete) | ✘ | ✘ | ✘ *(reads `content.parts`, which nothing persists)* | ✘ | ✔ |
| `ComponentKind.deriveWiring`/`allowedPartKinds` (metamodel) | shape only | ✘ | shape only | ✘ | edges only |

Consequences today: the reuse-graph doesn't draw widget→view or view→dataset edges; **deleting a
widget that a dashboard tiles, or a dataset that a widget/view binds, is not blocked** (only
pipeline `use:` refs are protected); and the bundle closure is the *only* complete derivation —
living in the wrong layer.

**Verdict: rework is warranted, and it is small.** The seam is already named in the code
("A `deriveParts` ComponentKind seam could formalize this later" — `registry.component.ts:172`).

### Rework R1: one derivation, four consumers — ✅ SHIPPED 2026-07-06

Implemented as `inspecto/component-model/refs.ts` (`Ref {kind,id,rel,via}` + structural
derivations + `refsForComponent`), the `ComponentKind.deriveRefs` seam (adopted by widget /
dashboard / pipeline kinds), and all four consumers rewired: reuse-graph (now draws widget→view
and view→dataset edges; saved-view kinds joined `REGISTRY_KINDS`), bundle closure, and the mock
integrity rules (deleting a tiled widget / bound dataset / rendered view now 409s). Invariants:
`component-model/refs.spec.ts` + the new 409 cases in `components.handler.spec.ts`.
The remainder of this section is the original proposal, kept for rationale.

Add to `ComponentKind` (in `inspecto/component-model`):

```ts
/** The outgoing references derivable from a config — THE single source of lineage edges. */
deriveRefs?: (config: TConfig) => Ref[];   // Ref = { kind: string; id: string; rel: RefRel }
type RefRel = 'binds' | 'tiles' | 'renders' | 'projects' | 'loads';
```

Each kind's `*.kind.ts` implements it once (widget, dashboard, the two view kinds, pipeline,
dataset→`loads` when `physicalRef` names a store). Consumers then *stop deriving*:

1. **Reuse-graph** (`partsFor` → deleted; parts built from `deriveRefs`).
2. **Bundle closure** (`refsOf` → delegates to the registry).
3. **Mock integrity rules** (one generic rule over `deriveRefs` — fixes the delete-protection gaps).
4. **Import fit-check** (below) — validates a bundle against the *target's* graph.

The backend eventually mirrors the same contract (`ComponentStore` ref extraction per kind) — one
vocabulary, `docs/COMPONENT_GRAPH.md` becomes its spec.

## 3. Where import/export lives (recommendation)

Same envelope everywhere — a "single export" is just a 1-item bundle, so there is **one format,
three surfaces**:

| Surface | Scope | Actions |
|---|---|---|
| **Editor pages** (Widget Builder, Dashboard Builder editor, Pipeline editor, dataset editor, Geo/Link studios' saved-view rows) | this one artifact | **Export** (with/without deps toggle) · **Import into this editor** (opens the config as a draft for review, saves through the normal save flow — so the editor's validation runs) |
| **Library/list pages** (Viz Library toolbar, Pipelines list, Datasets list) | a filtered set | **Export selected/filtered** (e.g. every widget tagged `fraud`) · **Import…** (accepts 1-or-N-item bundles, same preview dialog) |
| **Settings → Import & Export** (shipped) | cross-kind, whole-space subsets | the full selection + closure + conflict workbench — the promotion tool |

Recommendation against putting it on **Config**: the Config page is server/runtime configuration
(TOON of the instance), not the Component graph — mixing the two blurs the boundary the bundle
deliberately draws (see §5). The pipeline editor gets per-pipeline export (its natural "share this
flow" gesture); *sets* of viz items belong to the Viz Library toolbar (it is the library);
everything-across-kinds stays in Settings. The import *dialog* (preview + per-item overwrite)
should be extracted from the Transfer pane into a shared component so all three surfaces reuse it.

## 4. Bundle format v2 — lineage & provenance made explicit

v1 (shipped) carries `{kind, id, content}` and *re-derives* dependencies on both sides. v2 makes the
subgraph **self-describing**, so the target can fit-check a bundle without knowing how to derive
refs for kinds it hasn't been taught yet (or newer kinds than the target build knows):

- **`items[].refs`** — the item's outgoing lineage edges, each marked `resolution: "included"`
  (the referent travels in this bundle) or `"external"` (must already exist on the target).
- **`items[].provenance`** — origin instance/space, export time, `contentHash` (SHA-256 of the
  canonical JSON of `content`) and optional origin `etag`/version. Enables: drift detection
  ("exists on target but differs from what staging exported"), idempotent re-promotion
  ("identical hash → skip silently"), and an audit trail on the target.
- **top-level `requires`** — the aggregated external refs: the bundle's *contract with the target*.
  Import preview resolves each against the target graph and classifies: `satisfied` /
  `missing` (block or warn) / `present-but-different` (hash mismatch — surfaced, user decides).

v1 files remain importable (refs/provenance are optional; absent ⇒ derive on the target as today).

### Import fit-check algorithm (target side)

1. Parse + schema-validate the envelope.
2. Build the resolution set = target's existing ids ∪ bundle's included items.
3. For every `refs[]` entry (or derived refs for v1 files): classify satisfied / missing / drifted.
4. Preview: per-item New/Exists/Drifted + per-item action (import / overwrite / skip; existing
   defaults to **skip**), plus a `requires` panel showing unmet externals *before* anything writes.
5. Apply in kind order (referenced kinds first) — already shipped behavior.

## 5. Boundary — what the bundle is and is not

**In scope (configuration):** dataset *metadata* (columns/roles/measures/query — not rows),
widgets (mapping + options), dashboards (tiles/filter), saved Geo/Link views (query + display +
camera + annotations), pipelines (lossless node/edge graph), grammars/schemas/transforms/sinks,
connections (secret-**masked**), and rules when the kind joins the enum.

**Out of scope (deliberately):** data rows and stores; secrets (only `${ENV:…}` references travel);
runtime/operational state (runs, batches, incidents, watermarks, inbox status); server config
(TOON instance config — different lifecycle, different owner); canvas layout positions (not
persisted anyway); id renaming/remapping (ids are the graph's foreign keys — a rename tool is a
separate, target-side feature); content-shape migration (the envelope is versioned, item `content`
is owned by each kind's editor/validator — kind owners migrate shapes, the bundle just carries them).

## 6. Open questions for discussion

1. **Blocking vs warning on missing externals** — block apply until satisfied, or import anyway and
   rely on ghost-node rendering? (Proposal: warn + allow, matching the reuse-graph's ghost-node
   philosophy; blocking only when the user asks for "strict" mode.)
2. **Hash canonicalization** — JSON canonical form (sorted keys, no whitespace) is enough for
   config-sized payloads; do we need anything stronger?
3. **Space semantics** — a bundle exports *from* one space and imports *into* the active space;
   cross-space fan-out (import into N spaces) stays a Space-Template concern, not a bundle concern?
4. **Backend endpoints** — when `ComponentStore.WRITABLE_TYPES` widens, does the server take over
   export (`GET /bundle?items=…`, TOON-bodied) with the UI keeping the same envelope?
