# Flow Authoring — In-Graph Build-and-Test Design

> **Status (2026-06-26): Stages 0–4 BUILT + verified, mock-backed, UNCOMMITTED.** Stages 0 (run-to-here
> contract), 1 (in-graph choose-or-create component binding), 3 (the test loop: pick inbox files → run
> subgraph → matched/unmatched + scratch Parquet), 2 (canvas node-status + edge relationship picker), and
> 4 (validate & gated activate) are implemented and verified end-to-end (`lint:tokens` ✓, prod `build` ✓,
> `test:ci` ✓ — 127 passed incl. new dialog + pure-logic specs, live preview ✓), plus Stage 5 ergonomics
> in part (overflow-menu delete, keyboard-addable palette, trimmed header). Remaining: the rest of Stage 5
> (hover-port connect, side-drawer) and the real backend. The **authoring-UX** companion to
> [`flow-graph-design.md`](flow-graph-design.md) (the graph model) and
> [`acquire-controller-service-design.md`](acquire-controller-service-design.md) (the connection/source layer).
> Those two settle *what the graph is*; this doc settles *how a pipeline developer builds one*, NiFi-style:
> a single, continuous, **build-a-little / test-a-little** loop that stays on the canvas. Same locked
> decisions — NiFi authoring + per-component testability on Inspecto's batch-atomic engine, **no inter-node
> queues**. UI-first / mock-backed (the project's stated build philosophy); the mock contract here is
> designed to *become* the real contract.

## 1. The problem (developer's point of view)

A pipeline developer's goal is concrete: *"files land in an inbox — parse them, shape them, land Parquet,
and let me see it working at every step."* Today every building block exists, but **scattered across five
screens that don't reference each other**:

| Step the developer wants | Lives in | Today's friction |
|---|---|---|
| Choose an inbox source | flow editor (`collector.file` node) + connections | `use:` ref typed by hand |
| Select & configure a parser, **create/choose a grammar** | component registry (`grammar`) | authored on a *different* screen, then referenced by exact id |
| **Create/choose the events** a parser emits | events / component registry (`schema`) | same — separate screen |
| Configure a transform | component registry (`transform`) | same |
| **Test: pick inbox files → parse → save Parquet** | — | the per-node test returns *canned* rows; no file pick, no materialize |
| Attach transform, test → transformed Parquet | flow editor dry-run | dry-run is an in-memory sample, not a run-to-here over real files |

So building the pipeline above means: author a grammar on screen A, events on screen B, a transform on
screen A again, then go to screen C (the graph), drop nodes, and **hand-type exact `use:` ids** to wire them
— with no in-context test that runs real files to Parquet. **This design collapses that into one in-graph
loop.**

The pieces we build *on* (already shipped):
- **Component registry** ([`components.service.ts`](../inspecto-ui/src/app/inspecto/api/components.service.ts)) —
  `grammar`/`schema`/`transform`/`sink` with structured per-kind forms
  ([`component-form.dialog.ts`](../inspecto-ui/src/app/modules/admin/components/component-form.dialog.ts)) **and**
  real test endpoints: `testGrammar` → `{columns, rows, rejectedRows}`, `testSchema`/`testTransform` →
  `{inputColumns, relations[]}`, `testSink` → `{store, rows, warnings}`. The matched/rejected split the loop
  needs **already exists**.
- **Connection workbench** ([`connection-workbench.component.ts`](../inspecto-ui/src/app/modules/admin/connections/connection-workbench.component.ts))
  — explore an inbox tree + sample files. This is "select files from inbox."
- **Flow editor** ([`flow-editor.component.ts`](../inspecto-ui/src/app/modules/admin/flows/flow-editor.component.ts))
  + authored-flow CRUD + dry-run ([`flows.service.ts`](../inspecto-ui/src/app/inspecto/api/flows.service.ts)).

## 2. Golden path (the use case we build first — UC1)

> *"CDR files land in an SFTP inbox; I want parsed, filtered Parquet out — and I want to watch it work."*

```
1. New pipeline                          → empty canvas, draft
2. Drop a File source, bind a connection → cdr_sftp_prod (reuses the connection registry)
3. Drop a DSV parser, connect success    → edge carries rel=success
4. Configure parser → choose|create grammar inline (delimiter/header/quote)
5. Bind the events it emits → choose|create a schema (the typed output record)
6. RUN TO HERE: pick files from the inbox → parse → matched rows land as Parquet
      ↳ inspect: matched count, unmatched sample, the Parquet path written   ← UC2 loop
7. Drop a Filter/Record transform, connect, author rules
8. RUN TO HERE again → transformed rows land as Parquet                       ← UC4
9. Validate the graph → activate                                             ← UC6
```

The other use cases (UC2 grammar iteration, UC3 events/types, UC4 transform-to-Parquet, UC5 reuse-vs-create,
UC6 promote-to-active) are widenings of this same spine — built after the vertical slice proves the loop.

## 3. The two design moves

### 3.1 Unified in-graph authoring (choose **or** create inline)

A typed node's config is **not** a generic key/value table — it is the structured form for its kind, plus a
**component binding** control:

- **Parser node** → a *Grammar* binding: a typeahead over `components(grammar)` **or** `＋ New grammar`
  which opens the existing `component-form` inline and binds the returned `<type>/<id>` ref into the node's
  `use`. Same pattern for the *events/schema* it emits, the *transform* rules, and the *sink* format.
- The generic key/value editor remains as a **power-user escape hatch**, not the default.
- Net effect: the registry stops being a separate destination — it's reachable *in context* from the node,
  and reuse (UC5) is just "the typeahead already has it".

Contract: no new endpoints — reuse `ComponentsService.list/get/create`. The node↔component link is the
existing `AuthoredNode.use` string (`grammar/cdr_csv`, `transform/drop_test`, …).

### 3.2 The incremental test loop ("Run to here" over picked inbox files)

The current per-node test ([`flow-mock.interceptor.ts`](../inspecto-ui/src/app/inspecto/api/flow-mock.interceptor.ts)
`componentTest`) returns canned rows. The developer's "test" is richer: **select real files, run the subgraph
up to a node, materialize to Parquet, inspect per-relation output.** New contract (mock first):

```
POST /flows/authored/{id}/run?to={nodeId}
  body: { files: string[] }        # inbox paths chosen via the connection-tree
  →  {
       seedNode, toNode,
       relations: [{ node, rel, rowCount, rows[] }],   # matched / unmatched / kept / dropped …
       output:  { store, format, path, rowCount },     # the (simulated) Parquet landing
       warnings: string[]
     }
```

This generalises the existing `dry-run` (whole-graph, in-memory sample) to **run-to-here over chosen files
with a materialized output**. For a parser node it surfaces `success` vs `unmatched` as separate relations
(the grammar feedback loop, UC2), reusing the same shape `RelationsPreview` already uses.

Inbox file listing for the picker reuses the connection-probe **explore** contract
([`connection-probe.service.ts`](../inspecto-ui/src/app/inspecto/api/connection-probe.service.ts)) — the
source node's bound connection drives the tree.

## 4. Node & edge state (visible on the canvas)

NiFi's value is that the canvas *tells you the truth at a glance*. The editor models per-node status:

| State | Meaning | Canvas cue |
|---|---|---|
| **Unconfigured** | required config / binding missing | dashed border + ⚠ glyph |
| **Configured** | valid, not yet tested | solid border |
| **Tested-OK** | last run-to-here passed | ✓ corner badge |
| **Test-failed** | last run produced rejects / error | ✕ corner badge (text + color, never color alone) |
| **Dangling ref** | `use:` points at a missing component | ⚠ glyph + tooltip |

Edges carry their **relationship** (`success`/`unmatched`/`kept`/`dropped`/`route:<key>`). Edge creation
**prompts for the relationship** from the source's `FlowNodeType.emits` — otherwise the unmatched/dropped
branches are silently dropped. After a run, edges show **record counts** (the provenance weight).

## 5. Usability principles (the bar every stage is held to)

1. **Stay on the canvas.** Authoring a grammar/transform never means navigating away and memorizing an id.
2. **Choose *or* create, everywhere.** Every "configure X" is a reuse-or-author fork (UC5).
3. **Test materializes visibly.** Run-to-here always shows counts, a sample grid, and the Parquet path.
4. **Structured forms for typed nodes.** JSON/key-value is the escape hatch, not the default.
5. **State on the node, counts on the edge.** No hunting in a side panel to learn a node is invalid.
6. **Validate before activate.** A draft with untested/dangling nodes cannot silently go live (UC6).
7. **Discoverable gestures.** Hover-port connect (not Shift-drag), click-to-add palette (keyboard-reachable),
   one unambiguous delete. (Carries the 2026-06-26 editor-reassessment fixes.)

## 6. Staged, mock-first plan (each stage: `lint:tokens` → `build` → `test:ci` → preview)

> Build order is **vertical** — UC1's thin slice through Stages 0/1/3 first, to prove the loop end-to-end
> before widening. Stages 2/4/5 widen and harden.

- **Stage 0 — test-loop contract (mock). ✅ DONE.** `runToNode(id, nodeId, files)` + `FlowRunResult` in
  `FlowsService`; `flow-mock.interceptor` serves `POST …/run?to=` (subgraph walk + matched/unmatched split +
  scratch Parquet path) and the component-registry CRUD the binding needs. Inbox listing via connection-probe explore.
- **Stage 1 — in-graph component binding. ✅ DONE.** `node-config.dialog` shows a choose-or-create-inline
  picker for the node's bound kind (parser→grammar, transform→transform, sink→sink); "New …" opens the
  reused `component-form` and binds the returned ref into `AuthoredNode.use`.
- **Stage 3 — the test loop. ✅ DONE.** New `run-to-here.dialog`: pick inbox files (reused connection-tree),
  run the subgraph up to the node, see per-relation results (success/unmatched/kept/dropped) + the Parquet
  landed. Reached from the inspector's **Run to here** action.
- **Stage 2 — canvas state. ✅ DONE.** Per-node status (`unconfigured`/`dangling`/`configured`/`tested`/
  `rejects`) computed in the editor and painted on the canvas (outline colour + dashed border + label glyph)
  and the inspector chip; run-to-here outcomes mark nodes tested/rejects. The edge inspector picks the
  relationship the connection carries, from the source node's `emits`.
- **Stage 4 — validate & activate. ✅ DONE.** `validateFlow` walks the graph (unconfigured/dangling = error,
  no source/sink + orphan = warning, untested = info); the Validate panel lists findings (click → select the
  node), and **Activate** is gated on zero errors (Deactivate toggles back). Pure logic unit-tested.
- **Stage 5 — ergonomics. ◐ PARTIAL.** Done: "Delete flow" moved to an overflow menu (disambiguated from
  "Delete selected"); palette processors are click/keyboard-addable (`addFromPalette` → canvas centre — the
  no-mouse path, a WCAG fix), each with an `Add <label>` aria-label; the verbose connections header trimmed.
  **Deferred (separate effort):** hover-port drag-to-connect (a G6 v5 interaction rework, hard to verify
  headless — two-click Connect remains the discoverable path), the side-drawer inspector (needs a canvas
  resize-observer), and the inline-style→`bg-card` normalization (cosmetic).
- **Deferred — backend wiring.** Swap mocks for the real `/run` materialization + registry binding + Parquet.
  Out of scope now; the Stage-0 contract is the seam.

## 7. Open questions

1. **Sink category name** — palette calls SINK "Writer"/"File writer"; confirm the user-facing label.
2. **Events vs. schema** — is "the events a parser emits" exactly a registry `schema`, or a distinct
   first-class concept the `events/` screen owns? (The golden-path slice treats it as a bound `schema`;
   revisit if events need their own lifecycle.)
3. **Run-to-here scope** — does materialized Parquet from a *test* run go to a throwaway scratch location
   (like the component tests' throwaway DuckDB) or a developer-visible `data/_scratch/`? (Slice assumes
   scratch + a shown path.)
