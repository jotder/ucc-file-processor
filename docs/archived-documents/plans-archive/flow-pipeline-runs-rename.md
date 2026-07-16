# Flow → Pipeline + Pipeline → Runs (UI restructure)

**Status:** in progress on `feat/rename-flow-pipeline-runs` (2026-06-30). UI-only, mock-backed; **no Java**.
Last term of the *2b* canonical-vocabulary migration (`docs/GLOSSARY.md` §13). Resolves the collision where
two FE surfaces both wanted the name "Pipeline".

## Canonical model (GLOSSARY §5)
- **Pipeline** = the authored **DAG of Steps** (today's *Flow* editor). ⛔ never "Flow".
- **Run** = one *execution* of an Executable (Run ⊇ Batch ⊇ File). The existing `/pipelines` page lists ingest
  pipelines + their run/lifecycle actions — it is the **Runs** ops surface (nav + header already say "Runs").

## Decision (user, 2026-06-30): **full UI restructure**
- Flow editor `Flow*` → `Pipeline*`, route `/flows` → `/pipelines`.
- Ingest ops page `Pipeline*` → `Run*`, route `/pipelines` → `/runs` (frees the "Pipeline" name).
- Bare `Run*` for the ingest surface (it's *the* top-level Runs page); other run-types stay domain-prefixed.

## Execution order (one branch)
1. **Ingest `Pipeline*` → `Run*`** first (frees the name), then
2. **Flow `*` → `Pipeline*`**.
Final state must be self-consistent; intermediate compile state doesn't matter (verify at end).

## Map A — ingest ops surface → Run*
- Route: `app.routes.ts` `pipelines`→`runs`, `pipelines/:name`→`runs/:name`; module imports → `runs/runs.routes`,
  `run-detail/run-detail.routes`.
- Nav (`mock-api/common/navigation/data.ts`): group id `pipelines-group`→`runs-group`, group title `Pipelines`→`Runs`,
  item id `pipelines`→`runs`, link `/pipelines`→`/runs` (item title already "Runs").
- Dirs (git mv): `modules/admin/pipelines/`→`runs/`, `modules/admin/pipeline-detail/`→`run-detail/` (move child
  dialogs `reprocess.dialog.ts`, `batch-detail.dialog.ts` with parents). Rename files `pipelines.*`→`runs.*`,
  `pipeline-detail.*`→`run-detail.*`.
- Components: `app-pipelines`→`app-runs`, `PipelinesComponent`→`RunsComponent`; `app-pipeline-detail`→`app-run-detail`,
  `PipelineDetailComponent`→`RunDetailComponent`.
- Service: `inspecto/api/pipelines.service.ts`→`runs.service.ts`, `PipelinesService`→`RunsService`; barrel
  `inspecto/api/index.ts` export.
- Types (`inspecto/api/models.ts`): `PipelineView`→`RunView`, `PipelineRunResult`→`RunResult`,
  `PipelineStatus`→`RunStatus`.
- Cross-refs: `sources.component.ts`, `source-detail.dialog.ts` (import + `inject(PipelinesService)`→`RunsService`,
  rename local `pipelines`→`runs`); `search.component.ts` comment; specs.
- Labels: subtitle "Pipeline runs and lifecycle actions"→"Ingest runs and lifecycle actions"; column header
  `'Pipeline'`→`'Run'`; empty-state "No pipelines configured"→"No ingest runs configured"; detail breadcrumb
  `Pipelines`/`/pipelines`→`Runs`/`/runs`; confirm dialogs "pipeline"→"run".
- **KEEP**: backend endpoint strings `/pipelines...` in `pipelines.service.ts` HTTP calls + `demo-mock.interceptor.ts`
  regexes + `configPath: pipelines/{name}.toon` + `space.interceptor.spec.ts` URL assertions.

## Map B — Flow editor → Pipeline*
- Route: `app.routes.ts` `flows`→`pipelines`; module import → `pipelines/pipelines.routes` (the editor module now
  owns this path — distinct dir from the old ingest one which is now `runs/`).
- Nav: item id `flows`→`pipelines`, link `/flows`→`/pipelines` (title already "Pipelines").
- Dir (git mv): `modules/admin/flows/`→`modules/admin/pipelines/` (now free). Rename `flow*.*` files →
  `pipeline*.*` (`flows.component`→`pipelines.component`, `flow-editor*`→`pipeline-editor*`, `flow-graph.ts`→
  `pipeline-graph.ts`, keep `node-config.dialog`/`parser-config.dialog`/`run-to-here.dialog`).
- Components: `app-flows`→`app-pipelines`, `FlowsComponent`→`PipelinesComponent`; `app-flow-editor`→
  `app-pipeline-editor`, `FlowEditorComponent`→`PipelineEditorComponent`; `app-flow-editor-graph`→
  `app-pipeline-editor-graph`, `FlowEditorGraphComponent`→`PipelineEditorGraphComponent`.
- Service/types (`inspecto/api/flows.service.ts`→`pipelines.service.ts`): `FlowsService`→`PipelinesService`,
  `FlowSummary`→`PipelineSummary`, `FlowNode`→`PipelineNode`, `FlowEdge`→`PipelineEdge`, `FlowGraph`→`PipelineGraph`,
  `FlowNodeType`→`PipelineNodeType`, `FlowNodeCategory`→`PipelineNodeCategory`, `FlowCombined`→`PipelineCombined`,
  `AuthoredFlow`→`AuthoredPipeline`. Barrel export `flows.service`→`pipelines.service`.
  **KEEP** `FlowDryRunResult`/`FlowRunResult`/`FlowRunRelation`/`DryRun*` → rename Flow→Pipeline prefix
  (`PipelineDryRunResult`, `PipelineRunResult`(editor test result — NOTE this name was just freed by Map A; it now
  means the in-editor run-to-here result, which is fine and distinct), `PipelineRunRelation`).
- Mock: `flow-mock.interceptor.ts`→`pipeline-mock.interceptor.ts`; **KEEP** its backend endpoint regexes `/flows...`
  (backend contract unchanged) — only the filename/symbols change.
- `registry.component.ts`: `c.kind === 'pipeline'` routes to `['/flows']` → `['/pipelines']`.
- Labels: "New flow name"→"New pipeline name", "Flow"→"Pipeline", "New flow"→"New pipeline", "Save flow"→
  "Save pipeline", "More flow actions"→"More pipeline actions", "Delete flow"→"Delete pipeline", "No authored
  flows"→"No authored pipelines", "Flow editor canvas"→"Pipeline editor canvas".
- Specs: rename + update.

## Verify
`npm run lint:tokens` + prod `build` + `test:ci` green. Live-check `/pipelines` (editor) and `/runs` (ingest ops)
both load and nav links resolve. Backend untouched → `mvn` unaffected.

## GLOSSARY §13
Mark Flow→Pipeline row ✅ **UI DONE** (backend `com.gamma.flow` deferred). Note the ingest page → Runs split.
