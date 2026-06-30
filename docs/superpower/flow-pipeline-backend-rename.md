# Backend rename pass — Flow → Pipeline (the deferred "backend half")

**Status:** PLANNED (not started). Companion to the UI-only rename
[`flow-pipeline-runs-rename.md`](flow-pipeline-runs-rename.md). Closes the backend touchpoints the
UI-only directive (2026-06-30) deferred. Scope verified against the code 2026-06-30.

## Decisions (locked with the user, 2026-06-30)

1. **BI "Metric → Measure" is a backend NO-OP.** The backend has no BI "Metric" concept. Its BI/semantic
   construct is **KPI** (`kpis:` in `*_meta.toon`, `KpiMeta`, `NodeKind.KPI`, `IdScheme.kpi()`), which the
   GLOSSARY keeps as a *distinct canonical term* — "a single-number Measure with a target/threshold."
   Renaming KPI → Measure would conflate two canonical terms and is **explicitly not done**. The only
   `Metric*` types are the **ops** ones (`MetricRegistry`, `MetricsService`, `AcquisitionTelemetry`) — also
   kept. → Action: mark GLOSSARY §13 "Metric (BI) → Measure" backend cell **done (no backend work)**.
2. **Routes: swap to match the FE.** The FE UI already moved editor → `/pipelines` and ingest → `/runs`,
   but FE services + mock regexes still call the OLD backend paths. Backend target:
   `/flows` → `/pipelines` (editor) and `/pipelines` → `/runs` (ingest), then re-align the FE service paths
   and mock-interceptor regexes.
3. **Clean break, no version bump.** Nothing has shipped on `4.x`, so no released artifact carries the old
   enum string / TOON key / API contract — there is no data or config in the wild to protect.
   → **No migration/back-compat code**, and **no 5.0 tag**: these land on `master` as ordinary refactors.
   (This supersedes the earlier handoff note that the breaking `!` renames would force a 5.0 tag.)

## Naming map

| Old | New |
|---|---|
| package `com.gamma.flow` | `com.gamma.pipeline` |
| package `com.gamma.flow.exec` | `com.gamma.pipeline.exec` |
| `Flow{Codec,Compiler,Edge,Graph,Node,NodeType,NodeTypes,Projection,References,Rel,Store,Stores,Trigger,Validator}` | `Pipeline{…}` |
| `Flow{DryRun,Executor,JobRunner,WatermarkStore}` (in `.exec`) | `Pipeline{…}` |
| `JobType.FLOW` | `JobType.PIPELINE` |
| route prefix `/flows` (editor) | `/pipelines` |
| route prefix `/pipelines` (ingest) | `/runs` |
| `FlowRoutes` (serves editor) | `PipelineRoutes` |
| existing `PipelineRoutes` (serves ingest) | `RunRoutes` |

> **Class-name collision to manage:** `FlowRoutes` → `PipelineRoutes` collides with the *existing*
> `PipelineRoutes` until the latter becomes `RunRoutes`. Rename the ingest class to `RunRoutes` **first**,
> then rename `FlowRoutes` → `PipelineRoutes`, so the name is free at each step.

## Scope (verified touchpoints)

**Package (`inspecto/` only — none in agent/hosted/connectors):** 40 prod files (23 `com.gamma.flow`,
17 `com.gamma.flow.exec`) + 24 test files to move/rename; **90** `import com.gamma.flow.*` lines across
~8 consumers: `control/{FlowRoutes(9),ComponentRoutes(5),LineageRoutes(3),ViewRoutes(3),JobRoutes(1)}`,
`job/JobService(3 + 2 FQN)`, `service/{SourceService(6),ServiceStores(1 + 2 FQN)}`. Pure source-level —
package/type names never appear in TOON/DB, so **no serialization risk**.

**`JobType.FLOW`:** def `job/JobType.java:20`; refs `flow/exec/FlowJobRunner.java:117` (`type()`),
`job/JobService.java:319` (guard) + `:265,269,275` (`.name()` written into `JobRun` records); tests
`FlowJobRunnerTest` (15) + `JobServiceTest` (7). Also `JobType.from()` (`:24`) parses TOON `type: flow`
and the error message (`:29`) lists `flow`. Per decision #3 these are a clean break (no shim).

**Routes:** `/flows` editor API = `control/FlowRoutes.java:35–47` (13 routes incl. `/flows/authored*`,
`/flows/node-types`, `/flows/combined`, `/flows/{name}/graph`); registered in `control/ControlApi.java:271`.
`/pipelines` ingest API = `control/PipelineRoutes.java:35–73` (15 routes incl. `/pipelines/{name}/{trigger,
pause,resume,commits,batches,files,lineage,quarantine,reprocess,report}`, `/trigger`, `/status`, `/report`).

**Measure / EXCLUDE (do not touch):** `metrics/MetricRegistry.java`, `service/MetricsService.java`
(`/metrics` endpoint), `inspector/AcquisitionTelemetry.java`. KPI semantic layer
(`catalog/{SemanticModel,NodeKind,IdScheme,MetadataGraphBuilder,CatalogOverlay,EdgeKind}`,
`config/spec/ConfigSpecs.java:349`) **stays KPI**.

## Phased execution (each phase verified before the next)

**Backend verify loop:** `mvn -o clean test -DargLine=--enable-native-access=ALL-UNNAMED` (build-verify skill).
**FE verify loop:** `npm --prefix inspecto-ui run lint:tokens && run build && run test:ci`, then live preview.

- **Phase A — package + type rename** (`com.gamma.flow*` → `com.gamma.pipeline*`, ~18 `Flow*` types).
  Pure mechanical. Move 40 prod + 24 test files; update 90 imports + 4 FQN usages. *Defer the `FlowRoutes`
  class rename to Phase C (it's tied to the route swap).* Verify: `mvn -o test` green.
- **Phase B — `JobType.FLOW` → `PIPELINE`.** Constant + javadoc + error-message list; `FlowJobRunner.type()`
  → `PIPELINE`; `JobService` guard; `from()` now parses `type: pipeline`; update 22 test refs. Verify.
- **Phase C — route swap + FE re-alignment.** (1) `PipelineRoutes` → `RunRoutes`, prefix `/pipelines` →
  `/runs`. (2) `FlowRoutes` → `PipelineRoutes`, prefix `/flows` → `/pipelines`. (3) Update
  `ControlApi.java` registration order. (4) FE: repoint the editor service from `/flows` → `/pipelines`
  and the ingest service from `/pipelines` → `/runs`; update the mock-interceptor regexes
  (`flow-mock.interceptor.ts` + whichever mock serves ingest/runs). Verify backend `mvn -o test`,
  then FE `lint:tokens`/`build`/`test:ci` + **live `/pipelines`+`/runs` smoke-test against the real or
  mock backend**.
- **Phase D — docs.** GLOSSARY §13: mark Flow→Pipeline backend ✅ and Metric→Measure backend "no-op";
  drop the "(UI DONE / backend deferred)" caveats. Update `docs/PROJECT_NOTES.md` module map
  (`com.gamma.flow` → `com.gamma.pipeline`). Update this plan's status to DONE.

## Notes / flagged (not silently changing)

- **Pre-existing inconsistency (do NOT fix as part of this pass unless asked):** the `job.type` error
  message at `config/spec/ConfigSpecs.java:246` lists `enrich|report|maintenance` and already **omits
  `flow`** — a latent bug independent of this rename. Flagged for a separate decision.
- The FE editor components were already renamed `flows/` → `pipelines/` (UI pass); only their **HTTP
  service paths + mock regexes** remain on the old endpoints — that's the Phase C FE work.
- No `META-INF/services` SPI files are affected by any rename.

## Commit / release

Per decision #3: land on `master`, **no version bump, no tag**. Classify commits as `refactor(...)`
(not `refactor!`) — there is no released contract being broken. Push only on explicit user go, after
confirming the merge-forward set (release-workflow skill).
