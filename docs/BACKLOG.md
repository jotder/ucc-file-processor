# Consolidated Backlog — every OPEN item, one page

**Updated:** 2026-07-21 (AGT-5 embedded intelligence **P0–P5 COMPLETE** + polish — the giant shipped
blob collapsed to a `COMPLETE` pointer per this doc's "shipped work is not recorded here" rule; the
four remaining AGT-5 follow-ons promoted to first-class rows) · prior 2026-07-20 (D-ETL quarantine UI,
Job pack unavailable-flip, Email/SMTP channel, `record_split`, R8 pivot-bar) · **Owner:** whole team
(update at every handoff that closes/opens an item)

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

_(no in-flight repo state — the `package.ps1` bundle smoke-test row closed 2026-07-20, see §3 note below)_

**Dependency-ordered priority (triaged 2026-07-23).** Order of attack across the open rows below,
by dependency fan-out — each row's detail stays in its own section; this is only the ordering:

1. **Root enablers (largest downstream fan-out — start here):**
   - **§6 RBAC/ABAC R-workstreams** (`superpower/rbac-abac-plan.md`) — unblocks sharing RBAC
     (§3 Queries/BI), Lens Access P3, SPC-5 ABAC, X-Actor retirement, auth-gated notification
     prefs, and the NFR-7 access-control evidence. None of those dependents can start before R1–R2.
   - **Bound job concurrency** (semaphore on the `JobService` executor) — stated prerequisite (§5
     DuckDB issue) for the on-by-default memory cap, which in turn gates the chunking default.
   - **Incidents I1 backend workflow resolution-gate + `ObjectStore` delete** — sole blocker on MNT-14.
   - **v1-only triggers** (retire the legacy inline-ingest routes) — drives
     `inspecto_legacy_api_requests_total` to zero, which is what starts the API-5 30-day soak clock.
2. **Cheap decision gates (product calls, near-zero build):** NFR-7 C1 sequencing sign-off ·
   secret-in-bundle policy (sole blocker on the bundle `connection` kind) · API-5 soak sign-off
   once the meter reads zero.
3. **Dependent chains (sequence behind tier 1):** SPC-5 ABAC, sharing RBAC, Lens Access P3, and
   NFR-7 SOC 2 execution → after RBAC R1–R2 · MNT-14 → after I1 · API-5 physical deletion → after
   the soak · Postgres multi-user (write the `docs/superpower/` plan first, then store pooling) —
   pairs with RBAC for the multi-user story and gives notifications a persistent backend.
4. **Independent — schedule by value, no ordering constraint:** AGT-6 · Geo Phase 4 backend (also
   feeds the `spatial` QueryType + ComponentStore view-kind widening) · link-analysis V2 ·
   Reference Phase-2 engine semantics · T15 adaptive back-pressure · notification follow-ons
   (webhooks/digest/authorable TOON) · M4 Fuse remainder + M5 coverage baseline · onboarding
   per-stage `/validate` · menu O1/O2/O3 · eoiagent DryRunProvider.
5. **Externally gated (parked — not schedulable by us):** OPS-5 live soak (deployment) · EOI-7b
   (infra) · E1 (demand) · parser field tiers (UX session) · `package.ps1` ACL fix (admin access) ·
   remaining connector workbenches (demand) · C6 (profiling evidence).

## 2. Product remainder (MoSCoW of record: `REQUIREMENTS.md` §5)

| ID | Item | Status / blocker |
|---|---|---|
| ACQ-4 | Object-storage connectors — S3/MinIO/GCS-interop + SDK-free Azure Blob + **GCS-native (JSON API, service-account OAuth2) all shipped** (2026-07-22) | **DONE.** The "offline-blocked (no SDK jars)" tag was stale — OAuth2 JWT signing is the same JDK-crypto category as SigV4/SharedKey, no SDK needed. `okf/backend/acquisition/connectors.md` |
| OPS-5 | Provenance conservation on live data (built, off by default) | Needs a live deployment to verify. **Offline de-risk done 2026-07-22:** feature integrity re-confirmed post engine-refactor + synthetic tests added for the imbalance→ALERT promotion and the run→check→emit bridge; `docs/ops/provenance-conservation-verification.md` corrected (alerting path = ALERT object via `EventObjectBridge`, Sankey via `JobRoutes`). ~~⚠ Open product Q: should an imbalance also fire a `NotificationRule`?~~ **RESOLVED + SHIPPED 2026-07-23 — yes:** `NotificationRules.defaults()` now carries a `FLOW_CONSERVATION_IMBALANCE` rule (category `ops`, `minLevel=WARN` → both LOSS/ERROR and AMPLIFICATION/WARN notify, matching the ALERT bridge). The remaining OPS-5 work is still the live-feed soak (no code left). |
| NFR-7 | Compliance certifications | PARTIAL (not started) — **plan drafted 2026-07-23: `superpower/compliance-certifications-plan.md`** (SOC 2 first; C1 sequencing sign-off pending) |
| API-5 | Legacy route **physical deletion** — sunset mechanism + `inspecto_legacy_api_requests_total` meter shipped (W8); delete after the signed **30-days-at-zero soak** | Soak-gated policy call |
| EOI-7b | Publish eoiagent `0.1.0` artifacts to a registry (v0.1.0 cut + pinned 2026-07-08; CI rebuilds from tag meanwhile) | Infra/product call |
| AGT-5 P0–P5 | Embedded-intelligence agent — QA → author (L1) → gated action (L2) → bounded autonomy (L3) → learning | **COMPLETE 2026-07-21** (+ polish). Roadmap fully shipped; history in git + `superpower/embedded-intelligence-plan.md` §8, distilled in `okf/backend/agent/embedded-intelligence.md`. Only the follow-ons below remain OPEN. |
| AGT-5 · embedding recall | P5 upgrade: replace `CaseSimilarity` Jaccard with embedding/vector recall (eoiagent `LlmGateway.embed` + `Retriever`/`VectorStore`) | **PARKED — not warranted.** `CaseStore` is a 256-cap ring; Jaccard is adequate. Drop-in seam preserved behind `CaseSimilarity.score`; revisit only if the corpus grows unbounded. |
| AGT-5 · eoiagent DryRunProvider seam | Cross-repo (`jotder/inspect-agent`): add a per-tool `DryRunProvider`/preview seam to `PlatformBuilder` so the framework populates `ApprovalRequest.preview`, letting inspecto drop its parallel `AgentApprovals` previewer | **OPEN — low priority.** Pure refactor; functional parity today. Push-first discipline applies (CI rebuilds eoiagent from `main`). Precedent: the P3 `approvalDecisionStore` seam (`d6fabb3`). |
| AGT-5 cuts | QA-only (`incident_explain` waits on the eoiagent host seam); local-models-only | Open scope cuts — same |
| AGT-6 | AI behind every screen / agent graphs | PLANNED |
| SPC-5 | Per-tenant ABAC (rides the SEC-7 grants model; absorbs per-resource ACLs/ownership) | PLANNED (Enterprise) |
| E1 | Enterprise distributed tier / Stage-2 streaming | Demand-gated |

## 3. Feature follow-ons (deferral sections of shipped work)

> _Reconciled against live backend routes 2026-07-18. Struck as already-shipped: `/settings/branding`
> (→ removed the Branding row), `/signals`, sink→`ViewStore` auto-registration, legacy-alias usage logging.
> **2026-07-20 re-reconcile:** this note's "genuinely un-implemented" list had gone stale — most of it
> shipped in the 2026-07-18/19/20 sweeps below and was just never cleared here. Grep-verified as of
> 2026-07-20, only two items from that list are **still actually open**: the Email/SMTP notification
> channel (Notifications row) and the `graph`/`spatial`/`search`/`api` QueryTypes (Queries/BI row).
> Confirmed already shipped (see their rows below for the as-built): `/notifications/channels*` CRUD,
> a backend navigation endpoint, the `recon.run` Job + Alert Rule→Incident wiring, the workflow-override
> boot-scan into `ObjectService`, cursor pagination (mechanism + 4 adopters), and the enrichment
> sample-preview endpoint (`POST /enrichment/preview`, `EnrichmentRoutes.java:42`)._

| Area | Open items | Source (detail) |
|---|---|---|
| **API v1** | X-Actor full retirement on Standard · UI sign-out affordance · ~~UI↔backend `ContentHash` float-parity conformance test~~ *(2026-07-23 SHIPPED: shared golden-hex vectors across `ContentHashTest.java` + `content-hash.spec.ts` lock the two hashes together. Finding: non-integer floats agree (JDK 19+ `Double.toString` = JS shortest round-trip), but an **integer-valued double diverges** — Jackson keeps `1.0` as `"1.0"`, JS emits `"1"` — now pinned as an explicit boundary on both sides + noted in `ContentHash` javadoc. Config both sides hash should avoid integer-valued floats.)* · ETag beyond components+bootstrap · cursor pagination *(2026-07-18 SHIPPED the mechanism + first adopter: opaque keyset cursors via `metadata.pagination` `{cursor,nextCursor,limit,total}` — `ApiContext.pagination` seam + `Envelope` emit + `com.gamma.control.Cursor` (decode-total, URL-safe Base64/JSON) on `GET /jobs/runs` over `DbJobRunStore` (new keyset `recentRuns`+`countRuns`); v1-only, legacy list unchanged; `okf/.../api-v1.md`. 2026-07-19 SHIPPED second adopter `GET /objects` — keyset over the SEC-7d-visible set, in-route not
SQL-side, since objects are low-volume and the visibility filter would otherwise leak/mis-size `total`.
2026-07-19 also SHIPPED third+fourth adopters `GET /jobs` (in-route, name keyset over the in-memory registry) + `GET /events` (store-side `(ts,eventId)` keyset — `EventStore.page/count`, exact impls in both bundled stores; v1 pages the full retained Parquet history, not just the live-tail ring). Remaining: adopt the seam on further list families as demanded)* · Standard-edition jlink runtime vs Nimbus not re-verified (`-NoRuntime` until confirmed) | `archived-documents/plans-archive/api-contract-design.md` |
| **Bundle / Exchange** | *(2026-07-18 SHIPPED: `authored-pipeline`/`job`/`saved-view` now bundle-eligible via a uniform `BundleSource` seam — job import hot-registers via `upsertJob`; as-built in `okf/backend/control-plane/metadata-bundle.md`.)* `BundleRoutes` remaining missing kind: `connection` (secret-in-bundle policy call unmade) · `requires` present-but-different classification · per-editor "load as draft" import *(2026-07-23 TRIAGED — NOT a small buildable, deferred: `BundleTransferService.write` commits every kind straight through its store's create/update; there is no generic draft/unpublished seam, and "per-editor" means opening each kind's own editor pre-filled-but-unsaved (editors open by route/id, not injected content). A real per-editor integration (router-state seam + draft-vs-inactive semantics) — design-first, likely multi-session; do NOT fake it with a cross-kind `enabled:false` stamp)* · ~~UI Exchange-surfaces track §3.6~~ *(2026-07-19 VERIFIED-DONE + last piece SHIPPED: catalog Shared-with/by-me, offer/request flow, scope badges, pin/expiry governance were all already live in `sharing.component`; this row **undersold** shipped work. The one genuine gap — the **pin-drift "Behind" chip** (an active grant pinned behind the owner's current `freshness.version`) — shipped client-computed in `sharing.component`, no backend. Full as-built: `okf/backend/control-plane/exchange-sharing.md`; the design-of-record plan is now archived (fully shipped))* | `okf/backend/control-plane/exchange-sharing.md` · `archived-documents/plans-archive/storage-layout-and-sharing-plan.md` · `archived-documents/plans-archive/transportability-plan.md` |
| **Job framework** | ~~Pack in-flight-Run quiesce~~ *(2026-07-20 SHIPPED the classloader half: `JobPackManager.acquireRun`/`releaseRun` pin a pack's active-run count for a Run's `Job.run(ctx)` duration; `unload()` defers closing the old classloader/staged jar until the count hits zero instead of closing it immediately underneath an in-flight Run. 2026-07-20 SHIPPED the remaining half: `JobService` now records each Job's owning pack at build time (`jobPackOwner`) and `JobPackManager.unload()` notifies it via a new `UnloadListener` callback; a notified pack's Jobs flip into an `unavailableJobs` set, and the shared `runJob` lifecycle rejects (`REJECTED`, fail-closed) any Run on a still-registered-but-flagged job instead of executing the stale cached `Job` — cleared again on rebuild/`removeJob`)* · ~~`args`/`bind` type-inference~~ *(2026-07-20 SHIPPED: `ParameterResolver.resolve` now validates a resolved value against its declared `ParamType`; a mismatch REJECTS the Run via a new `Resolution.invalidType()` bucket instead of a malformed string reaching Job code — `okf/backend/control-plane/jobs.md`)* · MNT-14 Archived-Incident sweep (**blocked** on backend Incident lifecycle + `ObjectStore` delete) · maintenance COULD tier (~~trends~~ **growth-trend analysis SHIPPED 2026-07-23**: `storage_trend` task + `storage_report` now appends a queryable per-axis sample to the `maintenance_storage` catalog Dataset — per-axis/total bytes/day, projected `warn_bytes` breach ETA, archive candidates, `maintenance.storage.trend` signal; `okf/.../jobs.md`. Still open: space-to-space comparison, predictive maintenance — the latter is AGT-5/self-healing territory, deliberately deferred) *(2026-07-18 §3-reconcile: struck "sink→`ViewStore` auto-registration" — already shipped, `PipelineJobRunner.registerViews` writes a `ViewDefinition` per `sink.view` node after every flow run)* *(2026-07-20 SHIPPED: `Scheduler.cron()` now returns a `CronHandle`; `JobService.removeJob` cancels it, so a deleted/replaced job's cron chain actually stops instead of ticking as an inert no-op forever — `okf/backend/control-plane/jobs.md`)* | `okf/backend/control-plane/jobs.md` · `archived-documents/plans-archive/system-maintenance-plan.md` |
| **Queries / BI** | ~~Structured-query editor UI (SQL is the only authoring surface)~~ *(2026-07-19 SHIPPED: the Query Library's `type` toggle wires `<inspecto-query-panel>` — the existing projection+filter Query Core builder, already reused by Decision/Alert Rules/Expectations — in for `structured` queries; the panel gained an `@Input initialModel` so editing a saved structured query round-trips its filter back in. `$`-parameters stay SQL-only, a deliberate cut. `okf/backend/control-plane/queries.md`)* · `graph`/`spatial`/`search`/`api` QueryTypes · more `$`-resolvers · ~~responsive dashboard tiles (ResizeObserver re-render)~~ *(2026-07-19 SHIPPED: `InspectoChartComponent` now observes its host box and calls `chart.resize()` on container-only changes — tile span toggle, side-pane collapse, flex reflow — mirroring the GraphView/MapView RO pattern; Chart.js `responsive` only covered window resize)* · dataset/widget/dashboard **sharing RBAC** (SEC-gated, `inspecto-security`-owned) · calculated-columns v2 = whitelist growth only (window/aggregate fns) | `archived-documents/plans-archive/query-kind-plan.md` · `studio-bi-improvements-plan.md` · `calculated-columns-design.md` |
| **Notifications** | ~~Email channel impl~~ + delivery-status webhooks · digest batching · ~~time-based retention sweep~~ *(2026-07-23 SHIPPED: a `notification_prune` built-in `maintenance` task — cron-schedulable, `retention_days` (required), forgets feed entries older than the window whatever their read/archived state, mirroring `ledger_prune`/`runlog_prune`. Added `prune`/`countPrunable` to `NotificationStore`; the per-space feed is attached to `JobService` post-construction. NB the default feed is in-memory + self-caps at 1000, so this matters most once a persistent notification backend lands. `okf/.../jobs.md`)* · GeoIP · auth-gated per-user prefs/security triggers *(2026-07-18: **channels admin CRUD** SHIPPED backend — `GET/POST /notifications/channels` + `PUT/DELETE /notifications/channels/{id}` persist a `channel` ComponentStore kind (`ChannelConfig`); 503/422/409/404 gates, per-space, `canAuthorWorkbench`. 2026-07-19 SHIPPED: **persisted channels wired into dispatch** — `NotificationService.dispatch` delivers to enabled `ChannelConfig` entries through the matching SPI transport via a new `deliver(n, target)`; the "admin config only, inert" gap is closed. 2026-07-19 SHIPPED: `ChannelConfig` gained a per-channel `template` field (Signal Backbone S2), rendered via the shared `DottedPath` grammar, falling back to the rule's default body when blank. 2026-07-20 SHIPPED: **email channel impl** — `SmtpEmailChannel` (already present in `inspecto-connectors`, `javax.mail`-based, ServiceLoader-discovered) now overrides `deliver(Notification n, String target)` to send to a persisted `ChannelConfig`'s own `target` instead of only the fixed `notify.smtp.to`; `ChannelConfig.fromMap` fails closed (422) at creation time when `kind=EMAIL` and `target` isn't a valid email address. Remaining: no *authorable* `notifications` TOON config section — rules stay hardcoded in `NotificationRules.java`, a deliberate S2 scope cut)* | `archived-documents/plans-archive/notification-system-and-audit-trail-plan.md` · `okf/backend/control-plane/events-metrics.md` |
| **Signal / Decision networks** | *(2026-07-18: raw `/signals` read endpoint was already live (`SignalRoutes` + static `Signals.query`); completed its query surface — added in-store `until` + `severity`-floor filters (400 on a bad severity) and the first test coverage (`SignalsTest` + `ControlApiSignalsTest`). No stateful `SignalsService` object — house style is the static utility called straight from the route, like `/events`. 2026-07-22 SHIPPED server-side causation-tree assembly — `GET /signals/tree?correlationId=` returns the correlation chain as a parent→child forest (roots oldest-first, orphans surfaced as roots, cycle-safe) via a shared pure `Signals.assembleTree`; the flat `/signals` view is unchanged. ⚠ No producer threads `causationId` yet, so trees are flat (all roots) today — the HTTP peer of the agent's already-shipped `signal_timeline` tool, whose duplicate `InspectoTools.causationOrder` ordering logic is a noted dedup follow-on (§5). 2026-07-22 SHIPPED the `source` filter — `GET /signals` + `/signals/stream` filter by the emitter Ref, matching its `kind` or compact `kind:id`, via a shared `Signals.matchesSource` predicate; route post-filters the page so `Signals.query`'s callers are untouched.)* · LLM-backed Assist proposal *(2026-07-18 SHIPPED end-to-end: real condition-tree evaluator `ConditionTree` — decision-rule `simulate` evaluates `when` over `sampleRows` with query-eval.ts parity (backend); the **UI** Simulate action fetches a bounded sample from the target store via `/db/table` and sends it (preview-verified: real `total`, not the stub); the **mock** `decision-rules.handler.ts` simulate now evaluates `when` over `sampleRows` (`evaluateRows`), demo counts as no-sample fallback. 2026-07-18 also SHIPPED: routing consequences `route`/`tag`/`quarantine`/`drop` now apply during live pipeline runs (`DecisionRuleApplier` in `writeAndTrace`, `ConditionSql` predicate compiler, per-space `DecisionRules` registry; `okf/backend/control-plane/decision-rules.md`), and the "GROSS simulates 0" finding was root-caused to the db-browser globbing the raw `backup/` tree instead of the pipeline's `dirs.database` output — fixed, plus the demo threshold recalibrated to a reachable `GROSS > 100`. 2026-07-18 also SHIPPED: both recorded deferrals — `targetType: job` rules now check `sql.template` job output (pre-snapshot, in `SqlTemplateJob`) and Stage-2 enrichment output on every recompute trigger (in `EnrichmentEngine`, matched by enrichment name + wrapping job name), via the generalized `DecisionRuleApplier.Subject`/`RouteSink` seam). 2026-07-18 also SHIPPED: **Expectation/Alert-Rule ComponentKind promotion** — Alert Rule moved off raw `*_alert.toon` files onto `ComponentStore` (`alert-rule` kind, same CRUD contract as Expectation/Decision Rule); Expectation gained a `condition` kind (`when` tree as the violation predicate, via `ConditionSql`); Alert Rule gained an optional `when` row-scoping filter over ledger rows (via `ConditionTree.filter`, new alongside `matched`). UI: both dialogs wire `<inspecto-query-condition-group>` (a new `AttributeSpec.dependsOn.notEquals` variant hides Expectation's `column` for the `condition` kind); live-verified end-to-end against the real backend (real `orders` columns probed, real `GROSS>100` → 1 violation; real alert-rule persisted to `registry/alert-rules/*.toon`). Full reactor 1567/0/0/3, UI test:ci 1403/0/5)* · *(2026-07-19 SHIPPED: `create-alert` consequence now opens a deduped `INCIDENT` for `critical`/`error` severities instead of a signal-only stub; see `decision-rules.md`)* · *(2026-07-19 SHIPPED the full Signal Backbone combined plan, S0–S7: canonical 13-field `Signal` envelope (`Ref`, 6-level `Severity`, structured payload, no version bump — 4.x unreleased); one bus + type catalog + `DottedPath` addressing grammar; `GET /signals/stream` SSE + `AgUiProjection`; the A2UI artifact channel (`AgentAskResult.artifact`, closed kind allowlist, `<inspecto-a2ui-render>` host) + the first agent chat surface; the `signals_query`/`signal_timeline` agent tools + `ContextBroker` situation frame; the gated agentic write path (A2UI `invoke` → existing-Decision-Rule dry-run/confirm/apply, `actor=agent:*` audit, `create-alert` wired to real Alert authoring); and the reflex-layer/editor-resolver unification (assist-panel renders through the generic host, `editorKey`→route resolver retiring two hardcoded tables). Full as-built: `okf/backend/control-plane/signal-backbone.md`; design-of-record archived. Open/deferred: optional S8 (connector-direct emission + cross-space controller); generalizing the hardwired ALERT→INCIDENT promotion into a Decision Rule; a general event-triggered consequence policy gate (still `/apply`-only); RFC 6902 JSON Patch state deltas for AG-UI (no diffing dependency, no consumer yet))* | `archived-documents/plans-archive/signal-network-plan.md` · `decision-network-plan.md` · `archived-documents/plans-archive/event-signal-backbone-plan.md` · `archived-documents/plans-archive/a2ui-agent-rendered-ui-spike.md` · `okf/backend/control-plane/decision-rules.md` · `okf/backend/control-plane/signal-backbone.md` |
| **Link analysis** | *(2026-07-20: V1 now fully shipped — multi-entity mapping, multi-root, incremental expand, all-paths, layout save, widget/dashboard placement, SVG/GraphML export, undo/redo, `attrCols`, and the schema-relationship model (`GET /inv/schema/relationships`, naming-convention FK inference across Datasets, see `okf/frontend/features/link-analysis.md`))*. Open: V2+ — advanced traversal, timeline, scoring, collaboration, algorithm library, AI assist, pattern packs | `archived-documents/plans-archive/link-analysis-studio-plan.md` §6–7 |
| **Geo map** | Phase 4 backend: DuckDB spatial, server-side projection/aggregation endpoint, `ComponentStore` widening for geo/link view kinds, progressive loading, worker binning | `archived-documents/plans-archive/geo-map-analysis-plan.md` |
| **Pipeline graph** | ~~T15 adaptive back-pressure *config*~~ → **re-scoped 2026-07-19**: the §3.5 levers are already configurable; the unbuilt part is the *adaptive lag-driven throttling feature* (admission-cap halving + hysteresis), a build not a constant-extraction (design doc T15) · ~~T19 topology-editor wiring into live executor~~ **the live-executor half shipped under T32 (2026-07-18)**; ~~only the G6-canvas node/edge authoring **UI** remains~~ *(2026-07-19 second correction: the G6 authoring canvas **was already shipped** — it's the Pipelines pane's Edit mode, `pipeline-editor.component.ts` + `pipeline-editor-graph.component.ts`: palette node-add, two-click/Shift-drag edges, per-node config dialogs, dry-run/validate/activate, full `/pipelines/authored` CRUD persistence; live-verified end-to-end. The 07-19 morning correction fixed the executor clause but carried this stale remainder forward — design doc T19 now `[x]`)* · mock-only: run-to-here `POST …/run` (path reserved — see below), ~~`/config/icon-map`~~ *(SHIPPED 2026-07-23: real per-space `GET/PUT /config/icon-map` in `SettingsRoutes`, persisted as `icon-map.toon` under the space write root; `okf/.../control-api.md`)*, `/asn1/modules` (stays mock-only — no backend ASN.1 capability exists) *(2026-07-18: T16/T17 checklist corrected — both were already shipped/closed, see the design doc §14 for the as-built; T17's live last-run overlay closed this pass by wiring the existing-but-unused `/provenance` endpoints into the editor canvas + inspector. 2026-07-18 also SHIPPED: the T32 config-less ad-hoc run — `POST /pipelines/authored/{id}/trigger` via `JobService.triggerFlowRun`, a synthetic never-registered PIPELINE config through the full run lifecycle (fence tracking, non-overlap, ledger, `runId` polling); deliberately `…/trigger` not `…/run`, which stays reserved for the editor's scratch-only run-to-here contract. The row's earlier `sink.view` `derived_sql` clause was stale — shipped 2026-06-19 per the design doc §14. 2026-07-18 also SHIPPED T32's last open item — the **UI consumer for views**: a `ViewsService` (`GET /views\|/views/{name}\|/views/{name}/data`) + a "Preview data" action on `sink.view` nodes in the pipeline inspector, opening a `ViewPreviewDialog` (bounded rows via `<inspecto-data-table>`, surfaces the backend's 409 "no derived_sql yet" as an inline error). Mock-backed (`pipelines.handler.ts` `/views*` routes over authored `sink.view` nodes) — T32 is now fully closed)* | `okf/backend/pipeline-graph/pipeline-graph-design.md` §14 · `archived-documents/plans-archive/flow-live-execution-plan.md` |
| **Acquisition / connections** | *(2026-07-18 both prior rows SHIPPED: `proxy` is now a `ConnectionProfile` sub-block — `target=proxy` on `/connections/test` probes the proxy hop; and the connection workbench `probe`/`explore`/`sample` routes are real — `ConnectionWorkbench` SPI + `ConnectionProber`, built-in local impl, SFTP/FTP/FTPS impls in `inspecto-connectors`, as-built in `okf/backend/acquisition/connectors.md`.)* *(2026-07-18 SHIPPED: the `db` workbench — `DbConnectionWorkbench` walks schema/table/column via `DatabaseMetaData` + bounded `SELECT` sample; read-only, WRITE always skipped.)* Open: workbench impls for the remaining connectors (`s3`/`gcs`/`azure`/`kafka`) — adopt the `CollectorConnectorFactory.workbench` hook when demanded (note: the `gcs` **connector** itself now exists as of 2026-07-22 (ACQ-4 done); only its probe/explore/sample *workbench* is unbuilt) · ~~connectors don't dial through a configured `proxy` yet (probe-only)~~ *(2026-07-20 SHIPPED first slice: `SftpConnector` now dials through a `SOCKS5` proxy via a new `SocksProxySocketFactory`, rejecting `HTTP` fail-closed rather than silently ignoring it — `okf/backend/acquisition/connectors.md`. Still open: FTP/FTPS and the JDBC-based connectors each need their own library-specific proxy wiring, and an actual HTTP CONNECT handshake for any connector)* | `okf/backend/acquisition/connectors.md` |
| **Incidents / cases** | I1 backend workflow resolution-gate · ~~`CaseRule` scheduler auto-evaluation~~ *(SHIPPED 2026-07-23: the `caserule.evaluate` built-in Job Type — a cron-schedulable wrapper over `ObjectService.evaluateCaseRule`, mirroring `recon.run`; emits `caserule.evaluate.completed`, requires the Object Engine, idempotent. `okf/backend/control-plane/jobs.md`)* · Studio-dataset binding of case analytics · C3 configurable Findings sections + auto member-timeline · first-class `category`/`tags` params on `GET /objects` (low value) *(2026-07-18 SHIPPED: workflow TOON overrides — `ServiceBootstrap` now scans `*_workflow.toon` at boot (both the legacy CLI and per-space `SpaceBootstrap` paths) and installs each via the new `ObjectService.registerWorkflow`, so `GET /workflows/{type}` serves the authored state machine instead of the frozen `Workflow.defaultFor` default; last file wins per type, malformed skipped. `okf/.../operations-reference.md`)* | `archived-documents/plans-archive/case-management-design.md` · `incidents-mail-ui-design.md` |
| **Reconciliation** | *(2026-07-18 SHIPPED the scheduled `recon.run` Job — `ReconRunJob` built-in Job Type runs a saved `reconciliation` on a cron and emits `recon.run.completed` with Break counts (WARNING on breach), building the identical `ReconService.Spec` the route does via the new shared `ReconConfigLoader`; `okf/frontend/features/reconciliation.md`.)* *(2026-07-19 SHIPPED: a breach also opens a deduped `INCIDENT`, one per reconciliation)* explicit non-goals: N>3, non-additive aggs, fuzzy keys | `okf/frontend/features/reconciliation.md` · `archived-documents/plans-archive/reconciliation-board-design.md` |
| **Menu builder** | M5: ~~favorites~~, polish, a11y, seeded example · real backend navigation endpoint *(2026-07-18 backend SHIPPED: `GET/PUT /nav/menus` (`NavRoutes`+`NavMenus`) persists the frozen `MenuTree` contract as per-space `nav-menus.toon` — settings-doc discipline, whitelist-canonicalizing 422 walk, `space` stamped from the request seam; as-built in `okf/backend/control-plane/control-api.md`. 2026-07-19: UI SHIPPED — `MenuService` hydrates from `GET /nav/menus` on load and write-throughs every mutation via `PUT` (`NavMenusService`); the localStorage mirror is kept for instant paint + the synchronous sidebar merge + offline; a `nav.handler` gives `mockDemo` offline parity. Live-verified end-to-end: server persistence survives a cleared mirror + reload)* *(2026-07-19 SHIPPED: M5 a11y — ARIA tree roles on the builder (role=tree/treeitem/group, aria-selected/aria-expanded) — and a generic opt-in seed example (`MenuStore.seedExample()` + "Load example" button, empty-tree only); O3's Telecom-themed seed and the favorites/`/design` gallery entry stay open)* *(2026-07-23 SHIPPED — favorites: a personal, client-local per-space overlay (`menu-favorites.ts`, localStorage `inspecto.menuFavorites.v1`, never PUT to the server), a star toggle on leaf rows in the builder tree (mirrors the sql-editor favorites idiom, `aria-pressed`), and a virtual top-of-sidebar "Favorites" group (`favoritesNavGroup` in `menu-nav.ts`, prepended in `NavigationService`; resolves ids to the current tree, drops stale/deleted, distinct `fav-…` ids); `MenuService.favoriteIds/isFavorite/toggleFavorite`. Still open: O3 Telecom seed + a `/design` gallery entry)* · open points O1 (curator) O2 (icons) | `archived-documents/plans-archive/menu-builder-plan.md` |
| **Onboarding (Stream/Reference)** | ~~findings → `blocked` chip state~~ *(2026-07-23 SHIPPED the small version — a `blocked` StageStatus + rail chip driven by the existing save-time `POST /config/write` findings: a stage whose last save returned an ERROR-severity Finding shows a "⚠ Blocked" chip (first message in a tooltip) and can no longer read as Ready; `OnboardingStateService.stageFindings`/`blockingMessage`, findings attributed to the stage that triggered the save. Still open: a dedicated per-stage `POST /validate` feed with precise `fieldPath`→stage attribution)* · ~~"View as graph" link~~ *(2026-07-19 SHIPPED: onboarding header "View as graph" button → `/catalog?tab=graph&from=stream:|ref:<name>`; the catalog now honours `?tab`/`?from` deep-links (opens the Lineage tab + runs the traversal); the graph lift includes draft pipelines, so it works pre-go-live. Live-verified end-to-end)* · ~~discard: unregister the live registry entry (ghost row ≤60s)~~ *(2026-07-19: the companion-TOON cascade SHIPPED — `discardDraft()` now best-effort deletes `<name>_schema` + `<name>_enrich` after the pipeline. 2026-07-20 SHIPPED the registry-unregister itself: `CollectorService.unregisterPipeline` drops the config path and rebuilds the read surface synchronously, wired from `DELETE /config/pipeline/{name}` — no more ghost row)* · ~~enrichment deregister (deleted-on-disk job runs until restart) + schedule-interval change needs restart~~ *(2026-07-20 SHIPPED: `Scheduler.everySeconds` now returns a cancellable `ScheduledFuture` (mirroring `cron()`'s `CronHandle`); `EnrichmentService.unregister` (wired from `DELETE /config/enrichment/{name}`) cancels a removed job's timer immediately instead of it running until restart, and `register`'s re-arm cancels the prior timer before starting a new one so a changed `schedule_seconds` on an existing name applies immediately too)* · enrichment stage's `Validated` state *(2026-07-18: backend SHIPPED — `POST /enrichment/preview` runs the draft transform over an inline sample via `EnrichmentEngine.preview` (seeds `input`, real reference views, `{columns,rows,truncated}`, persists nothing); `okf/.../onboarding-authoring.md`. 2026-07-19: UI SHIPPED — the enrichment pane's **Preview** button samples the stream's Stage-1 output via `/db/table` and renders `/enrichment/preview` results in a shared `<inspecto-query-panel>`; live-verified end-to-end against the real backend)* · ~~`read_json array|auto` preview timestamp-serialization edge~~ *(2026-07-19 SHIPPED: `jsonSelect` now casts every column to VARCHAR (`COLUMNS(*)::VARCHAR`), keeping an auto-detected timestamp a plain string like every other format; partition-key hint UI-copy also now explains the `year=1900` sentinel — the sentinel itself is a deliberate, documented behavior, left unchanged)* · Reference Phase-2 engine semantics: cache/upsert/SCD versioning + refresh scheduling, row-level dedup, Stream grouping (GLOSSARY §3/§6-B roadmap) · optional templates entry (space-template-gallery precedent) | `okf/backend/control-plane/onboarding-authoring.md` · `okf/frontend/features/onboarding.md` · `archived-documents/plans-archive/stream-onboarding-design.md` |
| **Collector rename residual** | Pipeline TOON config-key `source:` block kept (renaming breaks authored TOON) — separate migration if ever wanted; `'SOURCE'` stage category unchanged | `okf/backend/gotchas/cross-cutting.md` |
| **`package.ps1` docs-copy `Access denied`** | 2026-07-20 root-caused: **not** a transient lock — 13 files under `docs/archived-documents/plans-archive/` (the exact same 13 the git working tree shows modified: `another-brainstorm-i-am-virtual-mango.md`, `brainstorm-tingly-storm-agent-a25290b106f5574da.md`, `brainstorm-tingly-storm.md`, `glimmering-waddling-clarke.md`, `i-want-to-brainstorm-snoopy-flute.md`, `lets-generation-requirement-and-peaceful-flute.md`, `optimized-churning-marble.md`, `recursive-seeking-acorn.md`, `recursive-wibbling-castle.md`, `snazzy-painting-platypus.md`, `synchronous-splashing-swan.md`, `synthetic-plotting-whistle.md`, `we-are-trying-to-harmonic-trinket.md`) have a broken/explicit-deny ACL that blocks *everyone*, including `git diff` (`Permission denied`) and `Get-Acl` itself (`UnauthorizedAccessException`) — the parent directory's inherited ACL is normal. `package.ps1`'s per-file copy-with-skip (2026-07-20, `be50ad8`/`b1ad6f8`) already degrades gracefully (warns + continues), so the bundle isn't blocked, but these 13 docs are silently missing from every `file-processor-deploy/docs/` bundle. **Fix needs elevated (Administrator) access**: `takeown.exe /f <file>` then `icacls <file> /grant <admin>:F` per file — attempted from a non-admin session and failed (`Access is denied` even on the ACL-reset attempt, `Get-Acl` throws `UnauthorizedAccessException`). Whoever last touched these 13 files (they're the working tree's only "M" rows) may know how they ended up with a broken ACL | `inspecto/package.ps1:407` (workaround) · the 13 files above (root cause) |

## 4. UI residuals (small, valuable)

| Item | Source |
|---|---|
| ui-design-review residuals — R2 column suggestions, R2 object-create chips, R3 command registry + `/`-focus + j/k nav all SHIPPED 2026-07-17; R6 true offset paging (events/audit/object-mail offset-append + mock `pageSlice`) SHIPPED 2026-07-19; ~~R8 pivot-bar (design-only until demanded twice — no second demand as of 2026-07-19)~~ SHIPPED 2026-07-20 — `PivotService` (`inspecto/investigation/pivot.service.ts`) + pivot buttons on `ElementDetailDialog`, wired into the two hosts (link-analysis ⇄ geo-map); `table` stays covered by the pre-existing "Open record" action | `archived-documents/plans-archive/ui-design-review.md` · `okf/frontend/log.md` · `okf/frontend/features/investigation-pivot.md` |
| `ComponentKind.deriveParts` seam — formalize when a 3rd composite kind needs it | same |
| Minor: parser/node attribute tiers best-guess pending firm backend specs *(2026-07-17 sweep SHIPPED: `<inspecto-chip>` · mock `/alerts/evaluate` real ledger math · live-tail selectable cadence · mock audit trail records authoring mutations — ops-side rule authoring (alert/tag/case rules) can adopt the same `emitAudit` seam if wanted)*. ~~pipeline-editor dry-run panel extraction (may be moot)~~ **SHIPPED 2026-07-22** — was NOT moot (751-line component still had it inline); extracted to `PipelineDryRunPanelComponent` (self-contained sample/result/error state, `[pipelineId]` input), its own spec incl. a11y; host keeps only the open/closed toggle | same |
| ~~Dev-mode mount flake: the vendored `GammaLoadingBarComponent` NG0100 (progress −1→0 mid-tick) intermittently aborts the CD pass that activates routed content on fresh loads~~ FIXED 2026-07-19 — subscribe callbacks defer assignment to a microtask (`Promise.resolve().then`) so a synchronous first value lands after the current CD tick | observed 2026-07-17 preview walks |

## 5. Engineering / tech-debt

The engineering MoSCoW (build hygiene, `SourceService` decomposition, `agent.spi` facade,
Fuse-leftover removal, reactor split, shutdown robustness, `@PublicApi` freezing) lived in
**`archived-documents/plans-archive/modularization-optimization-plan.md`** §4 — **COMPLETE 2026-07-21
and archived**; as-built facts distilled to `okf/backend/modules/reactor.md`. Headline Musts:
~~M1 parent `dependencyManagement`~~ **SHIPPED 2026-07-21** (`73ea9a1`) · M2 `CollectorService`
decomposition **CLOSED — won't-do (maintainability-only; see §5 triage 2026-07-22)** ·
~~M3 `agent.spi` facade~~ **SHIPPED 2026-07-21** (`fc772f0d` + `f7d148a4` — as a `@PublicApi` contract
freeze on the agent-consumed core surface, deliberately NOT a wrapping facade) · M4 UI Fuse-leftover
removal (~25.8k lines) · M5 coverage
baseline · ~~M6 repo-clutter sweep~~ **SHIPPED 2026-07-21** (`b554048` — most already done by prior
shifts; only the stale `HANDOVER-multi-space.md` + a mangled root build-log remained).

~~M7 **agent durable-store consolidation**~~ **SHIPPED 2026-07-21** (`0865cb4`): extracted a generic
`DurableJsonlRing<T>` base (per-payload `Codec<T>`) + a shared `AgentWriteRoot` resolver in a new
`com.gamma.intelligence.store` package; `ApprovalStore`/`CaseStore`/`FeedbackStore`/`RunbookRunStore`
now subclass it and the ~6 copies of the `assist.write.root` → `<root>/agent/<file>` resolver collapsed
to one helper. No behavior change (intelligence 135/0/0/0).

**SHOULD tier — DRAINED 2026-07-21** (full detail in the archived plan §SHOULD): ~~S2~~ (both studio god
components split), ~~S3~~ (chart/grid consolidation), ~~S4~~ (PipelineScheduler map-leak fix), ~~S6~~
(ControlApi.dispatch → middleware chain), ~~S7~~ (shutdown robustness), ~~S8~~ (`@PublicApi` SPI freeze),
~~S9~~ (intelligence↔agent decoupled via core model-settings bridge). **S1 skipped by design**
(RouteModule→ServiceLoader would widen 39 types). ~~S5 steps ①②~~ **SHIPPED 2026-07-21** (`bc4d5f4d` +
`0398a02b`): leaf **`inspecto-api`** (`file-processor-api`, just `com.gamma.api.PublicApi`) and
**`inspecto-config`** (`file-processor-config`, `com.gamma.config` moved verbatim) extracted; reactor is
8 modules; `package.ps1` builds `-pl inspecto -am` from the root. COULD tier resolved the same day:
~~C3~~ ag-Grid trim (audited 12-module registration, grid chunk 1.17 MB→970 kB raw / 262→219 kB transfer,
`aa87b2c3`) · ~~C5~~ was already shipped (`defb1281`, stale row) · ~~C7~~ `PipelineNodeType` documented
as a reserved ServiceLoader seam (`1ec510d9`).

✅ **SHIPPED — `fp-engine` extraction (WS-D COMPLETE; 2026-07-22):** the 15-package engine SCC (`etl,
event, signal, query, pipeline, inspector, acquire, ingester, ops, job, enrich, alert, metrics, notify,
catalog`) extracted whole into **`inspecto-engine`** (`file-processor-engine`) below core; reactor now
**10 modules**. Full reactor `mvn -o clean test` green (**1884 tests**, build order proves acyclicity:
engine at 5, core at 6); shaded fat JAR verified (Main-Class + logback.xml + both service files + engine
classes bundled). Two build-clean-only issues resolved: fp-engine publishes a **test-jar** for the shared
`com.gamma.etl.TestConfigs` fixture (~45 core tests); `logback.xml` moved to engine with its
`EventStoreAppender`. As-built facts + the extended playbook (rules 7–8) distilled into
[`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md); plan archived. Prior tail (both `job`
edges cut) shipped `ee3e618f..33fa51ca`.

✅ **SHIPPED — increment 1: `etl` → foundation leaf (2026-07-22):** an intra-engine dependency map found
a **10-package SCC** (`etl, event, metrics, pipeline, job, acquire, signal, query, enrich, ops`). Its
main-code back-edges from `etl` lived in just two files, both cut without behavior change:
`etl.DecisionRuleApplier` → relocated to `com.gamma.pipeline`; `etl.BatchAuditWriter`'s `pipeline.batch.*`
Signal build+emit → new `com.gamma.signal.PipelineBatchSignal`, injected via `setTerminalBatchSink` wired
in `CollectorProcessor`. **`etl` is now a foundation leaf** (out-degree 0 within engine); the SCC
fragmented to **`{pipeline, job, query, enrich}`** + **`{event, metrics}`**, and **`acquire, signal, ops,
catalog` dropped out** (simple downward deps now). Full reactor green (1884 tests, 0 failures). Map +
before/after: [`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md).

✅ **SHIPPED — increment 2: `fp-etl` module extraction (2026-07-22):** `com.gamma.etl` extracted into
its own leaf module (`inspecto-etl` / `file-processor-etl`) below `fp-engine`; reactor now **12
modules**. The "test sources import up" blocker was worse than the import-line scan showed — 3 test
methods reached up via fully-qualified inline calls with no import line (playbook rule 5 struck again):
`SourceConfigTest` (a genuine acquire+etl+event+inspector integration suite, moved whole to
`fp-engine`'s `acquire` package per operator call), plus one-liners in `CommitLogTest`/`PhaseFConfigTest`
split into new `inspector.CommitLogIntegrationTest`/`acquire.PostActionTest`. Full reactor green — 1884
tests, 0 failures, exact match to baseline. Detail: [`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md#fp-etl-module-extraction-ws-d-increment-2-shipped-2026-07-22).

✅ **SHIPPED — increment 3: `fp-event` module extraction (2026-07-22):** `com.gamma.event`+`metrics`
(mutually cyclic with each other only) extracted into `inspecto-event`/`file-processor-event` below
`fp-etl`; reactor now **13 modules**. `logback.xml`+`EventStoreAppender` moved together; dropped
`fp-engine`'s now-unused `logback-classic` dep and unused test-jar publish (verified no consumer
before removing). Full reactor green — 1884 tests, 0 failures.

✅ **SHIPPED — increment 4: `fp-acquire` module extraction (2026-07-22):** `com.gamma.acquire`
extracted into `inspecto-acquire`/`file-processor-acquire` below `fp-event`, now no longer SCC-trapped;
reactor now **14 modules** (101 tests standalone). Turned out to require increment 3 first (acquire's
only up-dep is `event`) — scoped as "just fp-acquire", corrected mid-session once the transitive dep
was traced. `SourceConfigTest` (moved into `acquire`'s package in increment 2) moved a second time,
into `fp-engine`'s `inspector` package as `SourceConfigIntegrationTest` — same test, same "don't split
it" call, second address since `inspector` stays behind while `acquire` moved out. Full reactor green
— 1884 tests, 0 failures. Detail: [`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md).

**`{pipeline, job, query, enrich}` SCC fragmentation SHIPPED (2026-07-22, same-day follow-up)** — empirical
scan found the whole cycle held together by exactly 2 back-edges out of `pipeline`
(`PipelineJobRunner implements job.Job`, `DecisionRuleApplier`→`query.ConditionSql`); relocated both to
their natural homes (`job`, `query`), which also dropped `enrich` out of the SCC as a side effect
(it only touched `pipeline` via the relocated `DecisionRuleApplier`). `pipeline` is now a clean base;
`query`/`enrich` sit above it; `job` sits above all three — no more cross-cycle. Full reactor green,
1884/0/0/3. Package-level layering only (still one `fp-engine` module) — prerequisite for a future
`fp-query`/`fp-job`/`fp-enrich` module split, not the split itself. Detail:
[`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md).

**Remaining follow-on — TRIAGED 2026-07-22 (deferred, not to build now):**
- **Actual module extraction of `fp-query`/`fp-job`/`fp-enrich`** below `fp-engine` — **deferred pending
  an actual desire for finer module granularity** (nobody has asked for it; it is a preference, not a
  need). Mechanically re-assessed this triage: main-code layering is clean and acyclic (`pipeline` base
  → `query`→`pipeline`, `enrich`→`query` one-way with no `query↔enrich` cycle → `job` on top; the only
  consumers — `inspector`/`catalog`/`alert` — reach downward only, incl. two inline-FQN calls in
  `alert` (`DatasetMeasureProbe`/`ConditionTree`) that a plain import grep misses, rule 5). But it is
  **NOT a single clean increment**: (a) `query`/`job` also depend on `signal` and `ops`, which sit
  outside the four-package group and would have to be co-extracted below/alongside the new modules
  (their own test-side up-imports were not scanned — an unknown), and (b) a known test cut is required —
  `job`'s `SharedDottedPathGrammarTest` imports `com.gamma.notify.NotificationTemplate` (notify stays
  behind), the same rule-5 test-up-import class that bit `fp-etl`. Same deadlock-prone reactor work as
  the prior increments, for granularity no one has requested — **build only on explicit request.**

**Triage verdicts — 2026-07-22 (the rest are CLOSED or stay trigger-gated; none to build now):**
- **M2 `CollectorService` decomposition — CLOSED (won't-do).** `CollectorService`
  (`inspecto/src/main/java/com/gamma/service/CollectorService.java`, 1266 lines) already reads as a
  composition-root/facade: its constructor wires ~15 already-extracted collaborators rather than
  implementing their logic, and it is covered by 6 focused test files. Maintainability-only, not a
  split blocker (old premise corrected). No god-class emergency → deliberately not pursued.
- **M3 `agent.spi` facade — SHIPPED 2026-07-21** (`fc772f0d` + `f7d148a4`), as a `@PublicApi` contract
  freeze on the ~31 agent-consumed core types, deliberately NOT a wrapping facade (access already
  funnels through `UccAgentContext`). Item was stale on the tracking list; recorded here for the record.
- **C2 store-pair generic base — CLOSED (won't-do, 2026-07-23).** Distinct from the shipped M7
  (`DurableJsonlRing`/`AgentWriteRoot`, agent-side JSONL stores): the candidate duplication is the JDBC
  `Db*Store` family — 5 in `inspecto-engine`
  (`DbObjectStore`/`DbLinkStore`/`DbNoteStore`/`DbJobRunStore`/`DbProvenanceStore`) + `DbStatusStore` in
  the separate `inspecto` module. **Scoped 2026-07-23 and judged not worth it:** a `DbBackedStore` base
  could absorb only the `conn` field, `close()` (also unifying a minor split — 2 of 5 mark it
  `synchronized`, 3 don't), `browseConnection()`, and the `initSchema()` try/catch wrapper (DDL text
  differs per class) — roughly **30–40 lines across 5 classes**. It can't absorb the `open(...)` static
  factories (return concrete types, not inheritable), the per-class DDL/tables/row-mapping/domain APIs, or
  the maintenance methods; and the duplication that actually mattered is **already** de-duped by
  `JdbcDrivers` (connect + dialect), `JdbcRows` (ResultSet→Map), and `BrowsableStore`'s default methods.
  `DbStatusStore` is a different module and materially more complex (5 tables, transactional `sync()`,
  legacy migration) — folding it in would add cross-module coupling for little gain. Net a wash for a new
  inheritance coupling + abstract hooks. Convention is consistent and correct today. **Reopen only if the
  `Db*Store` family grows materially** (a 7th+ store, or the shared shape starts drifting/bugging).
- **Signal causation-assembly dedup — DONE (2026-07-22).** Folded `InspectoTools.causationOrder` onto a new
  shared engine primitive `Signals.causationOrder(List<Signal>)`, a depth-first (pre-order) flatten of the
  same `assembleTree` forest that backs `GET /signals/tree`; the private flat DFS in `inspecto-intelligence`
  is gone and `signal_timeline` delegates. Canonical cycle behavior is now `assembleTree`'s (members surface
  as roots at their oldest-first position) — the one deliberate divergence from the retired impl, which
  appended cycle remnants at the end; cycles never occur in real data, and the happy-path output is
  byte-identical. Locked with two engine-level tests (`SignalsTest.causationOrder*`); the existing
  `InspectoToolsTest` `signal_timeline` cases stay green. Pure dedup, no behavior change.
- **C4 BOM — CLOSED (moot).** The precondition (artifacts consumed OUTSIDE this reactor) does not exist —
  the project ships as a fat-JAR deployable, not a published library, and M1 (`73ea9a1`) already gave the
  reactor modules shared version hygiene via parent `dependencyManagement`. Reopen only if an external
  consumer of these artifacts ever appears.
- **C6 DuckDB per-run connection reuse — stays trigger-gated** (profiling-gated; no profiling evidence
  exists). Current code already opens ONE connection per run against a fresh unique temp scratch DB and
  shares it across all ops in that run (`PipelineJobRunner.run`, `EnrichmentEngine.runResult`). The
  proposal is cross-RUN reuse — a real re-architecture that is actively risky (per-run scratch DBs are
  deliberately ephemeral/isolated, JDBC connections aren't thread-safe, jobs run concurrently). Build
  only after profiling shows open cost matters. **Read-only connection/instance sharing investigated
  2026-07-22 and does NOT apply:** the query/measure/recon/enrichment read paths are ephemeral-instance
  over immutable Parquet globs (`read_parquet`, e.g. `DatasetRelation`/`SqlViews`/`SqlSandbox`) with NO
  persistent catalog to amortize, and each connection issues `CREATE VIEW`/`CREATE TABLE` scratch DDL
  (opened deliberately *unsealed*), so `access_mode=READ_ONLY` can't be used as-is. Sharing would save
  only cheap instance-init, not the scan; concurrent reads over immutable Parquet are already safe/
  isolated for free. If read-path open cost ever shows up (measure-heavy dashboards / recon boards open
  one temp DuckDB per measure/call), the cheap fix is switching `SqlSandbox` from a temp *file* to
  `:memory:` — not connection sharing.

**C-series DRAINED (2026-07-23).** C3/C5/C7 shipped, C4 closed (moot), C2 closed (won't-do, above), and C6
stays gated on profiling evidence that does not exist (a re-architecture that is actively risky to build
speculatively). Nothing in the C-series is buildable now; C6 is the only one that could ever reopen, and
only on real profiling evidence.

The intra-module `ops↔ops.link/workflow` and `catalog↔catalog.spi` cycles are same-family, not
reactor-split blockers.

**⚠️ ISSUE — DuckDB large-file responsiveness / resource-capping (raised 2026-07-22; the two uncapped paths
+ a global `-D` knob SHIPPED 2026-07-22, remainder open).**
Investigated after a "will a GB file choke responsiveness?" concern. JVM-heap axis is safe (ingest
streams through DuckDB — `read_csv` lazy VIEW → `COPY … PARTITION_BY`, no Java-side row
materialization; generation-mode flushes every `flush_records`). The exposure is **aggregate memory/CPU
under concurrency**, because the caps that prevent it are off-by-default:
- ~~**`PipelineJobRunner` and `EnrichmentEngine` run fully uncapped**~~ **SHIPPED 2026-07-22** — both run
  scratch connections now call `DuckDbUtil.applyGlobalDuckDbSettings(conn)` (which the batch path already
  did via `configure`), and a global JVM fallback (`DuckDbUtil.globalOr` + `-Dprocessing.duckdb.memory_limit`/
  `.temp_directory`/`.max_temp_directory_size`/`.threads`) now lets **one operator knob cap every DuckDB
  scratch connection** — batch, flow-job, enrichment. As-built in `okf/backend/engine/duckdb.md`.
- **`memory_limit`/`temp_directory` remain opt-in (no on-by-default value yet).** Unset → DuckDB default ≈
  **80% RAM per instance** → concurrent runs overcommit → OS thrash/OOM → whole box (incl. HTTP API)
  unresponsive. Operators can now cap all paths with `-Dprocessing.duckdb.memory_limit` (+ `.temp_directory`
  for spill), but there is **no computed default** — deliberately, because the backlog's suggested
  `RAM×0.7 / maxConcurrentRuns` premise is **wrong for the job path**: `maxConcurrentRuns` only bounds the
  batch-ingest semaphore; flow-jobs run on `JobService`'s **unbounded** virtual-thread pool (only same-job
  mutual exclusion), so `RAM/N` wouldn't bound their aggregate memory. Shipping an on-by-default value
  therefore needs either (a) bounding job concurrency (a semaphore on the `JobService` executor — throughput/
  deadlock considerations) or (b) a conservative fixed per-instance cap + spill. Deferred by the "consistency
  only, no new defaults" scope decision (2026-07-22).
- **Legacy (pre-v1) trigger routes run ingest INLINE on the HTTP request thread** (`RunRoutes.java:96`,
  `AcquisitionRoutes.java:50` → `CollectorService.runPipeline` synchronously); v1 routes are async
  (`202`+runId via `triggerRunAsync`). Standardize on v1 / deprecate the inline path. **[open]**
- **Single GB file isn't auto-chunked** — `processing.chunking.max_file_bytes = 0` (disabled by default;
  the knob IS documented — `okf/backend/config/configuration.md` §"Large files", + referenced in
  `okf/backend/engine/stage1-architecture.md` and `plugins.md`; the "undocumented" tag was stale, corrected
  2026-07-22). The open part is a *policy* call — ship an on-by-default safety-net value for pathological
  single files (needs a sensible fixed cap; ties to the memory-cap decision above). **[open]**
Order of value: ~~cap the two uncapped paths~~ (done) → on-by-default memory value + bound job concurrency
→ v1-only triggers → chunking. Read-path is NOT the risk here (see C6 note). Cheap open-cost instrumentation
is the gate.

**Postgres multi-user transactional backend (raised 2026-07-22 — DIRECTION captured, deferred by
operator).** Idea: move the transactional surface (`event→alert→incident/Case` + objects/links/notes/
job-runs/status/provenance) onto PostgreSQL to serve many concurrent users, keeping bulk ingestion +
analytical reads on DuckDB/Parquet. **Most of it already exists:** the stores are interface-seamed
(`ObjectStore`/`LinkStore`/`NoteStore`/`StatusStore`/`EventStore`) with a `-D*.backend` toggle in
`ServiceStores` and dialect-aware JDBC (`JdbcDrivers` duckdb-vs-postgresql); alerts/incidents/cases are
`ObjectType.*` rows through `ObjectStore` (already swappable); **`PostgresStateStoreTest` already
round-trips all 7 JDBC stores against embedded Postgres**; as-built in `okf/backend/engine/db-layer.md`
("essentially a configuration change"). **The real gap for multi-user is NOT the engine — it's
connection pooling:** every `Db*Store` holds ONE `synchronized` connection, so PG gives concurrency only
once the store layer is pool-backed (HikariCP/PgBouncer). Remaining build items: pool the stores;
**schema-per-space** URL wiring (NOT db-per-space — a PG conn binds to one DB, fragmenting pools);
`CaseStore` interface + PG impl (it's a JSONL ring today, no seam); keep events on Parquet (no PG impl,
right fit). **Don't route all reads through the postgres-duckdb plugin** (wire-protocol scans compete
with OLTP; bundling concern) — read PG directly for OLTP, reserve the plugin (or a materialize-to-Parquet
CQRS split) for cross-engine analytical joins. **Editions:** keep DuckDB-file the Personal default (zero
external deps / jlink), Postgres for Standard/Enterprise — via the existing toggle, editions-as-build-
flavor. Full options analysis in this session's transcript; write up as a `docs/superpower/` plan before
building.

## 6. Security-module scope (deferred wholesale — do not partially implement elsewhere)

**PLAN FINALIZED 2026-07-23 → `superpower/rbac-abac-plan.md`** (authorable `roles.toon` · Access
Policy engine · RBAC=Standard / ABAC=Enterprise; workstreams R1–R5 + A1–A5 — that doc is now the
authoritative scope for everything in this section). **Auth-stack direction added 2026-07-23:
Keycloak / WSO2** (external OIDC IdP + WSO2 APIM gateway) — groundwork captured as **R0** + plan
§5-B (gateway topology: OpenAPI import, SSE passthrough, SPA PKCE login — absorbs the §3 "UI
sign-out affordance" row); standards-only. **R0 reality-check (same day): the OIDC validator +
Keycloak PKCE relay already existed in `inspecto-security` (Nimbus JWKS, W6)** — R0's remaining
open items are the WSO2 gateway signed-JWT trust mode + the `identity:` claim allowlist (see plan).
**R1 SHIPPED 2026-07-23:** authorable `roles.toon` (`GET/PUT /access/roles`, core `Roles` seed +
per-request overlay, restart-free; `RoleMapper` switch retired). ⚠ Seed table corrected — five
route capabilities (`canConfigureAccess`, `canAuthorAlertRules`, `canOfferDatasets`,
`canRequestShares`, `canApproveShares`) were granted by NO role (access-config writes unreachable
under OIDC; bootstrap deadlock) — now seeded to builder/ops/admin/super per the plan's R1 note;
**product review of the new seed pending** (fold into Q3). **R4 SHIPPED 2026-07-23:**
`CapabilityManifest` (70 declared gates) + two-way source-scan congruence test; `KNOWN_CAPABILITIES`
now derives from it; catalog action nodes 422 on unknown capabilities. Next per plan phasing: **R2**
(Access-Profile server-side enforcement + authorize stage; folds in X-Actor retirement). Identity/login, user model, role-assignment UI,
Admin pane, server enforcement, lens-switcher
constraint — per `archived-documents/plans-archive/rbac-groundwork.md` §5. Rides on top:
**Lens Access P3** (subjects become Roles, matrix enforcement server-side — the shipped
catalog/profile/matrix reuse as designed) · dataset/widget/dashboard sharing RBAC ·
rbac open Qs: ~~Q1 `canOnboardConnections` → Admin split~~ **RESOLVED + IMPLEMENTED 2026-07-22**
(own Admin-only grant on the connection write routes; `RoleMapper admin→canOnboardConnections`) ·
Q3 `canTriageRequirements` grant set · Q4 Requirements SLA (declined, revisit with roles) ·
X-Actor retirement (overlaps API v1 row).

## 7. Docs & open product questions

| Item | Status | Source |
|---|---|---|
| ~~User-guide audit P1~~ *(2026-07-20 SHIPPED: #8 — added the KPI-authoring path + an honest Measure-reuse answer (reuse is real, per-Dataset, `NamedMeasure`) to `USER_GUIDE.md`. #10 — documented the real quarantine→fix→reprocess workflow (`GET /runs/{name}/quarantine` + `POST /runs/{name}/reprocess`), but honestly flagged the **product gap** it surfaced: reprocess has no UI action today, and there's no record-level "replay just the quarantined rows" — filed as D-ETL below, not silently glossed over)* | Shipped | `archived-documents/superpower-reviews/user-guide-audit.md` |
| ~~User-guide audit P2~~ *(2026-07-20 SHIPPED: #11 — dropped "pro"/"pro max" tier-name jargon from the Data Table section and "Stage-2" from Enrichment, both in `USER_GUIDE.md`. #12 — corrected the Parser-node "nine formats" claim to distinguish the 4 backend-wired frontends (delimited/DSV, fixed-width/text_regex, JSON, plugin/"other") from the 5 UI-only scaffolded ones (ASN.1, HTML, Parquet, XLSX, XML — confirmed via `parser-types.ts`'s own "best-guess" doc-comment); fixed `GLOSSARY.md`'s Matrix entry from present-tense ("is the label...") to honest not-yet-shipped wording (zero `Matrix` occurrences in the Angular source))* | Shipped | same |
| ~~D-ETL~~ *(2026-07-20 SHIPPED: the Quarantine tab's row-action set now includes **Reprocess this batch** (previously Batches-tab only), gated behind `lens.canOperateRuns()` and a confirm step before `POST /runs/{name}/reprocess`; `okf/frontend/features/run-detail.md`. Remaining known gap: still whole-batch only, no record-level replay — tracked separately if ever prioritized)* | Shipped | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` · `okf/frontend/features/run-detail.md` |
| ~~Open product questions + interview backlog Qs~~ **RESOLVED 2026-07-22** (product sign-off; recs plan archived). Outcomes: `canOnboardConnections` split+implemented · sunset timing = per-deployment · structured queries accepted-as-design (R6 closed) · case-type grants = SEC-7d as-shipped · Incident/Case = direct assignment + title/≥1-link mandatory · Space Template = config-only + opt-in sample data · KPI targets = Business-authored Requirement field (`target`/`comparator`/`unit`). | Resolved | `archived-documents/plans-archive/product-decisions-recommendations.md` (outcome table) |
| **Parser required-vs-advanced field tiers** (interview #2) — which options are `required` vs `advanced` in each parser format's `AttributeSpec`. A genuine UX judgment ("a new user is blocked without it"), needs someone who has watched real onboarding sessions — NOT an engineering guess (a placeholder heuristic would bake in an arbitrary answer that's expensive to unwind once forms ship). | Open — needs UX session | `inspecto-ui/.../parser-types.ts` |
| FEATURE_INVENTORY gaps: *(2026-07-20 SHIPPED: json/text_regex example files added under `examples/02-parsing/` — `json-frontend` + `text-regex-frontend`, each with `samples/`, verified end-to-end)*. ~~`record_split` (blank-line/delimiter block records for `text_regex`) remains genuinely unsupported (`PipelineConfigParser` rejects anything but one-record-per-line) — needs a DuckDB block-reading strategy change in `DuckDbCsvIngester`, a real engine feature not a quick fix; deliberately deferred.~~ *(2026-07-20 SHIPPED: `record_split: blank_line` or a literal delimiter string is now accepted; `DuckDbCsvIngester.buildTextRegexBlockReadSpec` reads via `read_text`/`str_split` and matches the `(?s)`-prefixed pattern per multi-line record; `ComponentPreview` mirrors it for previews)*. The `package.ps1` dir-pre-creation claim was **stale, corrected in the doc** (no adapter-specific dir creation exists; that's the `ura` CLI's `prepare-inbox`) | Shipped | `FEATURE_INVENTORY.md` §6 |
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
