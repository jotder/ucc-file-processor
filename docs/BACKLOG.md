# Consolidated Backlog — every OPEN item, one page

**Updated:** 2026-07-16 (doc-consolidation refresh) · **Owner:** whole team (update at every handoff
that closes/opens an item)

> **What this is.** The single index of ALL pending/deferred/open work: one line + status + where the
> detail lives. Detail stays in the linked source doc. **Shipped work is not recorded here** — it
> lives in git history, `REQUIREMENTS.md` status columns, and the archived plans under
> [`archived-documents/plans-archive/`](archived-documents/plans-archive/).
>
> **Where we are (2026-07-16):** roughly **75% of the chartered scope has shipped** — the
> REQUIREMENTS MUST + SHOULD engineering backlogs are empty; what remains is the COULD tier,
> externally-gated items (deployment, product decisions, the security module), feature follow-ons,
> and polish. Compiled from REQUIREMENTS §5 + the deferral sections of every archived plan
> (2026-07-16 sweep) + the live session notes.

---

## 1. Act first / in-flight repo state

| Item | Status | Source |
|---|---|---|
| Full `package.ps1` bundle (npm+jlink) not smoke-tested since the `spaces/` migration + bundle-prune fix | Smoke the built artifact before any release | `HANDOVER-multi-space.md`, `SESSION_STATUS.local.md` |

## 2. Product remainder (MoSCoW of record: `REQUIREMENTS.md` §5)

| ID | Item | Status / blocker |
|---|---|---|
| ACQ-4 | Object-storage connectors — S3/MinIO/GCS-interop + SDK-free Azure Blob shipped; **open: GCS-native API** | Offline-blocked (no SDK jars) |
| OPS-5 | Provenance conservation on live data (built, off by default) | Needs a live deployment to verify |
| NFR-7 | Compliance certifications | PARTIAL (not started) |
| API-5 | Legacy route **physical deletion** — sunset mechanism + `inspecto_legacy_api_requests_total` meter shipped (W8); delete after the signed **30-days-at-zero soak** | Soak-gated policy call |
| EOI-7b | Publish eoiagent `0.1.0` artifacts to a registry (v0.1.0 cut + pinned 2026-07-08; CI rebuilds from tag meanwhile) | Infra/product call |
| BI-4 | Scheduled report **delivery**: CSV export ships as a `type:'report'` Job; **real PDF/PNG rendering** is placeholder text | Backend/Standard scope |
| AGT-5 P1 | Investigation: timeline/diff/anomaly tools, `root_cause_analysis`, Case Store, event ingress | Open (P0 shipped + hardened) — `superpower/embedded-intelligence-plan.md` §8 |
| AGT-5 P2–P5 | Author-everything (L1) → gated action (L2) + approvals inbox → bounded autonomy (L3) → learning | Open — same |
| AGT-5 cuts | QA-only (`incident_explain` waits on the eoiagent host seam); local-models-only | Open scope cuts — same |
| AGT-6 | AI behind every screen / agent graphs | PLANNED |
| SPC-5 | Per-tenant ABAC (rides the SEC-7 grants model; absorbs per-resource ACLs/ownership) | PLANNED (Enterprise) |
| E1 | Enterprise distributed tier / Stage-2 streaming | Demand-gated |
| INV-1 | Entity Projection backend (Link Analysis is UI/mock-projected today) | Open |

## 3. Feature follow-ons (deferral sections of shipped work)

> _Reconciled against live backend routes 2026-07-18. Struck as already-shipped: `/settings/branding`
> (→ removed the Branding row), `/signals`, sink→`ViewStore` auto-registration, legacy-alias usage logging.
> Grep-verified **genuinely un-implemented** (leave open): Email/SMTP channel, `/notifications/channels*`
> CRUD, a backend navigation endpoint, `graph`/`spatial`/`search`/`api` QueryTypes, a `recon.run` Job +
> Alert Rule→Incident wiring, workflow-override boot-scan into `ObjectService`, cursor pagination, and an
> enrichment sample-preview endpoint._

| Area | Open items | Source (detail) |
|---|---|---|
| **API v1** | X-Actor full retirement on Standard · UI sign-out affordance · UI↔backend `ContentHash` float-parity conformance test · ETag beyond components+bootstrap · cursor pagination *(2026-07-18 SHIPPED the mechanism + first adopter: opaque keyset cursors via `metadata.pagination` `{cursor,nextCursor,limit,total}` — `ApiContext.pagination` seam + `Envelope` emit + `com.gamma.control.Cursor` (decode-total, URL-safe Base64/JSON) on `GET /jobs/runs` over `DbJobRunStore` (new keyset `recentRuns`+`countRuns`); v1-only, legacy list unchanged; `okf/.../api-v1.md`. 2026-07-19 SHIPPED second adopter `GET /objects` — keyset over the SEC-7d-visible set, in-route not
SQL-side, since objects are low-volume and the visibility filter would otherwise leak/mis-size `total`.
Remaining: adopt the seam on other list families as demanded)* · Standard-edition jlink runtime vs Nimbus not re-verified (`-NoRuntime` until confirmed) | `archived-documents/plans-archive/api-contract-design.md` |
| **Bundle / Exchange** | *(2026-07-18 SHIPPED: `authored-pipeline`/`job`/`saved-view` now bundle-eligible via a uniform `BundleSource` seam — job import hot-registers via `upsertJob`; as-built in `okf/backend/control-plane/metadata-bundle.md`.)* `BundleRoutes` remaining missing kind: `connection` (secret-in-bundle policy call unmade) · `requires` present-but-different classification · per-editor "load as draft" import · **UI Exchange-surfaces track §3.6** (catalog Shared-with/by-me, offer flow, scope badges, pin/drift; backend + `bootstrap.features.exchange` ready) | `superpower/storage-layout-and-sharing-plan.md` (ACTIVE) · `archived-documents/plans-archive/transportability-plan.md` |
| **Job framework** | Pack in-flight-Run quiesce · `args`/`bind` type-inference · MNT-14 Archived-Incident sweep (**blocked** on backend Incident lifecycle + `ObjectStore` delete) · maintenance COULD tier (trends, space comparison, predictive) · `Scheduler` has no cron-cancel primitive (deleted job's timer ticks as inert no-op — revisit if churn bites) *(2026-07-18 §3-reconcile: struck "sink→`ViewStore` auto-registration" — already shipped, `PipelineJobRunner.registerViews` writes a `ViewDefinition` per `sink.view` node after every flow run)* | `okf/backend/control-plane/jobs.md` · `archived-documents/plans-archive/system-maintenance-plan.md` |
| **Queries / BI** | Structured-query editor UI (SQL is the only authoring surface) · `graph`/`spatial`/`search`/`api` QueryTypes · more `$`-resolvers · ~~responsive dashboard tiles (ResizeObserver re-render)~~ *(2026-07-19 SHIPPED: `InspectoChartComponent` now observes its host box and calls `chart.resize()` on container-only changes — tile span toggle, side-pane collapse, flex reflow — mirroring the GraphView/MapView RO pattern; Chart.js `responsive` only covered window resize)* · dataset/widget/dashboard **sharing RBAC** (SEC-gated, `inspecto-security`-owned) · calculated-columns v2 = whitelist growth only (window/aggregate fns) | `archived-documents/plans-archive/query-kind-plan.md` · `studio-bi-improvements-plan.md` · `calculated-columns-design.md` |
| **Notifications** | Email channel impl + delivery-status webhooks · digest batching · time-based retention sweep · GeoIP · auth-gated per-user prefs/security triggers *(2026-07-18: **channels admin CRUD** SHIPPED backend — `GET/POST /notifications/channels` + `PUT/DELETE /notifications/channels/{id}` persist a `channel` ComponentStore kind (`ChannelConfig`); 503/422/409/404 gates, per-space, `canAuthorWorkbench`. 2026-07-19 SHIPPED: **persisted channels wired into dispatch** — `NotificationService.dispatch` delivers to enabled `ChannelConfig` entries through the matching SPI transport via a new `deliver(n, target)`; the "admin config only, inert" gap is closed. Remaining: `ChannelConfig` has no `template` field — that rides the Signal Backbone plan's S2)* | `archived-documents/plans-archive/notification-system-and-audit-trail-plan.md` · `okf/backend/control-plane/events-metrics.md` |
| **Signal / Decision networks** | *(2026-07-18: raw `/signals` read endpoint was already live (`SignalRoutes` + static `Signals.query`); completed its query surface — added in-store `until` + `severity`-floor filters (400 on a bad severity) and the first test coverage (`SignalsTest` + `ControlApiSignalsTest`). No stateful `SignalsService` object — house style is the static utility called straight from the route, like `/events`. Minor remaining: no `source` filter; correlationId returns a flat chain (no server-side tree assembly).)* · LLM-backed Assist proposal *(2026-07-18 SHIPPED end-to-end: real condition-tree evaluator `ConditionTree` — decision-rule `simulate` evaluates `when` over `sampleRows` with query-eval.ts parity (backend); the **UI** Simulate action fetches a bounded sample from the target store via `/db/table` and sends it (preview-verified: real `total`, not the stub); the **mock** `decision-rules.handler.ts` simulate now evaluates `when` over `sampleRows` (`evaluateRows`), demo counts as no-sample fallback. 2026-07-18 also SHIPPED: routing consequences `route`/`tag`/`quarantine`/`drop` now apply during live pipeline runs (`DecisionRuleApplier` in `writeAndTrace`, `ConditionSql` predicate compiler, per-space `DecisionRules` registry; `okf/backend/control-plane/decision-rules.md`), and the "GROSS simulates 0" finding was root-caused to the db-browser globbing the raw `backup/` tree instead of the pipeline's `dirs.database` output — fixed, plus the demo threshold recalibrated to a reachable `GROSS > 100`. 2026-07-18 also SHIPPED: both recorded deferrals — `targetType: job` rules now check `sql.template` job output (pre-snapshot, in `SqlTemplateJob`) and Stage-2 enrichment output on every recompute trigger (in `EnrichmentEngine`, matched by enrichment name + wrapping job name), via the generalized `DecisionRuleApplier.Subject`/`RouteSink` seam). 2026-07-18 also SHIPPED: **Expectation/Alert-Rule ComponentKind promotion** — Alert Rule moved off raw `*_alert.toon` files onto `ComponentStore` (`alert-rule` kind, same CRUD contract as Expectation/Decision Rule); Expectation gained a `condition` kind (`when` tree as the violation predicate, via `ConditionSql`); Alert Rule gained an optional `when` row-scoping filter over ledger rows (via `ConditionTree.filter`, new alongside `matched`). UI: both dialogs wire `<inspecto-query-condition-group>` (a new `AttributeSpec.dependsOn.notEquals` variant hides Expectation's `column` for the `condition` kind); live-verified end-to-end against the real backend (real `orders` columns probed, real `GROSS>100` → 1 violation; real alert-rule persisted to `registry/alert-rules/*.toon`). Full reactor 1567/0/0/3, UI test:ci 1403/0/5)* · *(2026-07-19 SHIPPED: `create-alert` consequence now opens a deduped `INCIDENT` for `critical`/`error` severities instead of a signal-only stub; see `decision-rules.md`)* | `archived-documents/plans-archive/signal-network-plan.md` · `decision-network-plan.md` · `okf/backend/control-plane/decision-rules.md` |
| **Link analysis** | V1: multi-entity mapping, multi-root, incremental expand, all-paths, layout save, widget/dashboard placement, SVG/GraphML export, undo/redo, `attrCols` + schema-relationship model · V2+: advanced traversal, timeline, scoring, collaboration, algorithm library, AI assist, pattern packs | `archived-documents/plans-archive/link-analysis-studio-plan.md` §6–7 |
| **Geo map** | Phase 4 backend: DuckDB spatial, server-side projection/aggregation endpoint, `ComponentStore` widening for geo/link view kinds, progressive loading, worker binning | `archived-documents/plans-archive/geo-map-analysis-plan.md` |
| **Pipeline graph** | ~~T15 adaptive back-pressure *config*~~ → **re-scoped 2026-07-19**: the §3.5 levers are already configurable; the unbuilt part is the *adaptive lag-driven throttling feature* (admission-cap halving + hysteresis), a build not a constant-extraction (design doc T15) · ~~T19 topology-editor wiring into live executor~~ **the live-executor half shipped under T32 (2026-07-18)**; only the G6-canvas node/edge authoring **UI** remains (design doc T19) · mock-only: run-to-here `POST …/run` (path reserved — see below), `/config/icon-map`, `/asn1/modules` *(2026-07-18: T16/T17 checklist corrected — both were already shipped/closed, see the design doc §14 for the as-built; T17's live last-run overlay closed this pass by wiring the existing-but-unused `/provenance` endpoints into the editor canvas + inspector. 2026-07-18 also SHIPPED: the T32 config-less ad-hoc run — `POST /pipelines/authored/{id}/trigger` via `JobService.triggerFlowRun`, a synthetic never-registered PIPELINE config through the full run lifecycle (fence tracking, non-overlap, ledger, `runId` polling); deliberately `…/trigger` not `…/run`, which stays reserved for the editor's scratch-only run-to-here contract. The row's earlier `sink.view` `derived_sql` clause was stale — shipped 2026-06-19 per the design doc §14. 2026-07-18 also SHIPPED T32's last open item — the **UI consumer for views**: a `ViewsService` (`GET /views\|/views/{name}\|/views/{name}/data`) + a "Preview data" action on `sink.view` nodes in the pipeline inspector, opening a `ViewPreviewDialog` (bounded rows via `<inspecto-data-table>`, surfaces the backend's 409 "no derived_sql yet" as an inline error). Mock-backed (`pipelines.handler.ts` `/views*` routes over authored `sink.view` nodes) — T32 is now fully closed)* | `okf/backend/pipeline-graph/pipeline-graph-design.md` §14 · `archived-documents/plans-archive/flow-live-execution-plan.md` |
| **Acquisition / connections** | *(2026-07-18 both prior rows SHIPPED: `proxy` is now a `ConnectionProfile` sub-block — `target=proxy` on `/connections/test` probes the proxy hop; and the connection workbench `probe`/`explore`/`sample` routes are real — `ConnectionWorkbench` SPI + `ConnectionProber`, built-in local impl, SFTP/FTP/FTPS impls in `inspecto-connectors`, as-built in `okf/backend/acquisition/connectors.md`.)* *(2026-07-18 SHIPPED: the `db` workbench — `DbConnectionWorkbench` walks schema/table/column via `DatabaseMetaData` + bounded `SELECT` sample; read-only, WRITE always skipped.)* Open: workbench impls for the remaining connectors (`s3`/`gcs`/`azure`/`kafka`) — adopt the `CollectorConnectorFactory.workbench` hook when demanded · connectors don't dial through a configured `proxy` yet (probe-only) | `okf/backend/acquisition/connectors.md` |
| **Incidents / cases** | I1 backend workflow resolution-gate · `CaseRule` scheduler auto-evaluation · Studio-dataset binding of case analytics · C3 configurable Findings sections + auto member-timeline · first-class `category`/`tags` params on `GET /objects` (low value) *(2026-07-18 SHIPPED: workflow TOON overrides — `ServiceBootstrap` now scans `*_workflow.toon` at boot (both the legacy CLI and per-space `SpaceBootstrap` paths) and installs each via the new `ObjectService.registerWorkflow`, so `GET /workflows/{type}` serves the authored state machine instead of the frozen `Workflow.defaultFor` default; last file wins per type, malformed skipped. `okf/.../operations-reference.md`)* | `archived-documents/plans-archive/case-management-design.md` · `incidents-mail-ui-design.md` |
| **Reconciliation** | *(2026-07-18 SHIPPED the scheduled `recon.run` Job — `ReconRunJob` built-in Job Type runs a saved `reconciliation` on a cron and emits `recon.run.completed` with Break counts (WARNING on breach), building the identical `ReconService.Spec` the route does via the new shared `ReconConfigLoader`; `okf/frontend/features/reconciliation.md`.)* *(2026-07-19 SHIPPED: a breach also opens a deduped `INCIDENT`, one per reconciliation)* explicit non-goals: N>3, non-additive aggs, fuzzy keys | `okf/frontend/features/reconciliation.md` · `archived-documents/plans-archive/reconciliation-board-design.md` |
| **Menu builder** | M5: favorites, polish, a11y, seeded example · real backend navigation endpoint *(2026-07-18 backend SHIPPED: `GET/PUT /nav/menus` (`NavRoutes`+`NavMenus`) persists the frozen `MenuTree` contract as per-space `nav-menus.toon` — settings-doc discipline, whitelist-canonicalizing 422 walk, `space` stamped from the request seam; as-built in `okf/backend/control-plane/control-api.md`. 2026-07-19: UI SHIPPED — `MenuService` hydrates from `GET /nav/menus` on load and write-throughs every mutation via `PUT` (`NavMenusService`); the localStorage mirror is kept for instant paint + the synchronous sidebar merge + offline; a `nav.handler` gives `mockDemo` offline parity. Live-verified end-to-end: server persistence survives a cleared mirror + reload)* *(2026-07-19 SHIPPED: M5 a11y — ARIA tree roles on the builder (role=tree/treeitem/group, aria-selected/aria-expanded) — and a generic opt-in seed example (`MenuStore.seedExample()` + "Load example" button, empty-tree only); O3's Telecom-themed seed and the favorites/`/design` gallery entry stay open)* · open points O1 (curator) O2 (icons) | `archived-documents/plans-archive/menu-builder-plan.md` |
| **Onboarding (Stream/Reference)** | Per-stage `POST /validate` findings → `blocked` chip state · ~~"View as graph" link~~ *(2026-07-19 SHIPPED: onboarding header "View as graph" button → `/catalog?tab=graph&from=stream:|ref:<name>`; the catalog now honours `?tab`/`?from` deep-links (opens the Lineage tab + runs the traversal); the graph lift includes draft pipelines, so it works pre-go-live. Live-verified end-to-end)* · discard: unregister the live registry entry (ghost row ≤60s) *(2026-07-19: the companion-TOON cascade SHIPPED — `discardDraft()` now best-effort deletes `<name>_schema` + `<name>_enrich` after the pipeline; the registry-unregister ghost remains a backend concern)* · enrichment deregister (deleted-on-disk job runs until restart) + schedule-interval change needs restart (`Scheduler` no-cancel, same as the jobs row) · enrichment stage's `Validated` state *(2026-07-18: backend SHIPPED — `POST /enrichment/preview` runs the draft transform over an inline sample via `EnrichmentEngine.preview` (seeds `input`, real reference views, `{columns,rows,truncated}`, persists nothing); `okf/.../onboarding-authoring.md`. 2026-07-19: UI SHIPPED — the enrichment pane's **Preview** button samples the stream's Stage-1 output via `/db/table` and renders `/enrichment/preview` results in a shared `<inspecto-query-panel>`; live-verified end-to-end against the real backend)* · ~~`read_json array|auto` preview timestamp-serialization edge~~ *(2026-07-19 SHIPPED: `jsonSelect` now casts every column to VARCHAR (`COLUMNS(*)::VARCHAR`), keeping an auto-detected timestamp a plain string like every other format; partition-key hint UI-copy also now explains the `year=1900` sentinel — the sentinel itself is a deliberate, documented behavior, left unchanged)* · Reference Phase-2 engine semantics: cache/upsert/SCD versioning + refresh scheduling, row-level dedup, Stream grouping (GLOSSARY §3/§6-B roadmap) · optional templates entry (space-template-gallery precedent) | `okf/backend/control-plane/onboarding-authoring.md` · `okf/frontend/features/onboarding.md` · `archived-documents/plans-archive/stream-onboarding-design.md` |
| **Collector rename residual** | Pipeline TOON config-key `source:` block kept (renaming breaks authored TOON) — separate migration if ever wanted; `'SOURCE'` stage category unchanged | `okf/backend/gotchas/cross-cutting.md` |

## 4. UI residuals (small, valuable)

| Item | Source |
|---|---|
| ui-design-review residuals — R2 column suggestions, R2 object-create chips, R3 command registry + `/`-focus + j/k nav all SHIPPED 2026-07-17; remaining: R6 true offset paging per pane (revisit if a pane outgrows widen-and-refetch) · R8 pivot-bar (design-only until demanded twice) | `archived-documents/plans-archive/ui-design-review.md` · `okf/frontend/log.md` |
| `ComponentKind.deriveParts` seam — formalize when a 3rd composite kind needs it | same |
| Minor: parser/node attribute tiers best-guess pending firm backend specs · pipeline-editor dry-run panel extraction (may be moot) *(2026-07-17 sweep SHIPPED: `<inspecto-chip>` · mock `/alerts/evaluate` real ledger math · live-tail selectable cadence · mock audit trail records authoring mutations — ops-side rule authoring (alert/tag/case rules) can adopt the same `emitAudit` seam if wanted)* | same |
| Dev-mode mount flake: the vendored `GammaLoadingBarComponent` NG0100 (progress −1→0 mid-tick) intermittently aborts the CD pass that activates routed content on fresh loads (dev `checkNoChanges` only — prod unaffected; vendored code, out of audit scope) | observed 2026-07-17 preview walks |

## 5. Engineering / tech-debt

The full engineering MoSCoW (build hygiene, `SourceService` decomposition, `agent.spi` facade,
Fuse-leftover removal, reactor split, shutdown robustness, `@PublicApi` freezing) lives in
**`superpower/modularization-optimization-plan.md`** §4 (ACTIVE, analysis unexecuted). Headline Musts:
M1 parent `dependencyManagement` · M2 `SourceService` decomposition · M3 `agent.spi` facade ·
M4 UI Fuse-leftover removal (~25.8k lines) · M5 coverage baseline · M6 repo-clutter sweep.

## 6. Security-module scope (deferred wholesale — do not partially implement elsewhere)

Identity/login, user model, role-assignment UI, Admin pane, server enforcement, lens-switcher
constraint — per `archived-documents/plans-archive/rbac-groundwork.md` §5. Rides on top:
**Lens Access P3** (subjects become Roles, matrix enforcement server-side — the shipped
catalog/profile/matrix reuse as designed) · dataset/widget/dashboard sharing RBAC ·
rbac open Qs: Q1 `canOnboardConnections` → Admin split · Q3 `canTriageRequirements` grant set ·
Q4 Requirements SLA (declined, revisit with roles) · X-Actor retirement (overlaps API v1 row).

## 7. Docs & open product questions

| Item | Status | Source |
|---|---|---|
| User-guide audit P1: #8 KPI authoring + Measure-reuse doc; #10 quarantine remediation/replay doc (or file product gap "D-ETL") | Open | `archived-documents/superpower-reviews/user-guide-audit.md` |
| User-guide audit P2: #11 de-jargon ("pro/pro max", "Stage-2"); #12 parser format-list alignment + GLOSSARY §6-B Matrix tense | Open | same |
| ADVANCED_GUIDE Control-API section regen (post-W7 route changes) | Open | review sweep |
| Open product questions: `canOnboardConnections` split, sunset timing, structured queries client-compiled | Awaiting product | `REQUIREMENTS.md` §7 |
| Interview backlog Qs: #2 parser required-vs-advanced, #5 Incident/Case mandatory fields + assignment model, #6 template scope, #7 KPI target ownership | Awaiting product | `archived-documents/plans-archive/frontend-review-and-completion-plan.md` §6 |
| FEATURE_INVENTORY gaps: LDIF `record_split` proposal; structured/`text_regex` block records; missing example files; `package.ps1` dir pre-creation | Open (snapshot 2026-06-20 — verify vs code) | `FEATURE_INVENTORY.md` |
| Template seed-pack enrichment (frontend C7) — continuous, not discrete | Ongoing | — |

## 8. Duplicate map (same work, multiple IDs — update all sources when closing)

| Canonical | Also recorded as |
|---|---|
| API-5 legacy sunset | w7-ui-v1-migration deferred follow-ons · legacy-alias logging row (§3 misc) |
| EOI-7b eoiagent publish | agent-kernel-replacement §open-items |
| Geo-map Phase 4 backend | INV-2 Phase 4 · ComponentStore view-kind widening |
| INV-1 Entity Projection backend | link-analysis V1 backend rows |
| MNT-14 archived-incident sweep | blocked-on = Incidents I1 backend workflow row |
| Sharing RBAC (SEC-gated) | Queries/BI row §3 · security-module scope §6 |

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays
authoritative), then delete the row here. New pending items discovered mid-shift get a row here at
handoff time (see the `handoff` skill). This page lists **open work only** — no DONE rows.
