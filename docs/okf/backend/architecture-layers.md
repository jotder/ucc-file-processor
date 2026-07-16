# Architecture Layers — Platform-Wide Design Map
> *Moved from `docs/architecture-layers.md` (docs consolidation, 2026-07-16).*

> Part of the [Inspecto](../../../inspecto/README.md) documentation. See the [docs index](../../INDEX.md).
>
> **Scope.** [`stage1-architecture.md`](engine/stage1-architecture.md) describes **Stage-1** (the M..N multiplexer ingest
> engine) in depth. This page is the **whole-platform layer map**: every `com.gamma` package, the
> dependency layering between them, the extension (SPI) surface, the event/config/storage/threading
> models, and the design patterns in use. Derived from a full import-level dependency sweep on
> 2026-07-08 (commit base `f2f9506` + working tree). Companion improvement plan:
> [`superpower/modularization-optimization-plan.md`](../../superpower/modularization-optimization-plan.md).

---

## 1. Layer model

The backend (`inspecto/`, artifact `file-processor`, ~285 files / ~38,000 lines under `com.gamma`)
is a **layered monolith** with satellite plug-in modules discovered via ServiceLoader:

```
┌─ L5 · EXTENSION MODULES (separate Maven artifacts, ServiceLoader-discovered) ─────────────┐
│  inspecto-connectors   inspecto-security   inspecto-agent   inspecto-intelligence         │
│  (SFTP/FTP/S3/Kafka/DB) (OIDC, Keycloak)   (assist skills)  (embedded agent)              │
│                                             └── inspecto-agent-hosted (plugin-of-plugin)  │
├─ L4 · HTTP / CONTROL PLANE ───────────────────────────────────────────────────────────────┤
│  control  (ControlApi dispatcher, ~50 route classes, ApiContext facade, auth gate)        │
├─ L3 · ORCHESTRATION / HOST ───────────────────────────────────────────────────────────────┤
│  service  (SourceService host, SpaceManager, Scheduler, BatchEventBus)   report           │
├─ L2 · DOMAIN ENGINES ─────────────────────────────────────────────────────────────────────┤
│  etl · inspector · acquire(+retry) · enrich · pipeline · pipeline.exec · job · query      │
│  catalog(+spi) · ops(+link/note/rca/workflow) · alert · notify(+channel) · expectation    │
│  assist(+spi) · intelligence.spi · ingester                                               │
├─ L1 · PLATFORM SERVICES ──────────────────────────────────────────────────────────────────┤
│  event (EventLog)  ·  metrics  ·  config.io / config.safety  ·  sql (SqlSandbox/Guard)    │
├─ L0 · FOUNDATION (leaf — no com.gamma dependencies) ──────────────────────────────────────┤
│  util (DuckDbUtil, ToonHelper, …)  ·  api (@PublicApi)  ·  config.spec  ·  intelligence   │
└───────────────────────────────────────────────────────────────────────────────────────────┘
```

Rules the layering implies (and mostly honors today):
- Lower layers never import higher ones (verified — no upward imports from L0/L1).
- L4 `control` may import anything below it (it does: 20+ packages) but nothing imports `control`.
- L5 modules must touch core only through SPI interfaces + `@PublicApi` types (honored by
  connectors/security; **violated** by agent/intelligence — see §4).

## 2. Package inventory

Direct files / lines, role, and outbound `com.gamma` dependencies (import counts):

| Layer | Package | Files/Lines | Role | Outbound deps |
|---|---|---|---|---|
| L0 | `util` | 27/4024 | Generic helpers: `DuckDbUtil`, `ToonHelper`, tar/CSV tools, `MainApp` CLI | — |
| L0 | `api` | 1/35 | `@PublicApi` marker annotation | — |
| L0 | `config.spec` | 8/698 | Config schema spec model (`ConfigSpecs`, `RawConfig`, `FieldSpec`) | — |
| L0 | `intelligence` | 5/83 | Agent ask/session value types + `AgentAnswerSink` port | — |
| L1 | `event` | 13/1290 | Append-only event log + pub/sub (`EventLog`, `ParquetEventStore`) | util:2, sql:1, metrics:1, etl:1 |
| L1 | `metrics` | 1/246 | Process-wide `MetricRegistry` (Prometheus) | event:1 |
| L1 | `config.io` | 3/263 | TOON codec + config loading (`ConfigCodec`, `ConfigLoader`) | config.spec:6 |
| L1 | `config.safety` | 2/304 | `ConfigSafetyValidator`, `SafetyPolicy` guardrails | config.spec:2 |
| L1 | `sql` | 5/655 | Sandboxed SQL: `SqlSandbox` (ephemeral DuckDB), `SqlGuard`, `SqlOracle` | util:2, config.spec:2 |
| L2 | `etl` | 32/4956 | Stage-1 config model/parser + CSV/DuckDB ingest engine | util:5, api:4 |
| L2 | `inspector` | 17/2938 | Source poll cycle, batch strategies, streaming engines | acquire:28, etl:~30, util:6, event:5, metrics:2 |
| L2 | `acquire` (+`retry`) | 29/2537 | Data Acquisition SPI: connectors, ledger, stability, retry, circuit breaker | event:2, etl:3, util:1, config.io:1 |
| L2 | `enrich` | 5/641 | Stage-2 enrichment engine | etl:6, util:4, sql:1 |
| L2 | `pipeline` | 23/2646 | Authored Pipeline graph model, compiler, component registry | api:22, util:6, etl:4, config.io:4 |
| L2 | `pipeline.exec` | 17/2347 | Pipeline execution runtime (`PipelineJobRunner`, `RowShaper`, previews) | pipeline:30, api:16, util:6, sql:5, job:4, event:4, etl:4, service:1 |
| L2 | `job` | 13/1588 | Job scheduling/execution/run ledger | util:8, service:5, pipeline:5, event:4, query:3, etl:3, enrich:3, pipeline.exec:1 |
| L2 | `query` | 6/629 | Measure/Dataset query execution (`QueryExecutor`, `MeasureCompiler`) | pipeline:5, sql:2 |
| L2 | `catalog` (+`spi`) | 17/1346 | Metadata graph / semantic model + `DescriptionProvider` SPI | etl:5, enrich:2, util:1, service:1 |
| L2 | `ops` (+`link`,`note`,`rca`,`workflow`) | 20/1971 | Operational object engine (Incidents/Cases), links, notes, RCA, workflow | event:6, util:5, config.io:2 |
| L2 | `alert` | 3/530 | Alert Rule evaluation over the batch ledger | event:4, ops:2, etl:2, service:1, config.io:1, catalog:1 |
| L2 | `notify` (+`channel`) | 13/870 | Notification service/rules/preferences + `NotificationChannel` SPI | event:6 |
| L2 | `expectation` | 2/250 | Data-quality Expectation model/evaluator | sql:2 |
| L2 | `assist` (+`spi`) | 4/327 | Assist value types + `AssistAgent` SPI | api:4, service:1 |
| L2 | `intelligence.spi` | 1/71 | `IntelligenceAgent` SPI | intelligence:5, service:1 |
| L2 | `ingester` | 2/259 | Reference `StreamingFileIngester` implementations | etl:6 |
| L3 | `service` | 23/4360 | `SourceService` host (1,178 lines), `SpaceManager`, `Scheduler`, `BatchEventBus`, `DbStatusStore` | etl:15, event:10, pipeline:6, enrich:6, util:6, job:5, catalog:5, inspector:3, acquire:3, +6 more |
| L3 | `report` | 1/300 | Report generation service | service:3, etl:1, api:1 |
| L4 | `control` | 49/6190 | `ControlApi` dispatcher + ~24 `RouteModule`s + `ApiContext` + auth SPI | pipeline:36, service:18, event:14, config.spec:14, query:13, +15 more |

### Known dependency cycles (module-extraction blockers)

- **`service` ↔ `job` ↔ `pipeline.exec`** (3-way): `SourceService` → `JobService`; `job` →
  `service.BatchEventBus`/`CronExpression` and → `pipeline.exec.PipelineJobRunner`;
  `pipeline.exec` → `job.Job*` types and → `service.BatchEventBus`. Break by moving
  `BatchEventBus` + `CronExpression` down a layer and extracting job contract types.
- **`service` ↔ `catalog`**: `CatalogOverlay` → `service.StatusStore` (interface belongs lower).
- **`ops` ↔ `ops.link` / `ops` ↔ `ops.workflow`**, **`catalog` ↔ `catalog.spi`**: parent↔child
  type cycles (`ObjectType`, `Description`) — resolvable by moving the shared types into the
  sub-package or a `*.model` namespace.

## 3. Composition & lifecycle

- **Composition root:** `SourceService`'s full constructor (`service/SourceService.java:293-399`)
  assembles the domain graph (stores, event log, catalog, alerting, notifications) *and* performs
  runtime event wiring — subscriptions must happen **before `start()`** (temporal contract noted in
  inline comments at 348-378). `ControlApi.main` → `SpaceManager.startAll()` → per-space
  `SourceService.start()`; a JVM shutdown hook closes `ControlApi` then `SpaceManager`.
- **Threading model:** a 2-thread daemon `Scheduler` (`service/Scheduler.java:38`) only *triggers*
  work (poll-all, sla-sweep, cron); real work hands off to per-purpose virtual-thread executors —
  `SourceService.triggerWorkers`, `ControlApi`'s HTTP executor (one virtual thread per request),
  `NotificationService`'s own executor. `ingestLock` serializes the three ingest entry paths
  (poll cycle, manual run, event trigger).
- **Shutdown:** `SourceService.close()` (`SourceService.java:1122-1151`) is a hand-ordered
  sequence (watcher → agents → jobs → workers → enrichment → subscribers → scheduler → stores →
  events last) with per-step try/catch but **no overall deadline**; `ControlApi.close()` uses
  `http.stop(0)` (no in-flight drain). These are documented gaps in the improvement plan.

## 4. Extension surface (SPI)

Eight ServiceLoader SPIs, all loaded by core:

| SPI interface | Loader | Implementations (module) | `@PublicApi` |
|---|---|---|---|
| `acquire.SourceConnectorFactory` | `acquire/SourceConnectors.java:38` | Sftp/Ftp/Ftps/DbExport/S3/Kafka (connectors) | no |
| `control.Authenticator` | `control/Authenticators.java:21` | `OidcAuthenticator` (security) | no |
| `control.TokenRelay` | `control/TokenRelays.java:19` | `KeycloakTokenRelay` (security) | no |
| `assist.spi.AssistAgent` | `service/SourceService.java:604` | `UccAssistAgent` (agent) | **yes** (3.0.0) |
| `intelligence.spi.IntelligenceAgent` | `service/SourceService.java:609` | `InspectoIntelligenceAgent` (intelligence) | no |
| `catalog.spi.DescriptionProvider` | `catalog/MetadataGraphService.java:62` | `Noop` (core), `AiDescriptionProvider` (agent) | no |
| `notify.NotificationChannel` | `notify/NotificationService.java:69` | `WebhookChannel` (core), `SmtpEmailChannel` (connectors) | no |
| `pipeline.PipelineNodeType` | `pipeline/PipelineNodeTypes.java:31` | **none** — SPI defined, zero implementors/services files | **yes** (4.3.0) |

Second-order SPI: `com.gamma.agent.model.HostedProviderPlugin` (owned by *inspecto-agent*, not
core) — implemented by `inspecto-agent-hosted`'s `LangChain4jProviderPlugin`. Hosted never touches
core at all.

### Seam health per module

| Module | Core surface actually used | API-jar-buildable today? |
|---|---|---|
| inspecto-connectors | `acquire.*` (10 types), `etl.PipelineConfig`, `notify.{Notification,NotificationChannel}` | **Yes** — clean; needs only `@PublicApi` freezing |
| inspecto-security | Exactly 4 types: `Authenticator`, `TokenRelay`, `Subject`, `SecretResolver` | **Yes** — tightest seam in the repo |
| inspecto-agent | catalog(10) + config(10) + sql(2) + etl(2) + service(3) + job(1) + report(2) + assist(5) + enrich(2) — direct internals, no facade | **No** — needs an `agent.spi` facade covering that surface |
| inspecto-intelligence | Clean SPI (`IntelligenceAgent`, `AgentAnswerSink`) **but** compile-deps on inspecto-agent's `AssistModelSettings`/`ProviderSettings`/`ModelTier`, plus `RepoPaths` monorepo-layout filesystem walk | **No** — needs a core-owned model-settings bridge + packaged-artifact path resolution |
| inspecto-agent-hosted | none (only inspecto-agent types) | N/A — isolated by construction |

## 5. Event architecture — two distinct buses

1. **`event.EventLog`** — the platform event log: per-space-routed append log + pub/sub
   (`CopyOnWriteArrayList` subscribers). *Writers:* `AlertService`, `control.AuditTrail`,
   `ExpectationRoutes`, `EventStoreAppender` (SLF4J bridge), `inspector.AcquisitionTelemetry`,
   `ReportJob`, `ObjectService`, `PipelineJobRunner`, `SourceService`. *Subscribers* (wired only in
   `SourceService.java:347,384`): `ops.EventObjectBridge` — promotes `SEQUENCE_GAP` /
   `FLOW_CONSERVATION_IMBALANCE` events into managed ALERT objects — and the notification
   subscriber.
2. **`service.BatchEventBus`** — the batch-commit fan-out (`Consumer<BatchEvent>`), subscribed by
   `JobService`, `EnrichmentService`, `MetricsService`, `SourceService.onUpstreamCommit` (event
   triggers). **Do not conflate the two.**

## 6. Configuration layer

- All durable config is TOON, parsed **only** through `config.io.ConfigCodec` (no `#` comments
  permitted). Spec/validation split: `config.spec.ConfigSpecs` (declarative schema per config
  type) + `config.safety.ConfigSafetyValidator` (guardrails), both L0/L1 and dependency-free.
- Direct `ConfigCodec` consumers: `pipeline` (5), `control` (3), `service` (2), `ops.workflow`,
  `ops.rca`, `alert`, `acquire` (1 each). Everything else touches config through `PipelineConfig`
  (Stage-1) or the config registry.

## 7. Storage layer

- **DuckDB, open-per-use, never pooled** — every one of the 10 `DuckDbUtil.openConnection` call
  sites opens a fresh temp-file DB and closes it in try-with-resources. On the query/BI/preview
  HTTP paths this is **deliberate**: `SqlSandbox` (`sql/SqlSandbox.java:11-36`) frames
  open-unsealed → seal → run → destroy as the untrusted-SQL security boundary. Per-run (not
  per-request) opens: `PipelineJobRunner`, `EnrichmentEngine`, ETL ingest strategies.
- **Store pattern:** interface + `InMemory*`/`Db*` pair, consistently applied — acquisition
  ledger, object/link/note stores, job runs, status, notifications, events (bounded ring buffer
  in-memory; Parquet event store durable).
- Output data is Hive-partitioned Parquet under the Space tree; the warehouse layer
  (`pg_duckdb`) queries it externally — see `integrations.md`.

## 8. Design-pattern inventory

**In use, working:**
- *Composition Root* — `SourceService` ctor (overloaded: also does event wiring at construction).
- *Facade + Service Locator* — `control.ApiContext` over host/spaces; routes pull deps at call
  time, no constructor injection; route instances are stateless.
- *Strategy* — `BatchIngestStrategy` (Csv vs StreamingPlugin), `OutputFormat` (enum-as-strategy),
  `TransformCompiler` function registry, `RouteModule` (shape only — see below).
- *SPI Registry* — the 8 ServiceLoader seams (§4).
- *Bridge/anti-corruption* — `ops.EventObjectBridge` (events → managed objects).
- *Ring buffer* — `InMemoryEventStore` (bounded, drop-oldest).

**Misapplied / missing (improvement-plan items):**
- `RouteModule` is a textbook Strategy **not wired as a Registry**: `ControlApi.registerRoutes`
  (`ControlApi.java:335-355`) hardcodes `List.of(new …)` of 24 modules; the interface and impls
  are package-private, so ServiceLoader needs them made public — otherwise the conversion is
  near-zero-cost (all impls are already no-arg + `ApiContext`-resolved).
- **Missing middleware/filter chain**: `ControlApi.dispatch` (`ControlApi.java:359-460`) inlines
  correlation-id, v1 versioning, CORS, idempotency, space routing, auth, audit, and legacy-usage
  metrics in one ~100-line method.
- **Missing template method**: assist-agent vs intelligence-agent register/lookup/close blocks in
  `SourceService` (559-596, 603-610, 1125-1132) are copy-pasted; a generic `OptionalAgentSlot<T>`
  collapses them.
- **God object**: `SourceService` — ~10 responsibility clusters, 25+ fields (decomposition plan in
  the improvement plan §2.2).
- Linear O(n) regex route dispatch — fine at ~200 routes, not indexed; noted, not urgent.

---

*Maintainers: update this page when a package's layer, an SPI, or a cycle changes. The MoSCoW
implementation plan for fixing the flagged gaps lives in*
[`superpower/modularization-optimization-plan.md`](../../superpower/modularization-optimization-plan.md).
