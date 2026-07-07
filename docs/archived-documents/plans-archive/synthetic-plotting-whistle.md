# Plan — Flow-topology editor pane (inspecto-ui, interactive G6 canvas)

## Context
T32 shipped the backend for authored flows (CRUD + dry-run, T18/T19) and a **read-only** Flows pane (T31).
The build-side UX is still missing: there is no way to *author/edit* a `*_flow.toon` flow from the UI. This
adds an **interactive G6 canvas editor** (user-chosen over a form-driven one): drag node-types from a palette
onto a canvas, draw edges between nodes, edit node config in a side inspector, dry-run a sample, and save.
Frontend-only — every backend endpoint already exists. `feat(ui):` → master.

## Backend contract (already exists — confirm exact JSON at impl start by reading `ControlApi.java`)
- `GET /flows/node-types` → `FlowNodeType[]` (palette; already consumed by the read-only pane).
- `GET /flows/authored` → authored flow list. **Verify the item shape** (summary vs full).
- `GET /flows/authored/{id}` → flow **graph projection** (T19). **Risk:** the projection is display-shaped
  (no raw `config`). The editor must round-trip node `config`, so confirm whether config is exposed; if not,
  the inspector edits config locally and PUT sends the authored shape (see Risks).
- `POST /flows/authored` `{name,nodes,edges,active}` → create (409 dup) — **503 without `-Dassist.write.root`**.
- `PUT /flows/authored/{id}` → replace whole graph (422 on `FlowValidator` errors). **Primary save path.**
- `DELETE /flows/authored/{id}` (gated). `POST .../nodes`, `.../edges` granular add (gated).
- `POST /flows/authored/{id}/dry-run` `{sampleRows}` → per-node relation counts + per-sink row count + sample.

## Approach
A **new** standalone feature component (keeps `flows.component` lean) reached via a **3rd mode toggle**
(`flow | combined | editor`) on the existing Flows pane — the project's mode-toggle-not-new-nav convention
([[inspecto-ui-conventions]]; T24 added the toggle). No new nav item, no new route.

### 1. `FlowsService` — add authored CRUD + dry-run (`inspecto/api/flows.service.ts`, barrel-exported)
New authored-shape DTOs (distinct from the read-only projection DTOs already there):
`AuthoredFlow{name;active;nodes:AuthoredNode[];edges:AuthoredEdge[]}`, `AuthoredNode{id;type;name?;description?;use?;config?:Record<string,unknown>}`,
`AuthoredEdge{from;rel;to}`, `FlowDryRunResult{inputColumns?;relations|nodes:{...};sinks:{...}}` (shape from `ControlApi`).
Methods (reuse `apiUrl`/`toParams`): `authoredList()`, `authored(id)`, `createAuthored(body)`, `replaceAuthored(id,body)`,
`deleteAuthored(id)`, `dryRunAuthored(id, sampleRows)`; reuse existing `nodeTypes()`.

### 2. Editing G6 host — new shared component `modules/admin/flows/flow-editor-graph.component.ts`
**Separate from `GraphViewComponent`** (catalog/graph-view) because that one `destroy()`s + rebuilds on every
`ngOnChanges` — fatal for interactive state. The editor host instead keeps a persistent `Graph` and **mutates**
it (`addNodeData`/`updateNodeData`/`removeNodeData`/`addEdgeData`). Reuse `canvasTheme()` + `nodeColor/nodeShape`
patterns from the read-only host for visual consistency.
- G6 v5.1.1 behaviors: `drag-canvas`, `zoom-canvas`, `drag-element`, `click-select`, `create-edge` (drag node→node
  to draw an edge), plus a `keydown(Delete)` handler on the host for delete-selected.
- HTML5 drag-drop: palette chip `draggable` → host `drop` → convert client→canvas coords
  (`graph.getCanvasByViewport`/viewport API in v5) → emit `nodeDropped(type, x, y)`.
- `@Output()`s: `nodeSelected(id)`, `edgeSelected(from,rel,to)`, `edgeCreated(from,to)`, `nodeMoved(id,x,y)`,
  `deleteSelected(...)`, `nodeDropped(type,x,y)`. `@Input() data` (editable G6 model) + `@Input() dark` handling
  via `GammaConfigService` like the read-only host. No `autoFit` after first render (preserve user layout).

### 3. Editor pane — `modules/admin/flows/flow-editor.component.ts` (+ `.html`), signals + OnPush
- **Toolbar:** authored-flow `<mat-select>` + **New** (dialog: name) + **Save** (enabled when `dirty()`, PUT) +
  **Delete** (`InspectoConfirmService.confirmDestructive`) + **Dry-run** (opens panel).
- **Left palette:** `nodeTypes()` grouped by category (reuse `groupByCategory` from `flow-graph.ts`); draggable chips.
- **Center:** `<app-flow-editor-graph>` bound to the editable model (signal).
- **Right inspector:** reactive form for the selected node (`id` read-only, `type` read-only, `name`,
  `description`, `use`, and a config key/value editor) with inline `<mat-error>`; edge inspector (`rel` select:
  `data`/`control`/`route:<key>`). Apply → `updateNodeData` + mark dirty.
- **Dry-run drawer:** sample-rows JSON `<textarea>` → `dryRunAuthored` → table of per-node relation row counts +
  per-sink counts (reuse ag-grid helpers or a simple table; no color helpers).
- **State (signals):** `model`, `selected`, `dirty`, `saving`, `unavailable` (503 → read-only notice reusing the
  connections-pane pattern). Save maps the editable model → authored shape; **422** surfaces `FlowValidator`
  issues via `apiErrorMessage` toast + inline list. `<inspecto-empty-state>` when no authored flows.
- Map projection↔editable model in `flow-graph.ts` (extend it) — reuse `nodeDisplayLabel`, `categoryColor`.

### 4. Wire-up (two edits, per the skill)
- `flows.component.ts/.html`: add `'editor'` to `FlowsViewMode` + a toggle button; render `<app-flow-editor>` in
  that mode. (No `app.routes.ts`/nav change — same route.)

### 5. Tests (`flow-editor.component.spec.ts`, vitest + TestBed)
Mock `FlowsService`. Cover: model↔G6 mapping, add-node-on-drop, edge-created updates the model, delete, dry-run
result mapping, dirty/save, 503 read-only path. `await expectNoA11yViolations(fixture.nativeElement)` on the
empty/read-only render (jsdom can't exercise the live G6 canvas).

## Risks / decisions
- **Config round-trip (primary risk):** if `GET /flows/authored/{id}` returns only the display projection (no raw
  `config`), editing+PUT could drop node config. **Mitigation:** at impl start, read `ControlApi` authored-GET; if
  config isn't exposed, the editor keeps the loaded authored model in memory and the inspector owns config — PUT
  sends the full authored shape. If even the list/GET can't yield config, fall back to per-node `POST /nodes` with
  full config from the inspector. Decide once the exact JSON is confirmed (read-only, cheap).
- **G6 v5 interactive APIs** (`create-edge`, client→canvas coord conversion) are fiddly — verify against 5.1.1
  during impl; the host is isolated so iteration is cheap.
- Keep it lean: **no new deps** (G6 + Material drag already present; use HTML5 DnD, not a DnD lib).

## Verification (DoD)
1. `npm run lint:tokens` (no hardcoded colors) · 2. `npm run build` (AOT + budgets) · 3. `npm run test:ci`
(unit + axe). 4. **Preview-verify** (both servers via `.claude/launch.json`, needs `-Dassist.write.root`): open
Flows → Editor, drag a node from the palette onto the canvas, draw an edge, edit node config, **Save** (PUT 200),
**Dry-run** a sample (counts render), reload and confirm persistence; check `preview_console_logs` clean.
5. Update the `/design` gallery + the `angular-ui` skill if the editing G6 host becomes a reusable pattern.
6. Commit `feat(ui):` → master (frontend-only, empty merge-forward set); push only on explicit ask.

## Out of scope (follow-ups)
Auto-layout-on-demand, undo/redo, multi-select marquee bulk ops, live collaborative editing, and surfacing
`sink.view` `derived_sql` — all deferred.
