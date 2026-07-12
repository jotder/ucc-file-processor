# Consolidated Backlog — every pending item, one page

**Updated:** 2026-07-09 · **Owner:** whole team (update at every handoff that closes/opens an item)

> **What this is.** The single index of ALL pending/deferred/open work, consolidated from
> `REQUIREMENTS.md` §5 (the product MoSCoW of record), every `docs/superpower/*` plan,
> `flow-graph-design.md` §14, `FEATURE_INVENTORY.md`, review sheets, and the live session/handover
> notes. **Detail stays in the linked source doc** — this page only tracks *that* an item is open,
> its one-line scope, status/blocker, and where the truth lives. Duplicate IDs across plans have
> been collapsed (see §8).
>
> `docs/superpower/backend-backlog.md` remains for its item detail but is now indexed here.

---

## 1. In-flight repo state (act first, before any new work)

| Item | Status | Source |
|---|---|---|
| AGT-5 P0 **hardening pass** (RAG corpus, SSE streaming, nav auto-derivation) | **COMMITTED & PUSHED** (verified 2026-07-09: working tree clean, `master`==`origin/master` @ `7b6c7db`) | `SESSION_STATUS.local.md` |
| AGT-5 P0 spine `f2f9506` + earlier chain (`c06bf7e`…`23cc08c`, `e67bea0`, `3afe606`) | **PUSHED** (verified 2026-07-09: `master`==`origin/master` @ `7b6c7db`) | `SESSION_STATUS.local.md` |
| `ControlApiConnectionsTest.java` | Another session's deliverable — **hands off, do not commit** | `SESSION_STATUS.local.md` |
| Full `package.ps1` bundle (npm+jlink) not re-run since the `spaces/` migration | Smoke-test the built artifact before any release | `HANDOVER-multi-space.md` |

**FIXED (2026-07-10) — space MDC silently fell back to the `default` space (cross-space data leak).**
Root cause was **not** application code: `.claude/launch.json`'s hand-rolled `inspector-backend`
classpath pinned `slf4j-simple-2.0.9.jar`, whose `SimpleServiceProvider` wires a `NOPMDCAdapter` (a
complete no-op — confirmed via `javap`: `MDC.put` followed immediately by `MDC.get` on the same
thread returned `null`). `inspecto/pom.xml:53-65` already replaced `slf4j-simple` with
`logback-classic` at 4.2.0 for exactly this reason and explicitly excludes it transitively — the
Maven-built artifact was never affected, only this dev launch config. Every `/spaces/{id}/...`
request was silently served by the `default` space's `SpaceContext` (`orElseGet(spaces::current)`
in `ControlApi.currentContext()`) regardless of the id in the URL. This also explains the
`/spaces/demo/enrichment` 404 filed earlier — same bug, not a missing route (confirmed 200 after
the fix). **Fix applied:** regenerated `.claude/launch.json`'s classpath via
`mvn dependency:build-classpath` (now `logback-classic 1.5.18` + `slf4j-api 2.0.17`, no
`slf4j-simple`). Verified: `/spaces/demo/jobs` now returns demo's real 5 jobs, `/spaces/demo/connections`
vs `/spaces/default/connections` now return genuinely different per-space data.

**DONE (2026-07-11) — `/connections/test` (unsaved-profile probe) implemented.** Was the one
remaining documented scaffolding gap ("until the real library + control routes land (B2)" —
`environment.ts` comment): `ConnectionRoutes.java` gained `POST /connections/test?target=connection|tunnel`,
testing an unsaved profile straight from the create/edit form (no persistence, no capability gate —
a read-only network probe like the saved-profile test). `target=connection` always probes the target
host directly even when a tunnel is configured (bypasses `ConnectionProfile#testEndpoint()`'s
tunnel-priority); `target=tunnel` requires a tunnel block and probes its host; `target=proxy` 422s
(proxy isn't a `ConnectionProfile` field yet — a smaller, separate gap, not fixed here). Live-verified
via curl (host-vs-tunnel hop selection confirmed with distinct ports) and the real "New connection"
dialog's Test connection button — the exact reported 405 now returns 200. Reactor 1143/0/0/3, no regressions.

### Component CRUD audit (2026-07-10, mocks-off real-backend drive)

Live-tested create/view/edit/save/delete per component against the real backend (after the MDC fix
above). Table: component | GET | POST | PUT | DELETE | notes.

| Component | GET | POST | PUT | DELETE | Notes |
|---|---|---|---|---|---|
| Connections | ✓ | ✓ | ✓ | ✓ | **Live-verified full CRUD cycle** (create/edit/delete all round-tripped correctly, scoped to the right space). Only the unsaved-profile test route is missing (above). |
| Expectations | ✓ | ✓ | ✓ | ✓ | **Live-verified create + delete** (`ExpectationRoutes.java:44-51`). Full CRUD wired. |
| Jobs (Scheduler) | ✓ | ✓ | ✓ | ✓ | **DONE (2026-07-10)** — was a real gap (see below), now closed: `JobRoutes.java` gained `POST /jobs` / `PUT /jobs/{id}` / `DELETE /jobs/{id}`, writing `<space>/config/jobs/<name>_job.toon` and hot-registering on the live `JobService` (`JobService.upsertJob`/`removeJob`, new; `JobConfig.toMap()`, new) — no restart needed. `SourceService.jobServiceOrCreate()` lazily builds a `JobService` for a space that had zero jobs before the first write. Live-verified via curl + the real UI dialog (create/update/delete all round-tripped); reactor 1143/0/0/3, no regressions. Known limitation: `Scheduler` has no cron-cancel primitive, so a deleted job's recursive timer keeps ticking as an inert no-op (self-checks `jobs.containsKey` before firing) rather than truly unscheduling — acceptable, documented in code, revisit if job churn ever gets heavy. |
| Enrichment | ✓ (views/runs/lineage/report) | ✗ | ✗ | ✗ | Read-only stub (`EnrichmentRoutes.java:18-22`). Covered by the `mockOps` comment — documented, not a regression. |
| **Decision Rules** | ✓ | ✓ | ✓ | ✓ | **DONE (2026-07-10)** — was a genuine gap (no route class existed at all, distinct from `AlertRoutes`'s `/alerts/rules` per `docs/GLOSSARY.md:150`), now closed: new `DecisionRoutes.java` (`/decision-rules*`), persisted as a `decision-rule` component (widened `ComponentStore.WRITABLE_TYPES` + `ComponentRegistry.TYPE_BY_DIR`) — same store/gates as `ExpectationRoutes`. `simulate` is an honest dry-run stub (0-matched; no condition-tree evaluator exists yet, client or server side — matches the mock reference implementation's own scope, which also doesn't evaluate `when` for real). `apply` executes each consequence against whatever real primitive exists: `emit-signal` → this space's Signal Ledger, `start-job` → `JobService.triggerRun`, `trigger-pipeline` → `SourceService.triggerRunAsync` (both **live-verified actually firing**, not stubs); `create-alert`/`render-widget`/`generate-report`/`invoke-api` emit a descriptive stub signal (mock parity); routing actions (`route`/`tag`/`quarantine`/`drop`) are record-level with no immediate side effect until a real evaluator exists. Live-verified full CRUD + simulate + apply via curl and the real UI form; reactor green. |
| Alert Rules | ✓ | ✓ | ✓ | ✓ | Full CRUD already shipped 2026-07-09 (`AlertRoutes.java:33-47`) — unaffected by the above; just don't confuse with Decision Rules. |
| Studio Components (dataset/chart/dashboard) | ✓ | ✓ | ✓ | ✓ | Full CRUD + versions/restore wired (`ComponentRoutes.java:30-38`). The `mockStudio` environment.ts comment ("gated pending backend storage enum widened") is now stale — routes are complete; safe to leave `mockStudio: false`. |
| Pipelines / Flows editor | ✓ | ✓ | ✓ | ✓ | Full CRUD + dry-run + node/edge mutation wired (`PipelineRoutes.java:35-48`). |
| Exchange (cross-space) | ✓ | ✓ (lifecycle actions) | — | — | No PUT/DELETE verbs, but approve/deny/revoke/pin/expiry are POST-based lifecycle actions by design — not a gap. |
| Spaces | ✓ | ✓ | ✓ | ✓ | Full CRUD wired (`SpaceRoutes.java:42-57`). |

**Remaining action item from this audit:** `mockStudio`'s environment.ts comment is stale and should
be updated to reflect that Studio CRUD is fully live (Jobs and Decision Rules gaps above are now closed).

## 2. MUST — release-gating remainder (product)

| ID | Item | Status / blocker | Source |
|---|---|---|---|
| ACQ-4 | Object-storage connectors — **open: Azure Blob native, GCS-native APIs** (S3/MinIO/GCS-interop shipped; NFS/SMB stays mounted-share) | PARTIAL | `REQUIREMENTS.md` |
| SEC-7 | RBAC/ABAC — **open: data-scoped grants** (= rbac-groundwork Q2 = SEC-7d) | Blocked on product decision | `REQUIREMENTS.md`, `superpower/rbac-groundwork.md` §4, `superpower/resource-permissions-design.md` §5 |
| EOI-7 | eoiagent `0.1.0` release: cut+pin version, publish artifacts (today: `0.1.0-SNAPSHOT`, local-`.m2` only — CI cannot resolve) | PLANNED; infra/product call | `REQUIREMENTS.md`, `superpower/agent-kernel-replacement-plan.md` §open-items |

## 3. SHOULD (product)

| ID | Item | Status / blocker | Source |
|---|---|---|---|
| DAT-4 | ~~**Matrix materialization**~~ (`materialize` job task) | **DONE** (verified 2026-07-10, shipped 2026-07-08 per code: `MaterializeTask.java` — `task: materialize` on the maintenance runner; BI-7 spec-compiled SELECT/raw snapshot → `COPY TO` Parquet with PIP-7's atomic swap; target registered as a normal `dataset` component) | `REQUIREMENTS.md` DAT-4 |
| DAT-5 | ~~**Row-level calculated columns**~~ | **DONE** (2026-07-10) — backend shipped 2026-07-08 (`ExpressionGuard.java`/`DatasetRelation.java`, fail-closed 422); **UI authoring shipped 2026-07-10**: `DatasetCalculatedComponent` on the dataset editor (name/expr rows, offline AlaSQL Test preview, inline `calculated-column-guard.ts` mirroring the backend's 3 rules for instant feedback — not authoritative, server re-validates at query time). `studio-bi-improvements-plan.md`'s "no calculated-columns UI" weakness is now resolved | `REQUIREMENTS.md` DAT-5, `superpower/calculated-columns-design.md` |
| — | ~~**Alert-Rule write endpoints**~~ (`POST/PUT/DELETE /alerts/rules`) | **DONE** (2026-07-09) — `AlertRoutes` CRUD, `canAuthorAlertRules`-gated, writes `<name>_alert.toon` + arms the (now always-present) `AlertService` in-process; `ControlApiAlertRuleWriteTest` 7/7, reactor 1136/0/0/3 | `superpower/backend-backlog.md` §4, `superpower/alert-rule-authoring-plan.md` |
| — | ~~**Widget-Library M2**~~: `DatasetResultService` M2 form, materialized-dataset refresh | **DONE** (2026-07-10) — `DatasetResultService` runs `POST /bi/query` (new `BiQueryService`) when Studio is live (`mockStudio` false), offline AlaSQL byte-identical otherwise; unmappable specs (named-measure SQL, OR filters) fail honestly. Refresh/delivery covered by DAT-4 + BI-4. Sharing/RBAC stays SEC-7-gated (never M2-owned) | `superpower/backend-backlog.md` §1, `superpower/widget-library-spec.md` §7 |
| — | ~~**Job templates**~~ (backend) | **DONE** — verified 2026-07-10: `JobTemplate.java` exists (`*_job_template.toon`, `${param}` substitution); REQUIREMENTS PIP-6 SHIPPED 2026-07-08 was correct, `backend-backlog.md` §3's "no template concept exists" was the stale side — reconciled | `REQUIREMENTS.md` PIP-6 |
| INC-4 | Incident workflow depth (queues/escalation/watchers) | Blocked on product design | `REQUIREMENTS.md` |
| ACQ-5 | Streaming (Kafka) source consumer | **Hard-blocked offline** (no client jars in `.m2`) | `REQUIREMENTS.md` |
| API-5 | Legacy route sunset — gated on `inspecto_legacy_api_requests_total` soak signal (= W7 deferred follow-ons) | Policy call | `REQUIREMENTS.md`, `superpower/w7-ui-v1-migration.md` |
| OPS-5 | Provenance conservation on live data (built, off by default) | Needs live verification | `REQUIREMENTS.md` |

## 4. COULD (product / feature phases)

| ID / plan | Item | Status | Source |
|---|---|---|---|
| AGT-5 P1 | Investigation: timeline/diff/anomaly tools, `root_cause_analysis`, Case Store, event ingress | Open (P0 shipped+hardened) | `superpower/embedded-intelligence-plan.md` §8 |
| AGT-5 P2–P5 | Author-everything (L1) → gated action (L2) + approvals inbox → bounded autonomy (L3) → learning | Open | same |
| AGT-5 cuts | QA-only (`incident_explain` waits on eoiagent host seam); local-models-only (no hosted-provider companion) | Open scope cuts | same |
| AGT-6 | AI behind every screen / agent graphs | PLANNED | `REQUIREMENTS.md` |
| BI-6 / BI-8 | Embed viewer UI; template gallery/browsing UI | **DONE** — BI-6 fully shipped incl. the in-app **Share dialog** (`e6ea167`, 2026-07-09: `ShareDashboardDialog` mints `POST /dashboards/{id}/share`, shows `/share/{token}` + copy/expiry); BI-8 gallery shipped 2026-07-08 | `REQUIREMENTS.md` |
| MET-5 | ~~Component draft/published version history~~ | **DONE** (2026-07-09) — `ComponentStore` archives prior copies under `<typeDir>/.history/`, keep-N; `GET …/versions` + `POST …/versions/{v}/restore`; reusable `ComponentHistoryDialog` + History button (dashboard editor); mock mirrors it. Reactor 1139/0/0/3. **Follow-on DONE 2026-07-10**: History wired into the dataset editor, widget Explore editor, Query Library rows, and the Workbench components rows. **Expectations DONE 2026-07-10**: the expectations mock was unified onto `component:expectation` (backend already shared `ComponentStore`), so the generic version-history routes serve it offline — History is now a Builder-lens row action on the Expectations pane; a config edit archives a version, a run-check `lastResult` stamp does not (`ComponentStore.write(…, archive=false)`). Still deliberately excluded: requirements (decision workflow, not content editing), link/geo saved-view menus (thin config rows) | `REQUIREMENTS.md` |
| SPC-5 | Per-tenant ABAC (rides SEC-7 grants model) | PLANNED (E) | `REQUIREMENTS.md` |
| E1 | Enterprise distributed tier / Stage-2 streaming | Demand-gated | `REQUIREMENTS.md` |
| Link-analysis V1 | Multi-entity mapping, multi-root, incremental expand, all-paths, layout save, widget/dashboard placement, SVG/GraphML export, undo/redo; design §7 `attrCols` + schema-relationship model | Open | `superpower/link-analysis-studio-plan.md` §6–7 |
| Link-analysis V2+ | Advanced traversal, timeline, scoring, collaboration (backend-blocked); backlog: algorithm library, AI assist, pattern packs, geospatial, scale | Open | same |
| Geo-map Phase 4 | Backend+perf: DuckDB spatial extension, server-side projection/aggregation endpoint, `ComponentStore` view-kind widening (geo/link), progressive loading, worker binning | Open (UI phases 0–4c done) | `superpower/geo-map-analysis-plan.md` |
| Flow T15 | Adaptive back-pressure defaults configurable | Not started | `flow-graph-design.md` §14 |
| Flow T16 | `GET /pipelines/{id}/graph` → G6 renderer projection | Not started | same |
| Flow T17 | Node inspector panel + live last-run overlay | Not started | same |
| Flow T19 | **Flow-topology editor** (node/edge CRUD on G6 canvas) + wiring authored flows into live executor | Partial (CRUD backend done) | same |
| Flow T32 | Dedicated `POST /pipelines/authored/{id}/run` (config-less ad-hoc run); `sink.view` `derived_sql` follow-up | Deferred (phases A–C done) | `flow-live-execution-plan.md` |
| Menu builder | Slices M1–M5; open points O1 (who curates), O2 (icons), O3 (seed example) | **DRAFT, awaiting sign-off** | `superpower/menu-builder-plan.md` |
| System Maintenance module | MoSCoW adapted to Inspecto (MNT-1…16), all 3 phases **SHIPPED 2026-07-12**: P1 dry-run/retention/storage-report/scheduler-audit · P2 backup/verify/restore + `metadata_validate` + all-store `db_maintenance` + `min_keep` (runbook `docs/ops/backup-restore-runbook.md`) · P3 nightly guarded signal chain + chain-step Job Template, `/health/details`, `file_repository_audit`, bundle-import integrity gate (`ComponentIntegrity`), **System Maintenance nav group + Overview pane**. Remaining: MNT-14 Archived-Incident sweep (**blocked** on the incidents backend workflow — lifecycle is mock-only, no ObjectStore delete); COULD tier (trends, space comparison, profiles, predictive/AI/self-healing) | **P1–P3 done; MNT-14 blocked; COULD open** | `superpower/system-maintenance-plan.md` |
| Query kind | Structured-query editor UI (SQL-only today); graph/spatial/search/api `QueryType`s not built; more `$`-resolvers | Follow-on | `superpower/query-kind-plan.md` |
| Signal network | Raw `/signals` endpoint + `SignalsService`; backend Signal contract | Follow-on (UI/mock shipped) | `superpower/signal-network-plan.md` |
| Decision network | Expectation/Alert-Rule ComponentKind promotion; real platform-action execution (mock today); LLM-backed Assist proposal | Deferred cuts | `superpower/decision-network-plan.md` |
| Studio BI | Remaining Phase A/B/C items (filter bar, drill-through, time-grain, Measure builder, viz plugins, PNG export, chart a11y, KPI & Reports gallery) — overlaps BI-3/DAT-5; **verify what remains** | No status header | `superpower/studio-bi-improvements-plan.md` |
| Component model | Verify P2 adapters / P3 registry view (menu placement + gating deferred to security module) / P4 formatter consolidation completion | Verify | `superpower/component-model-adoption-plan.md` |
| Transportability | `requires` present-but-different classification; bespoke editor draft-load; connection/pipeline/job/view bundle kinds (deferred to own stores) | Deferred cuts | `superpower/transportability-plan.md` |
| Multi-space leftover | `SpaceManager.delete` doesn't tear down per-space ConnectionRegistry/StabilityGate entries (`forget(spaceId)`) | Minor, fix if it bites | `HANDOVER-multi-space.md` |
| Dataset glob vs flow sink nesting | **Design flaw found by UAT volume seeding (2026-07-12):** a `physicalRef` Dataset reads `<dataRoot>/<ref>/**/*.parquet` recursively (`DatasetRelation.baseRelationSql`), but the authored-flow `sink.persistent` for `orders_rollup_flow` writes its `rollup` store INSIDE the source store's tree (`data/orders/rollup/`), so the Dataset silently double-counted rows (measured: +72% ≡ the SHIPPED filter's pass rate; engine batches were exact — 59 batches, in=out=20,691). Symptom-fixed by pointing the demo's `orders_dataset` at `orders/database`; the open question is the real contract: should flow sink stores resolve under the data root (per `PartitionSinkWriter`'s `<dataDir>/<store>` doc) instead of nesting, and/or should dataset globs default to the canonical `database/` subtree? | Open — decide the contract, then align engine/docs/demo | `tools/seed-uat.ps1` forensics · `docs/ops/uat-seeding.md` |
| Exchange UI | ~~**Bind-a-shared-dataset flow**~~: consumer creates a local dataset whose `physicalRef` = a granted `shared/<owner>/<item>` ref. | **DONE** (2026-07-09) — `BindSharedDatasetDialog` on the Datasets page: lists a space's active dataset grants, binds the chosen one as a `physical` dataset. Scope badge exercised live via mock; against a real backend the widget "Access revoked" empty-state now has a producing path. | `superpower/storage-layout-and-sharing-plan.md` §3.6 · `SESSION_STATUS.local.md` |
| Exchange UI | ~~**Sharing-grid row-action ergonomics**~~: action column sat behind horizontal scroll on narrow panes. | **DONE** (2026-07-09) — new opt-in `<inspecto-data-table [pinActions]>` pins the row-actions column right (`actionsColumn(…, pinned)`); enabled on all four sharing grids. Also fixed a latent gap: the "Shared with me" grants grid had no `[rowActions]` bound, so the consumer **pin** action was unreachable — now wired. | `SESSION_STATUS.local.md` |

## 5. Engineering / tech-debt backlog

The full engineering MoSCoW (build hygiene, `SourceService` decomposition, `agent.spi` facade,
Fuse-leftover removal, reactor split, shutdown robustness, `@PublicApi` freezing, etc.) lives in
**`superpower/modularization-optimization-plan.md`** §4 — not duplicated here. Headline Musts:
M1 parent `dependencyManagement` · M2 `SourceService` decomposition · M3 `agent.spi` facade ·
M4 UI Fuse-leftover removal (~25.8k lines) · M5 coverage baseline (intelligence/agent-hosted) ·
M6 repo-clutter sweep (incl. `file-processor-deploy-old/`, root logs, `.iml`).

## 6. Security-module scope (deferred wholesale)

Identity/login, user model, role-assignment UI, Admin pane, server enforcement, lens-switcher
constraint, data scoping — all explicitly deferred to the security module by
`superpower/rbac-groundwork.md` §5. Do not partially implement these elsewhere.

## 7. Docs & open product questions

| Item | Status | Source |
|---|---|---|
| User-guide audit P1: #8 KPI authoring + Measure-reuse doc; #10 quarantine remediation/replay doc (or file product gap "D-ETL") | Open | `superpower/reviews/user-guide-audit.md` |
| User-guide audit P2: #11 de-jargon ("pro/pro max" C8, "Stage-2" C9); #12 parser format-list alignment (C11) + GLOSSARY §6-B Matrix tense (C5) | Open | same |
| Open product questions R4/R6/R8: data-scoped grants, `canOnboardConnections` split, sunset timing, structured queries client-compiled | Awaiting product | `REQUIREMENTS.md` §7 |
| Interview backlog Qs: #2 parser required-vs-advanced, #5 Incident/Case mandatory fields + assignment model, #6 template scope, #7 KPI target ownership | Awaiting product | `superpower/frontend-review-and-completion-plan.md` §6 |
| rbac-groundwork Q1 (`canOnboardConnections` → Admin), Q3 (Requirements triage grant set), Q4 (Requirements SLA — declined, revisit) | Awaiting product | `superpower/rbac-groundwork.md` §4 |
| FEATURE_INVENTORY gaps: LDIF `record_split "\n\n"` `[PROPOSED]`; structured/`text_regex` block records; no `*_flow.toon`/`*_rca.toon` example files; no subscriber `.dat` sample; `package.ps1` pre-creates dirs only for adjustment+voucher | Open (snapshot 2026-06-20 — verify against code) | `FEATURE_INVENTORY.md` |
| NFR-7 compliance certifications | PARTIAL (not started) | `REQUIREMENTS.md` |

## 8. Duplicate map (same work, multiple IDs — always update all listed sources when closing)

| Canonical | Also recorded as |
|---|---|
| DAT-4 Matrix materialization — **DONE** | backend-backlog §2 (stale, not updated) · ia-vocabulary-reorg Phase C |
| DAT-5 calculated columns — **DONE** (backend + UI) | frontend-completion C8 · studio-bi Phase B |
| Job templates — **DONE** (= PIP-6) | backend-backlog §3 (stale, not updated) · ia-vocabulary-reorg Phase D |
| SEC-7 data-scoped grants | rbac-groundwork Q2 · resource-permissions SEC-7d · prerequisite of SPC-5 |
| API-5 legacy sunset | w7-ui-v1-migration deferred follow-ons |
| EOI-7 eoiagent release | agent-kernel-replacement §open-items · SESSION_STATUS eoiagent blocker |
| Alert-Rule backend endpoints | alert-rule-authoring §Backend · backend-backlog §4 |
| Geo-map Phase 4 backend | INV-2 Phase 4 · ComponentStore view-kind widening |

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays
authoritative), then delete or strike the row here. New pending items discovered mid-shift get a
row here at handoff time (see the `handoff` skill).
