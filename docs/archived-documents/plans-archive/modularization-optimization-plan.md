# Modularization · Optimization · Reuse Plan

**Date:** 2026-07-08 (rev 2 — backend deep-dive incorporated) · **Status:** COMPLETE 2026-07-21 —
MUST + SHOULD drained (S1 skipped by design), actionable COULD items resolved (C3 trimmed+measured,
C5 stale-done, C7 documented-reserved), S5 steps ①② shipped (fp-api + fp-config extracted).
Remaining trigger-gated items (WS-D/C1 reactor-split tail, C2, C4, C6) moved to `BACKLOG.md` §5.
Durable as-built facts distilled to `docs/okf/backend/modules/reactor.md`. ARCHIVED.
**Scope:** Whole repo — Maven reactor (6 Java modules), Angular SPA (`inspecto-ui/`), build/packaging, repo hygiene.
**Related plans (not duplicated here):** `component-model-adoption-plan.md` (component metamodel),
`transportability-plan.md` (bundle portability), `rbac-groundwork.md` (security split) — these three now in
`../archived-documents/plans-archive/` (shipped; durable content in `../okf/backend/`) —
`agent-kernel-replacement-plan.md` (eoiagent), `embedded-intelligence-plan.md` (inspecto-intelligence charter).
This plan covers the *structural* dimension none of those own: reactor boundaries, coupling chokepoints,
reuse debt, and dead weight.

---

## 1. Findings (evidence base)

### 1.1 Reactor & build

- Parent `pom.xml` (`file-processor-parent` 4.0.0-SNAPSHOT) has **no `<dependencyManagement>`** — every
  module pins its own literal versions. Duplicated pins: `langchain4j` 1.16.3 (3 modules), `eoiagent`
  0.1.0-SNAPSHOT (2), JUnit block copy-pasted into all 5 siblings (core hardcodes `5.10.2` instead of
  the parent's `${junit.version}`), `postgresql` 42.7.4 (2), langchain4j-open-ai exclusion (2).
- Jacoco `coverage` profile exists **only** in `inspecto` and `inspecto-agent` — the other modules
  cannot be instrumented in a reactor coverage pass.
- Dependency graph is a clean hub-and-spoke: every sibling → `file-processor` (core); only
  `inspecto-agent-hosted` and `inspecto-intelligence` also depend on `file-processor-agent`.
  `inspecto-security` builds only under `-Pedition-standard` (by design, editions = build flavors).
  Shade/fat-jar lives only in core (correct).

### 1.2 Backend coupling chokepoints (`inspecto/`)

- **`service/SourceService.java` (1,178 lines)** — the domain-layer god object: pipelines, jobs, events,
  connections, RCA templates, catalog wiring, enrichment, agent lookup, coalescers (~8 responsibilities).
- **`control/ControlApi.java` (643 lines)** — HTTP dispatcher *and* manual DI root: lines 344–353 `new`
  28 route modules in a hardcoded list; also owns TLS, CORS, idempotency, v1 envelope.
- `control/` imports reach into pipeline(40), config(24), service(18), event(14), query(13), ops(9),
  catalog(6), etl(4), acquire(3), job(3), … — no facade between HTTP layer and domain.
- **`inspecto-agent` bypasses its SPI seam**: 180 direct imports of core internals (`catalog` 26,
  `config` 20, `sql` 7, `etl` 5, `service` 5, …) vs only ~11 through `assist.spi`. It cannot be cut
  loose without a proper facade.
- **`inspecto-connectors` is already clean** — depends only on `acquire` (59) + `etl` (6); the
  `SourceConnectorFactory` SPI is doing its job. Note: connectors reuse `acquire/CircuitBreaker` and
  `acquire/retry/RetryPolicy`, so any split must ship the whole `acquire` package as the SPI artifact.

### 1.3 What is already healthy (preserve, don't churn)

- Route plumbing centralized: one `ApiException`, `ApiContext.JSON`, `ApiContext.requireCapability/
  withCapability` — no per-route duplication.
- Consistent `InMemory*`/`Db*` store-pair convention (acquire ledger, ops object/link/note stores,
  job runs, status, notifications, events) — deliberate pattern, not accidental duplication.
- `util/DuckDbUtil` is the single DuckDB access point (~30 call sites). Retry/circuit-breaker single-sited.
- Existing SPI seams: `SourceConnectorFactory`, `Authenticators`, `TokenRelays`, `assist/spi/AssistAgent`,
  `catalog/spi/DescriptionProvider`, `intelligence/spi/IntelligenceAgent`, `PipelineNodeTypes`,
  `notify/NotificationChannel`.
- UI: all 34+ feature routes lazy; a **respected** central API layer (`inspecto/api/`, ~40 typed
  services — no feature bypasses it); one shared grid wrapper used by 30 features; signals throughout.

### 1.4 Reuse debt & dead weight

- **UI Fuse-template leftovers (~25,800 lines)**: `src/app/mock-api/` (42 files, superseded by
  `inspecto/mock/`, still imported in `app.config.ts:17` + `app.component.ts` + layout search),
  `modules/auth/` (real auth lives in `inspecto/api/`), `modules/commons/` (`security-principal.ts`
  424 lines, `app.http.service.ts`, …).
- **UI god components**: ~~`link-analysis.component.ts`~~ **(S2, 2026-07-21: split into
  Toolbox + QueryPanel children; component.ts 1124→774)**; ~~`studio/geo-map/geo-map.component.ts`~~
  **(S2, 2026-07-21: geo-intelligence analysis cluster extracted into
  `GeoAnalysisToolboxComponent`; host keeps the MapLibre camera + `emphasis` computed, child emits
  `GeoAnalysisFocus`; mounted `[hidden]` so results survive the toggle). S2 COMPLETE — both studios split.**
- ~~Three metric panes hand-build Chart.js configs~~ **(S3, 2026-07-21: stale — dashboard/jobs/
  collectors + case-analytics.dialog already use `<inspecto-chart>`);** ~~`connection-workbench`
  uses raw `AgGridAngular`~~ **(S3: migrated onto `<inspecto-data-table tier="standard">`).**
- `inspecto/grid/index.ts` registers `AllCommunityModule` — every one of ~30 lazy grid chunks carries
  all ag-Grid community modules.
- Root clutter: `file-processor-deploy-old/` (stale Jun-20 bundle), `test-run.log` (410 KB), two
  mangled `C:Users…build.log` files, `HANDOVER-multi-space.md` (superseded by
  `SESSION_STATUS.local.md`), tracked `*.iml`, stray `file-processor-deploy/serve-8091.log`.

### 1.5 Structural performance smells

- `SourceService.java:124–172` — four unbounded `ConcurrentHashMap`s (`rcaTemplates`, `connections`,
  `lastRunAtMs`, `eventCoalescers`), no eviction.
- `event/EventLog.java:61` — static process-lifetime `SPACES` map; verify `SpaceManager` deregistration
  wires cleanup.
- **DuckDB audit (RESOLVED, second pass):** all 10 `DuckDbUtil.openConnection` call sites open a
  fresh temp-file DB, closed via try-with-resources; no pooling anywhere. On query/BI/preview/dry-run
  HTTP routes (`QueryRoutes:92`, `BiRoutes:106`, `InvRoutes:77`, `ShareRoutes:127`,
  `ComponentRoutes:182-230`, `PipelineRoutes:243`) this is **by design** — `SqlSandbox`
  (`sql/SqlSandbox.java:11-36`) documents open→seal→run→destroy as the untrusted-SQL security
  boundary. Do NOT pool that path. Per-*run* opens in `PipelineJobRunner:138` / `EnrichmentEngine:82`
  are the only legitimate reuse candidates, and only if profiling shows open cost matters.
- Positives: bounded `InMemoryEventStore` ring buffer; real `ScheduledExecutorService` in
  `service/Scheduler`; no ad-hoc `DriverManager.getConnection` outside `DuckDbUtil`.

### 1.6 Test coverage distribution

| Module | main / test classes | Verdict |
|---|---|---|
| inspecto (core) | 342 / 228 | well covered |
| inspecto-agent | 87 / 34 | moderate |
| inspecto-connectors | 17 / 8 | moderate (embedded SFTP/FTP servers) |
| inspecto-security | 3 / 2 | proportionate |
| inspecto-intelligence | 14 / **2** | near-zero |
| inspecto-agent-hosted | 2 / **1** | minimal |

### 1.7 Backend deep-dive (second pass, 2026-07-08)

Full layer map, package inventory, SPI table, and pattern inventory now live in
**`docs/architecture-layers.md`** — this section keeps only what changes the plan.

**Design patterns — used vs missing:**
- Working: Composition Root (`SourceService` ctor), Facade + Service Locator (`ApiContext`),
  Strategy (`BatchIngestStrategy`, `OutputFormat`, `TransformCompiler`, `RouteModule`), SPI Registry
  (8 ServiceLoader seams), Bridge (`EventObjectBridge`), bounded ring buffer (`InMemoryEventStore`).
- Missing/misapplied: `RouteModule` is a Strategy **not wired as a Registry** (hardcoded
  `List.of(new …)` of 24 modules at `ControlApi.java:335-355`; interface + impls are
  *package-private*, which ServiceLoader forbids — making them public is most of the conversion);
  **no middleware/filter chain** — `ControlApi.dispatch` (359-460) inlines correlation-id, v1
  versioning, CORS, idempotency, space routing, auth, audit, legacy-metrics in one method;
  **missing template method** — assist-agent vs intelligence-agent register/lookup/close blocks in
  `SourceService` (559-596, 603-610, 1125-1132) are copy-pasted (`OptionalAgentSlot<T>` collapses
  them); `SourceService` itself is the god-object anti-pattern.

**`SourceService` decomposition (method-level, refines M2):** extract in this order —
`PipelineScheduler` (poll/trigger cluster: 599-789 + 1044-1120; owns `ingestLock`, coalescers,
`triggerWorkers`, `liveRuns`), `ConnectionRegistryFacade` (479-521), `RcaTemplateRegistry`
(463-475, trivial), `AgentHost`/`OptionalAgentSlot` (559-610). Two contracts MUST survive
extraction: (a) the single shared `ingestLock` across all three ingest entry paths — never clone
it; (b) event subscriptions happen **before `start()`** and `close()`'s hand-sequenced order
(1122-1151) becomes an explicit ordered shutdown list, not implicit code order.

**Package cycles that block the reactor split (new blocker list for WS-D):**
- `service ↔ job ↔ pipeline.exec` (3-way; `BatchEventBus` + `CronExpression` live in `service`
  but are consumed below — move them down a layer, extract job contract types).
- `service ↔ catalog` (`CatalogOverlay` → `service.StatusStore`; interface belongs lower).
- `ops ↔ ops.link` / `ops ↔ ops.workflow`, `catalog ↔ catalog.spi` (shared types — relocate).

**SPI surface audit (refines M3):** the future `agent.spi` facade must cover what
`inspecto-agent` actually imports: catalog graph read/write + `DescriptionProvider` registration,
config parse/validate (`ConfigCodec`/`ConfigSpecs`/`ConfigSafetyValidator`), sandboxed SQL
(`SqlOracle`/`SqlSandboxPolicy`), `PipelineConfig`/`BatchEvent`, `CronExpression` + `StatusStore`
+ host registration, `JobConfig`, `ReportService` windows, enrichment audit reads.
`inspecto-intelligence` additionally needs a **core-owned model-settings bridge** (today it
compile-depends on inspecto-agent's `AssistModelSettings`/`ProviderSettings`/`ModelTier`) and its
`RepoPaths` monorepo-filesystem walk must gain a packaged-artifact mode. `inspecto-connectors` and
`inspecto-security` are already API-jar-clean. **(S8, 2026-07-21: all 6 seam interfaces now carry
`@PublicApi` — `Authenticator`/`TokenRelay`/`CollectorConnectorFactory`/`DescriptionProvider`/
`IntelligenceAgent` at 4.0.0, `NotificationChannel` at 4.4.0 — joining `AssistAgent` and the
zero-implementor `PipelineNodeType`.)**

**Lifecycle gaps (new):** `SourceService.close()` has no overall shutdown deadline (a hung agent
close blocks the JVM shutdown hook); `ControlApi.close()` calls `http.stop(0)` — no in-flight
drain before spaces close under live requests; `SpaceManager.delete` (215-233) calls `ctx.close()`
uncaught, so a throw leaves the space half-removed.

---

## 2. Target architecture

### 2.1 Guiding constraints (binding)

1. **Framework-free stays** — JDK HttpServer, manual DI, ServiceLoader SPI. No Spring/Quarkus
   (per `docs/EDITIONS.md` + security-hardening direction).
2. **One deployable stays** — the fat `file-processor.jar`; modularization is *reactor-internal*,
   not a microservice split.
3. **Editions = build flavors, never branches** (`docs/BRANCHING.md`).
4. Glossary vocabulary is binding across every touched layer (`docs/GLOSSARY.md` §13).

### 2.2 Seams before splits

The module split is *blocked* until two god objects shrink and two seams exist:

1. **`SourceService`/`CollectorService` decomposition** — split by domain into thin services the host
   composes (pipeline-service, job-service, connection/RCA registry, event coalescing), leaving the
   service a ≤200-line orchestrator.

   **1a. Progress + the PipelineScheduler extraction map (as of 2026-07-21).**
   - **Step 1 SHIPPED (`06d54e0`):** the two standalone in-memory registries pulled out —
     `RcaTemplateRegistry` + `ConnectionProfileRegistry` (both in `com.gamma.service`, package-private).
     `CollectorService` holds one field each and delegates its public accessors unchanged. The
     process-wide `ConnectionRegistry` mirror (under space-scoped `underSpace(...)`) and
     `connectionInUse` (reads `configRegistry`) stayed on `CollectorService` verbatim. Both were
     verified standalone: untouched by any constructor overload and by `close()`, no lock/scheduler/bus
     coupling, populated only post-construction by `ServiceBootstrap`.
   - **Step 2 SHIPPED (`b6ac2c0` tests + `f40e2d9` extraction).** `PipelineScheduler` (package-private,
     `com.gamma.service`) now owns the poll-cycle body (`runCycle`, ex-`runAllOnceInSpace`), T13 gating
     (`dueThisTick`/`cronDue`), the event hand-off (`onUpstreamCommit`/`triggerMatches`), and the
     `lastRunAtMs`/`eventCoalescers` state. It receives **shared refs** (never clones) to `ingestLock`,
     `bus`, `triggerWorkers`, `registry`, `configRegistry`, `paused`, `running`, `maxConcurrentRuns`, plus
     two callbacks for what stayed on `CollectorService`: `runPipeline` (sync run-by-name, same lock) and
     `syncStatus`. `runAllOnce()` stays public, wraps `underSpace(pipelineScheduler::runCycle)`;
     `runPipeline` stamps cadence via `scheduler.recordManualRun(...)`. Control-API-facing paths
     (`runPipeline`, `triggerRunAsync`, `register`/`unregisterPipeline`, `pause`/`resume`, `liveRuns`) and
     the exact `close()` order are unchanged. **Guarded by** the new `CollectorServiceIngestLockTest`
     (reflectively holds `ingestLock`, asserts both the poll cycle and the manual trigger block on the one
     lock — turns a cloned-lock regression into a red test) + the existing `CollectorServiceTriggerTest`
     (event hand-off / cadence). inspecto: **1471 tests, 0 failures**. The remaining `SourceService`
     decomposition candidates from §1.7 (`ConnectionRegistryFacade`, `AgentHost`/`OptionalAgentSlot`) are
     lower-risk and can follow independently.
   - **Original step-2 hazard notes (kept for provenance).** This was the repo's most dangerous refactor.
     Facts that governed the extraction:
     - **One `ReentrantLock ingestLock`** (`CollectorService` field) serializes **three** ingest entry
       paths — the scheduled poll cycle (`runAllOnce`→`runAllOnceInSpace`), the sync trigger
       (`runPipeline`), the async trigger (`triggerRunAsync`→`triggerWorkers`) — plus registry mutation
       (`registerPipeline`/`unregisterPipeline`). **It must stay a single shared instance**; cloning it
       into a new class = silent live deadlock, no compile error.
     - **`BatchEventBus.publish()` is synchronous on the publishing (lock-holding) thread.** A pipeline
       run publishes committed-batch events *while holding `ingestLock`*; the only reason a downstream
       EVENT-triggered run doesn't self-deadlock is that `onUpstreamCommit` hands it to `triggerWorkers`
       (a virtual thread) instead of running inline. **Keep that off-thread hand-off verbatim** — any
       "simplification" to an inline `runPipeline`/`coalescer.signal` reintroduces the deadlock.
     - **Safest boundary (staged, not opaque):** move only `runAllOnce`/`runAllOnceInSpace`,
       `dueThisTick`/`cronDue`, `onUpstreamCommit`/`triggerMatches`, and `eventCoalescers`/`lastRunAtMs`
       into `PipelineScheduler`. **Leave** `runPipeline`, `triggerRunAsync`, `pipelineRunById`,
       `liveRuns`, `registerPipeline`/`unregisterPipeline`, `pause`/`resume` on `CollectorService`
       (Control-API-facing). `PipelineScheduler` receives **shared refs** to `ingestLock`,
       `triggerWorkers`, `running`, `paused`, `bus`, `configRegistry`, `registry`, `scheduler`,
       `status`/`syncStatus` callback, and the `underSpace(...)` wrapper.
     - **Invariants to preserve:** the `underSpace(...)` MDC wrap on every relocated method (per-space
       routing correctness); the exact `close()` shutdown order (`watcher → agent → jobs →
       triggerWorkers → enrichment → … → scheduler`); the constructor's single
       `configRegistry.rebuild(this.registry)` pre-population before `start()`.
     - **Do the prep first:** write characterization tests pinning ingestLock serialization / the
       event-trigger off-thread hand-off / run non-overlap BEFORE moving code (existing coverage:
       `CollectorServiceTest`, `CollectorServiceTriggerTest`).
2. **`RouteModule` ServiceLoader registration** — replace the hardcoded `List.of(new …)` of 24
   modules in `ControlApi.registerRoutes` (335–355) with ServiceLoader discovery. Confirmed
   near-zero-cost: every module is already no-arg constructible, stateless, and pulls deps from
   `ApiContext` at `register()` time; the only real change is making `RouteModule` + implementors
   `public` (ServiceLoader requires it) and adding the `META-INF/services` file. Optional route
   groups then self-register exactly like `Authenticators` already does.
   2b. **Cycle-breaking moves** (blocker list from §1.7): relocate `BatchEventBus` +
   `CronExpression` out of `service` to a lower layer; move `StatusStore` below `catalog`; relocate
   shared types in `ops↔ops.link/workflow` and `catalog↔catalog.spi`.
3. **`com.gamma.agent.spi` seam** — **DONE (`fc772f0`) as a `@PublicApi` freeze, not a wrapping facade**
   (see M3 row for why: small inventory, already via `UccAgentContext`, reactor-internal so a DTO wrapper
   pays nothing). The consumed core surface is frozen `@PublicApi(since=4.0.0)`; `UccAgentContext` stays
   the single access seam.
4. **`acquire` as a self-contained SPI package** — it nearly is already; keep retry/circuit-breaker
   inside it so connectors need exactly one artifact.

### 2.3 Eventual reactor shape (after seams land)

```
file-processor-parent
├─ fp-acquire        acquire/ (SourceConnector SPI, ledger, retry, circuit breaker)
├─ fp-config         config/ (ConfigSpec, ConfigSafetyValidator, ConfigCodec)
├─ fp-core-etl       etl/, inspector/, pipeline/  → depends on fp-acquire, fp-config
├─ fp-catalog        catalog/, query/, sql/
├─ fp-ops            ops/, event/, alert/, notify/
├─ fp-control        control/ (HTTP; depends on all above; routes via ServiceLoader)
├─ fp-host           service/ (thin composition root; shade/fat-jar lives here)
├─ …existing siblings (agent, agent-hosted, connectors, intelligence, security) unchanged
```

Extraction order: `fp-acquire` → `fp-config` → `fp-catalog`/`fp-ops` → `fp-core-etl` → `fp-control`
last (it depends on everything). Each step = move packages + fix imports + full reactor
`mvn -o clean test` green; no behavior change per step. Artifact/deployable output identical throughout.

---

## 3. Workstreams

### WS-A · Build & repo hygiene (independent, do first)
A1. Parent `<dependencyManagement>` + shared properties (`junit.version` actually used, `langchain4j.version`,
    `eoiagent.version`, `postgresql.version`); delete per-module literals.
A2. Move jacoco `coverage` profile to the parent so all modules instrument.
A3. Delete `file-processor-deploy-old/`, root logs, mangled build-log files, `serve-8091.log`,
    `HANDOVER-multi-space.md`; untrack `*.iml`; extend `.gitignore`.

### WS-B · UI reuse & dead weight
B1. Remove Fuse `mock-api/` (rewire `app.config.ts`, `app.component.ts`, layout search to
    `inspecto/mock/`), then `modules/auth/`, then `modules/commons/` (~25.8k lines).
B2. Route `dashboard`/`jobs`/`sources` chart panes through the design-system `chart.component.ts`.
B3. Migrate `connection-workbench` onto `<inspecto-data-table>`.
B4. ~~Split `link-analysis.component.ts` and `geo-map.component.ts`: extract presentational children each~~
    **DONE 2026-07-21 (S2)** — link-analysis → Toolbox + QueryPanel; geo-map → GeoAnalysisToolbox.
B5. Trim ag-Grid `AllCommunityModule` to the module set actually used.

### WS-C · Backend seams (prerequisites for the split)
C1. `SourceService` decomposition (see §2.2.1) — behavior-preserving, test-guarded.
C2. `RouteModule` ServiceLoader registration in `ControlApi`.
C3. `agent.spi` facade; migrate `inspecto-agent` + `inspecto-intelligence` onto it.

### WS-D · Reactor split (only after WS-C)
D1–D6. Extract modules in the order of §2.3, one per change, reactor green each step.

### WS-E · Optimization & robustness
E1. Bound/evict the four `SourceService` maps; verify `EventLog.SPACES` cleanup on space removal.
E2. Audit DuckDB connection lifecycle under `control/` routes; pool or cache per space if per-request.
E3. Bring `inspecto-intelligence` (14/2) and `inspecto-agent-hosted` (2/1) to baseline test coverage.

---

## 4. MoSCoW analysis (implementation/design aspects)

### MUST (blocking correctness, safety, or all later work)

| # | Item | Why must |
|---|---|---|
| M1 | Parent `dependencyManagement` + version properties (A1) — **SHIPPED 2026-07-21 (`73ea9a1`)**: parent now manages junit/langchain4j/eoiagent/postgresql; per-module literals + duplicate version properties removed. | Version drift across 6 poms is a live risk (core already ignores the parent's `junit.version`); prerequisite for adding modules without multiplying the drift. |
| M2 | `SourceService`/`CollectorService` decomposition (C1) — **step 1 SHIPPED 2026-07-21 (`06d54e0`)**: Rca/Connection registries extracted. **Step 2 SHIPPED 2026-07-21 (`b6ac2c0`+`f40e2d9`)**: `PipelineScheduler` extracted (the high-risk poll/trigger cluster), shared-`ingestLock` invariant pinned by a characterization test. Remaining lower-risk candidates (ConnectionRegistryFacade, AgentHost/OptionalAgentSlot) optional — see §2.2.1a. | 1,178-line god object is the single blocker for every modularization step and the top defect-risk concentration. |
| M3 | `agent.spi` seam (C3) — **SHIPPED 2026-07-21 (`fc772f0`) as a CONTRACT FREEZE, not a wrapping facade.** Live inventory corrected the premise: `inspecto-agent` imports **~38 distinct** core types across 10 packages (not 180), `pipeline`/`query` have **zero** agent imports, and access already funnels through `UccAgentContext`. Since the modularization is reactor-internal (one deployable, W2), a DTO-wrapping `agent.spi` would duplicate data shapes for no payoff. Instead marked exactly the consumed surface (~31 types: catalog, config.io/safety/spec, sql, signal, service Cron/Status, etl.BatchEvent, enrich, job, ReportService.Window) `@PublicApi(since=4.0.0)`, so the architecture rule "L5 touches core only through SPI + @PublicApi" is now honored. Documented in `docs/okf/backend/control-plane/api-stability.md`. | 180 concrete imports of core internals make `inspecto-agent`/`-intelligence` unable to evolve independently; the eoiagent migration plan depends on this seam. |
| M4 | Remove Fuse leftovers (B1) — **increment 1 SHIPPED 2026-07-21 (`80d6366`)**: dead demo mocks deleted (−25,079 lines). **Increment 2 SHIPPED 2026-07-21 (`427e240`)**: deleted the wired `mock-api/common/{navigation,shortcuts,user,auth}` + `MockApiService` + `modules/auth` + `modules/commons` (~33 files); rehomed `defaultNavigation` → `core/navigation/navigation-data.ts` and moved the merge logic into `NavigationService.get()` (client-side `of(...)`, no `api/common/navigation`); de-wired `app.component`/`user.component` off Fuse `SecurityPrincipal`/auth/commons; dropped `provideGamma` `mockApi` + `AUTH_HTTP_CLIENT`. Verified lint:tokens/build/test:ci + live preview (sidebar renders client-side). angular-ui skill updated (nav-items home moved). **M4 COMPLETE.** | ~25.8k dead lines *partially wired into the app config* — real bundle weight, security surface, and constant confusion for new shifts. |
| M5 | Coverage baseline for intelligence + agent-hosted, jacoco in all modules (E3, A2) — **SHIPPED 2026-07-21 (`eeb4d5f`)**. A2: jacoco `coverage` profile moved to the parent POM — `mvn -Pcoverage test` now instruments every module (intelligence/hosted/connectors verified, not just core+agent). E3: +30 deterministic unit tests on the cheap pure units — intelligence 138→164 (ActionBudget, CaseSimilarity, AgentWriteRoot, DurableJsonlRing base class, InspectoPolicyProfile), hosted 4→8 (LangChain4jChatProvider.available()). Remaining untested classes are the integration-scaffolding ones (ControlPlaneClient, model build/generate) — deferred by design. | Near-zero-tested modules ship in every reactor build; untestable modules can't be refactored safely later. |
| M6 | Repo hygiene sweep (A3) — **SHIPPED 2026-07-21 (`b554048`)**: most targets (deploy-old, root logs, `serve-8091.log`, tracked `*.iml`) were already gone/gitignored by prior shifts; removed the last two — stale `HANDOVER-multi-space.md` + a mangled root build-log. | Stale 96-MB-scale bundle copy + logs in a shared sandbox; trivially cheap, removes handover noise. |

### SHOULD (high value, not blocking)

| # | Item | Why should |
|---|---|---|
| S1 | ~~`RouteModule` ServiceLoader registration (C2)~~ **SKIPPED 2026-07-21** | Scope map (all 38 impls package-private, intra-module, always-present, no-arg) showed conversion would force 39 types public — *widening* API surface, the opposite of M3's freeze — for **zero present benefit**: unlike the `Authenticator` precedent it mirrors, no `RouteModule` is edition-optional (all live in `inspecto` core, always present). Only payoff is the fp-control extraction (C1), which is not scheduled. Revisit as part of C1, not standalone. |
| S2 | Split UI god components (B4) — **DONE 2026-07-21 (both studios)** | **link-analysis** fully split into presentational children: `LinkAnalysisToolboxComponent` (`ab90d7c`) + `LinkAnalysisQueryPanelComponent` (`766360b`); component.ts 1124→774, template 806→369; each child independently spec'd. Bottom panel restructured to `[hidden]` gates so children keep state across tab switches. **geo-map** split (`57ab9696`): geo-intelligence analysis cluster (co-location / frequent locations / stay points + params + result state) extracted into `GeoAnalysisToolboxComponent`; the host keeps the MapLibre camera + the `emphasis` computed (`resultEmphasis`) and flies the map in `focusResult()`, the child emits a `GeoAnalysisFocus` on a result click; mounted `[hidden]` so results survive the toolbox toggle and `clearAnalysis()` reaches it via `@ViewChild`. lint:tokens + prod build + test:ci (1549) all green. |
| S3 | ~~Chart + grid consolidation (B2, B3)~~ **DONE 2026-07-21** | **B2 was already shipped** (all 4 Chart.js consumers — dashboard/jobs/collectors/case-analytics.dialog — already route through `<inspecto-chart>`; zero hand-rolled `new Chart()` panes remained). **B3 done**: migrated `connection-workbench`'s Sample preview from raw `<ag-grid-angular>` onto `<inspecto-data-table tier="standard">` (gains search / column-chooser / CSV export from the shared toolbar; dropped the now-duplicate hand-rolled Download-CSV button, kept Copy-CSV since the table has no clipboard action). lint:tokens + prod build + test:ci all green. |
| S4 | ~~Bound `SourceService` maps, `EventLog.SPACES` cleanup (E1)~~ **DONE 2026-07-21 — mostly moot + one real gap fixed** | Recon verdict: space *teardown* was already clean — `SpaceManager.delete`→`CollectorService.close` unwinds every space-id-keyed static map (`EventLog.SPACES` via `EventLog.unregister`, plus `AcquisitionLedgers`/`ConnectionRegistry`/`StabilityGate`/`DecisionRules` `forget`), and the M2 registries are per-service-instance (die with the space; `ConnectionProfileRegistry` even has `remove`). The one genuine leak was *within* a long-lived space: `PipelineScheduler`'s `lastRunAtMs` + `eventCoalescers` (keyed by pipeline id) were never pruned on pipeline delete → orphan entry per deleted pipeline under churn. **Fix:** added `PipelineScheduler.forget(id)`, called from `CollectorService.unregisterPipeline` (+ `paused.remove(id)` for the cosmetic paused-set leak). Regression test `CollectorServicePipelineForgetTest` (reflection-based). inspecto suite 1473 green. **Deferred (not leaks):** `RcaTemplateRegistry` has no `remove` but is boot/config-bounded; `AcquisitionLedgers`' 3 path-keyed maps self-clean by design. |
| S5 | Extract `fp-acquire` + `fp-config` (D1, D2) — **steps ①② SHIPPED 2026-07-21 (`bc4d5f4d` fp-api, `0398a02b` fp-config); ③ fp-acquire stays blocked on WS-D.** ① `inspecto-api` = dependency-free leaf holding `com.gamma.api.PublicApi`; core depends on it; `package.ps1` step 1 now builds `-pl inspecto -am` from the root (core-alone build can't resolve siblings — a gotcha the plan missed). ② `inspecto-config` = `com.gamma.config` (13 main + 3 test classes, package unchanged → zero core import edits); deps fp-api + jtoon + jackson (versions hoisted to parent, M1 rule); the shipped-examples round-trip test stayed in core (`ShippedExamplesRoundTripTest`) with the `examples/` fixture it walks (surefire CWD = module root). Both gated on full reactor `mvn -o clean test` green (8/8 modules, 1884 tests). Original recon (kept below) had corrected the premise: | ⚠️ The "both clean enough to move, low risk" premise is **wrong at the Maven-module level** (it held only for package-import acyclicity, not module extraction). Findings: **(a) `com.gamma.api.PublicApi`, `com.gamma.etl`, `com.gamma.util`, `com.gamma.event` all live INSIDE core (`inspecto`)** — there is no leaf `api` module. **(b) fp-config is NOT independently extractable:** it imports `com.gamma.api.PublicApi` (in core) while core imports config in 32 files → a module cycle; extracting it first needs `com.gamma.api` pulled into a leaf **`fp-api`** module (then `fp-api → fp-config → core`). **(c) fp-acquire is blocked:** it imports `com.gamma.config.io` (ok→fp-config), but also `com.gamma.etl`, `com.gamma.util`, `com.gamma.event` (all core) while core imports acquire (service/control/inspector/job, 14 files) → module cycle; extracting acquire first requires extracting etl+util+event = **the full WS-D split, not a low-risk item.** Positive: no package-import *cycles* exist (config→only PublicApi; acquire→config.io/etl/util/event/PublicApi, none import back), and `CollectorConnectorFactory` is already a proven SPI seam (`inspecto-connectors` implements it). **Corrected path (for a dedicated future shift):** ① extract `fp-api` (leaf: just `com.gamma.api`); ② extract `fp-config` (dep fp-api); ③ only then tackle acquire after etl/util/event are modularized (WS-D). Moved to `BACKLOG.md` §reactor-split. |
| S6 | ~~Middleware/filter chain for `ControlApi.dispatch`~~ **DONE 2026-07-21 (full chain, operator-chosen)** | The ~115-line monolith is now a one-line `dispatch` running a `pipeline` composed once in the ctor. Each concern is an ordered private `Middleware`: `correlation` (outermost — Correlation-ID + MDC + `ex.close()`) → `cors` (headers + OPTIONS preflight) → `errorBoundary` (ApiException→status / else→500) → `normalizePath` (v1 + `/api` strip) → `idempotency` (replay) → `bindSpace` (`/spaces/{id}` + space MDC, 404) → terminal `routeDispatch` (route match, legacy sunset, auth gate, static fallback, 404/405). `Middleware`/`Chain` functional interfaces + `compose()` are all **private** (no surface widening). The one piece of shared mutable state — the route-matching path — is threaded via a private exchange attr (`path(ex)`/`setPath(ex)`); the chain declares `throws Exception` so a handler's checked failure reaches `errorBoundary`, and `dispatch` guards it to keep its `HttpHandler`/`throws IOException` contract. Behavior-preserving line-for-line move; regression-guarded by the full `ControlApi*Test` surface (suite 1473 green). **Caveat:** S6's original driver (composable S1 route plugins) is void since S1 was skipped — the operator chose the full chain anyway (readability + per-stage testability); a lighter named-stage extraction was the recommended alternative. |
| S7 | ~~Shutdown robustness: deadline around `SourceService.close()`, drain delay in `ControlApi.close()`, try/catch in `SpaceManager.delete`~~ **DONE 2026-07-21** | All three: (1)+(3) `SpaceManager.delete`/`close` now drain each space via `closeWithDeadline()` — `ctx.close()` on a **daemon** thread bounded by `CLOSE_DEADLINE_MS`=10s (a hung agent/DB close can't hold the JVM open), exceptions logged not propagated, MDC propagated to preserve space-log routing; `delete` runs the per-space registry cleanup **regardless** of close outcome (no half-removed space). (2) `ControlApi.close()` `http.stop(0)`→`http.stop(SHUTDOWN_DRAIN_SECONDS=2)` so in-flight exchanges drain. Regression-guarded by the existing 9 `SpaceManagerTest` delete/close tests (green). No hang-path unit test added — no clean injection seam, would be speculative complexity. |
| S8 | ~~`@PublicApi`-freeze the 6 unmarked SPI interfaces~~ **DONE 2026-07-21** | Annotated `Authenticator`, `TokenRelay`, `CollectorConnectorFactory` (the post-rename `SourceConnectorFactory`), `IntelligenceAgent` with `@PublicApi(since="4.0.0")` and `NotificationChannel` with `since="4.4.0"` (matches its `@since`); `DescriptionProvider` was already annotated. Extends M3's freeze; connectors/security stay API-jar-clean. |
| S9 | ~~Decouple `inspecto-intelligence` from `inspecto-agent`~~ **DONE 2026-07-21** | **Part 1 (compile edge):** the only sibling-internal edge was 2 files importing 3 agent types (`ProviderSettings`/`AssistModelSettings`/`ModelTier`), all model-settings. Added a core-owned **read-side** bridge `com.gamma.model.ModelSettings` + `ModelSettingsStore` (loads the same `assist-settings.properties` the agent settings screen writes; tier-keyed by name string). `GatewayFactory`+`InspectoModelProfile` now read core; **`file-processor-agent` dep removed from the intelligence pom.** **Design:** did NOT move agent's types to core — `ModelTier` alone has 36 agent refs (kernel-foundational), a ~50-file churn; instead a small parallel read-side value type, agent stays the single writer (documented file-format contract). **Part 2 (`RepoPaths`):** added `-Dinspecto.repo.root` packaged-artifact override before the checkout walk-up (still degrades to empty → caller fallbacks). Test `ModelSettingsStoreTest` (core). **Verified: full reactor `test-compile` clean with the dep gone**; core 1476 + intelligence 164 green. Removes the last sibling-internal compile dep → intelligence can build against an API jar. |

### COULD (do when adjacent work touches the area)

| # | Item | Why could |
|---|---|---|
| C1 | Full reactor split fp-catalog/fp-ops/fp-core-etl/fp-control/fp-host (D3–D6) | Real payoff (build parallelism, enforced boundaries) but only safe after M2/M3/S1; deployable is unchanged, so no user-visible urgency. |
| C2 | Generic base for `InMemory*`/`Db*` store pairs | Boilerplate reduction only; the convention is consistent and correct today. |
| C3 | ~~ag-Grid module trimming / shared grid chunk (B5)~~ **DONE 2026-07-21 (`aa87b2c3`)** — measured first: ag-grid = ONE shared lazy chunk (not duplicated), 1.17 MB raw / 262 kB transfer, 2nd-largest lazy chunk. Usage audit (all 4 raw hosts) → `AllCommunityModule` replaced with the 12 actually-used modules; `ValidationModule` registers dev-only so a missing module fails loudly (error 200) in tests/preview. Result: 970 kB raw / 219 kB transfer (−200 kB / −43 kB). lint:tokens + test:ci 1549 + prod build + preview smoke green. | Bundle-size win; measure first — all chunks are lazy already. |
| C4 | BOM (`file-processor-bom`) for external consumers | Only worth it if artifacts are ever consumed outside this reactor. |
| C5 | ~~`OptionalAgentSlot<T>` template method for the duplicated assist/intelligence agent blocks in `SourceService`~~ **DONE (`defb1281`, M2 residual — row was stale; found in the 2026-07-21 completion review)**: `OptionalAgentSlot` is a private nested class in `CollectorService`, both register/lookup/close blocks delegate to it. | Small dedup; falls out naturally during the M2 decomposition anyway. |
| C6 | DuckDB connection reuse for `PipelineJobRunner`/`EnrichmentEngine` per-run opens | Only after profiling shows open cost matters; **never** pool the `SqlSandbox` HTTP path — ephemeral-per-request is its security boundary. |
| C7 | Decide `PipelineNodeType` SPI fate (defined `@PublicApi 4.3.0`, zero implementors, no services file) | Ship a first plugin node type or document it as reserved; dead public API otherwise. |

### WON'T (this cycle — explicit non-goals)

| # | Item | Why won't |
|---|---|---|
| W1 | Spring/Quarkus or any DI-framework migration | Binding design tenet: framework-free JDK HttpServer + manual DI + ServiceLoader (EDITIONS.md, security-hardening direction). |
| W2 | Microservice / multi-deployable split | One fat jar + jlink runtime is the product's transportability story; modularization stays reactor-internal. |
| W3 | Rewriting the store-pair pattern to an ORM/repository framework | Working, consistent, tested; churn without payoff. |
| W4 | UI workspace split (Nx/monorepo tooling) | One SPA, healthy lazy-loading; tooling migration cost exceeds any benefit at this size. |
| W5 | Per-edition branches | Forbidden by BRANCHING.md — editions remain build flavors. |

---

## 5. Sequencing & verification

```
Phase 0 (hygiene):      A3 → A1 → A2                     · verify: mvn -o clean test green, diff = poms+deletions
Phase 1 (reuse):        B1 → B2/B3 → M5 tests            · verify: ng lint/test/build green; bundle size drop recorded
Phase 2 (seams):        C1 → C3 → C2/2b (+ S4, S6-S9)    · verify: full GAUNTLET green after each; no API change
Phase 3 (split):        D1 → D2 → (D3/D4) → D5 → D6      · verify: reactor green + package.ps1 both editions byte-compatible layout
Phase 4 (optimize):     S6 audit → E2 fix if confirmed → C3-could · verify: measured before/after
```

Every phase is independently shippable and behavior-preserving; commits follow `release-workflow`
(Conventional Commits, master, merge-forward rules). Nothing here touches the uncommitted AGT-5
hardening work — Phase 0 must start from a clean tree after that lands.
