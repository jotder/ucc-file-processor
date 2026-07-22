# Inspecto — Platform Requirements & MoSCoW Analysis

> **Status: CURRENT requirements-of-record** · Compiled 2026-07-07 · **Re-reconciled 2026-07-08 after the
> ship-sweep session** (ACQ/PIP/BI/INV/MET closures; every pending item now carries a scope line) ·
> Reconciled against the shipped code (`master`, W1–W7 API contracts + R1–R6 metadata rework included).
>
> This document supersedes the archived requirements snapshot
> (`archived-documents/consolidated-2026-06-13/02-Product-Requirements.md`) and **reconciles** the planning-time
> MoSCoW in [`archived-documents/plans-archive/feature-matrix-editions.md`](archived-documents/plans-archive/feature-matrix-editions.md) (2026-07-02)
> with what has actually shipped since — several of that matrix's MUST items (security module, Studio
> persistence, live query path, pipeline-authoring wiring) are now delivered and appear here as baseline.
> Vocabulary is binding per [`GLOSSARY.md`](GLOSSARY.md).

---

## 1. Product definition & scope

**Inspecto** is a lean, configuration-driven **data acquisition + data management + BI + investigation
platform**: one ~90 MB self-contained artifact (single JVM, embedded DuckDB, zero external runtime
services) that replaces a NiFi-style collection/pipeline layer, a Tableau/Superset-style BI layer, a
Jira-style ops incident layer, and the surrounding glue scripts. Its wedge is **leanness *with*
operability** — for regulated, air-gapped, and resource-constrained buyers.

Three convictions anchor every requirement:

1. **One declarative config onboards a feed** — no code deploy, no DAG project, no cluster.
2. **Lean & self-contained** — runs on a laptop, an air-gapped server, or a container.
3. **Operable by design** — every Run is crash-isolated and idempotent; everything is captured as
   Signals, Metrics, audit, and managed objects.

The north star ([`superpower/living-operational-system.md`](superpower/living-operational-system.md))
frames the platform as **seven cooperating networks over one Component metamodel** (Data · Signal ·
Decision · Execution · Metadata · Presentation · Security), so it can evolve from deterministic rules to
AI-driven autonomy without redesign.

### 1.1 The three application sections

| Section | What it is | Where |
|---|---|---|
| **Angular UI** | The one operator console (all Lenses), mock-first + live | `inspecto-ui/` |
| **Java backend** | Engine + control plane + connectors + agent + security modules | `inspecto/`, `inspecto-connectors/`, `inspecto-agent/`, `inspecto-agent-hosted/`, `inspecto-security/` |
| **Agentic framework** | **eoiagent** — reusable, embeddable agent platform (separate repo); Inspecto's model transport since 2026-07-07; the kernel reasoning layer is vendored in `inspecto-agent` | `C:/sandbox/agent-brainstorm` (`com.eoiagent:*`) |

### 1.2 Personas (Lenses) and editions

- **Lens** = self-selected persona view of the one console (never a permission): **Business** (consume,
  investigate, raise Requirements) · **Builder** (author in Workbench + Studio) · **Ops** (operate runs,
  Signals, Incidents). **Role** = assigned, server-enforced authorization (security module); Roles project
  onto Lenses through **Capabilities** — panes gate on a Capability, never on Lens identity.
- **Editions are build flavors, never branches**: **Personal** (auth-free, local, free tier) ·
  **Standard** (adds `inspecto-security`: HTTPS, OIDC via external IAM, RBAC/ABAC, attributed audit — the
  free→paid line) · **Enterprise** (future: shared state, distributed scheduling, per-tenant ABAC;
  demand-gated).

---

## 2. Conventions used below

- **MoSCoW** — **Must** (product is not sellable/coherent without it) · **Should** (high value, not
  gating) · **Could** (valuable, opportunistic) · **Won't (now)** (explicitly out of this horizon).
  Ratings reflect **current product strategy as of 2026-07-07**, not the 2026-07-02 planning matrix.
- **Status** — `SHIPPED` (real backend, on `master`) · `PARTIAL` (shipped with named caveats) ·
  `MOCK-FIRST` (UI complete against the mock backend; backend pending) · `IN-FLIGHT` (uncommitted work in
  progress) · `DESIGN` (design-of-record exists, no code) · `PLANNED` (agreed, not started).
- **Edition** — `P` Personal · `S` Standard · `E` Enterprise · `All` edition-neutral.
- IDs are stable handles for traceability (`ACQ-3`, `NFR-2`, …); they do not imply sequence.

---

## 3. Functional requirements

### 3.1 Acquisition & connectivity (ACQ) — backend + UI Workbench

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| ACQ-1 | **Connections**: named endpoint+credential definitions (SFTP/FTP/FTPS, database), reused by many Sources | Must | SHIPPED | All |
| ACQ-2 | **Sources**: configured collection tasks (paths/queries, cadence, filename patterns, dedup policy) bound to one Connection | Must | SHIPPED | All |
| ACQ-3 | Acquisition framework: ledgers, dedup, watermarks, gap detection, retry (Phases A–F) | Must | SHIPPED | All |
| ACQ-4 | Object-storage (S3/GCS/Azure/MinIO) + network-share (NFS/SMB) connectors on the connector SPI | **Must** | SHIPPED (2026-07-08: `connector: s3` — SDK-free SigV4, covers S3/MinIO/GCS-interop; `connector: azure` — SDK-free SharedKey signing over JDK HttpClient, List Blobs pagination + Range resume + copy-status-guarded MOVE, etags feed ACQ-7, Azurite-compatible for LAN testing; NFS/SMB = documented OS-mounted-share pattern, UNC stays jail-rejected by design. GCS *native* API remains demand-gated — interop mode covers it today) | All |
| ACQ-5 | Streaming source consumer (e.g. Kafka topic as a Source) | Should | SHIPPED (2026-07-08: `connector: kafka` — a topic drained per scan cycle into virtual slice files on the existing SourceConnector SPI, no core-engine change; `assign()`+`seek()`, no consumer group — the consumed frontier rides the ledger watermark and is persisted only post-commit (at-least-once, DB-export machinery); envelope-NDJSON or raw-value payloads, retention clamp + `max_records` cap, optional SASL PLAIN; kafka-clients 3.9.2 confined to inspecto-connectors, tested offline via in-jar `MockConsumer`, no broker) | All |
| ACQ-6 | Push/event-driven file discovery (replace poll where the remote can notify) | Could | SHIPPED (2026-07-08: `POST /sources/{id}/notify` — external systems trigger an immediate scan, 202+runId on v1, `canOperateRuns`-gated, audited as `source.notified`; plus `source.discovery: watch` — WatchService push for local/mounted inboxes, debounced, poll loop stays on as backstop) | All |
| ACQ-7 | etag/version-aware dedup dimensions | Should | SHIPPED (2026-07-08: `source.duplicate.mode: etag` — pre-fetch skip on the connector's listing etag/object version; ledger columns `etag`/`object_version` with in-place migration; degrades to size+mtime when the connector supplies neither) | All |

### 3.2 Ingestion & parsing (ING) — backend

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| ING-1 | Stage-1 M..N multiplexer: parse → transform → partition → commit, batch-atomic | Must | SHIPPED | All |
| ING-2 | Format frontends: delimited grammar, fixed-width (text+binary), plugin `StreamingFileIngester` SPI (binary/multi-segment) | Must | SHIPPED | All |
| ING-3 | Compressed input streaming (gzip/bz2/zip) | Must | SHIPPED | All |
| ING-4 | Schema casting + reject routing (quarantine semantics: unreadable / mismatch / sink-flush fail) | Must | SHIPPED | All |
| ING-5 | Unified `parsing:` config block + **JSON/NDJSON** + **text/regex** frontends | **Must** | SHIPPED (2026-07-07; `parsing:` aliases `csv_settings`/`processing.ingester`; LDIF block-records stay PROPOSED) | All |
| ING-6 | **Expectation** engine: data-quality rules validating records against a Schema (non-null, range, regex, referential) | **Must** | SHIPPED (2026-07-07: `com.gamma.expectation` — authored `expectation` components, request-driven evaluation counts violations in the target's at-rest Parquet via a server-built COUNT in a DuckDB sandbox; a FAILED check opens a deduped `expectation:<name>` Incident + emits `EXPECTATION_FAILED` → notifications; `/expectations*` CRUD+evaluate) | All |

### 3.3 Pipelines & orchestration (PIP) — backend + UI Workbench

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| PIP-1 | Authored **Pipeline** DAGs (Steps: Parser/Transform/Enrichment/Sink, embedded Job, sub-Pipeline) with author-time validation, visual editor | Must | SHIPPED (live e2e verified 2026-07-07 — `examples/06-serve/pipeline-job`, manual + `on_pipeline` triggers) | All |
| PIP-2 | Medallion ELT: raw → clean partitioned Tables → Derived Tables (bronze→silver→gold) | Must | SHIPPED | All |
| PIP-3 | Incremental event-driven processing: on-pipeline commit Triggers, watermarks, cron + catch-up | Must | SHIPPED | All |
| PIP-4 | **Scheduler** + **Jobs** (atomic Executables; Run ⊇ Batch ⊇ File status hierarchy) | Must | SHIPPED | All |
| PIP-5 | Async Run triggers: `202 + runId` + poll, `Idempotency-Key` replay — jobs **and** pipelines | Must | SHIPPED (W5/W5b) | All |
| PIP-6 | Job templates (reusable parameterized Job definitions) | Could | SHIPPED (2026-07-08: `*_job_template.toon` — declared params + defaults, `${param}` substitution; jobs reference `template:` + `params:`, resolved at load so the scheduler sees only plain JobConfigs; instance keys override the template block) | All |
| PIP-7 | Maintenance job library (retention, compaction, housekeeping) | Should | SHIPPED (2026-07-08: MAINTENANCE tasks now `cleanup` + `ledger_prune` + `db_maintenance` + `compact` — compaction is quiet-window + crash-journal safe, readers are glob-based so it is query-transparent; ⚠ reprocess of a compacted-away batch is unsupported, keep `min_age_days` beyond the reprocess horizon. Curated library at `examples/06-serve/maintenance-library`) | All |

### 3.4 Data plane: Datasets & Queries (DAT) — backend + Studio

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| DAT-1 | **Dataset** umbrella (Table / Derived Table / View) over partitioned Parquet, described by Schemas, browsable in the Catalog | Must | SHIPPED | All |
| DAT-2 | **Query** as a first-class Component (`sql \| structured`) + Query Library + `$`-**Parameters** + **Result Set** descriptor | Must | SHIPPED (R3+W4) | All |
| DAT-3 | Live query execution `POST /queries/{id}/run` on DuckDB with server-side parameter resolution | Must | SHIPPED (2026-07-08: the missing server-side *structured* evaluator is BI-7's `POST /bi/query` — spec-based measures/dimensions/filters compiled and executed server-side; `query` components stay `type: sql` and the 422 on `type: structured` remains the honest boundary (no client authors them). Minor caveat: pagination stays offset-based) | All |
| DAT-4 | **Matrix** materialization: persisted summary Derived Tables as managed assets | Should | SHIPPED (2026-07-08: `task: materialize` on the maintenance runner — BI-7 spec-compiled SELECT (or raw snapshot) over the source Dataset's trusted relation, `COPY TO` Parquet with PIP-7's hide-old/reveal-new atomic swap (crash leaves only glob-invisible leftovers, self-cleaning), and the target registered/refreshed as a normal `dataset` component — so a Matrix is queryable everywhere a Dataset is, zero net-new read paths) | All |
| DAT-5 | Row-level calculated columns on Datasets | Should | SHIPPED (2026-07-08: `dataset.calculated: [{name, expr}]` — `DatasetRelation` wraps the base relation `SELECT *, (expr) AS name`; every expr passes the new **`ExpressionGuard`** (fragment-level safety: closed token alphabet + keyword deny-set killing subquery smuggling + function-call whitelist killing `read_parquet`/UDFs + comment-sequence rejection; design: `archived-documents/plans-archive/calculated-columns-design.md`); fail-closed 422, inherited by every consumer incl. DAT-4 materialization. Deliberate v1 cuts: no window/aggregate functions, no quoted identifiers) | All |
| DAT-6 | Optional Postgres state store (swap embedded state) | Should | SHIPPED (2026-07-07: all 6 JDBC state stores — jobs/provenance/objects/links/notes/status — verified against real Postgres via embedded-PG test; DuckDB-only `quantile_cont` made dialect-aware; `postgres` backend alias; PG driver on classpath = `inspecto-connectors`) | S/E |

### 3.5 BI Studio & presentation (BI) — UI + backend

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| BI-1 | Studio authoring: Datasets, **Widgets** (Visualization Type + Config + Result-Set binding), **Dashboards**; real persistence via the widened component store | Must | SHIPPED (W3 widened `WRITABLE_TYPES`) | All |
| BI-2 | **VizPlugin** registry of Visualization Types (charts, tables, scatter, funnel, …) | Must | SHIPPED | All |
| BI-3 | KPI & Reports gallery; dashboard quick-filter bar, drill-through, time grain, PNG export; **Measures** in Explore | Should | SHIPPED | All |
| BI-4 | Scheduled report/export delivery | Should | SHIPPED (2026-07-08: REPORT job `out_dir`/`format` renders a timestamped JSON/CSV artifact — new `scope: dataset` exports a headless BI query; `REPORT_READY` event → webhook/SMTP notification. Caveat: SMTP delivers the artifact *path*, not an attachment — the SMTP channel is text-only) | S/E |
| BI-5 | Alerting on **Measures** (BI thresholds raising Alerts) | Could | SHIPPED (2026-07-08: `*_alert.toon` measure rules — `dataset:` + `measure: agg(field)` evaluated via the headless BI evaluator on every sweep, firing the existing ALERT_FIRED→notification path. v1 = whole-dataset measures, no per-rule filters) | All |
| BI-6 | Public/embedded Dashboard sharing | Could | SHIPPED (2026-07-08: backend — fail-closed HMAC share tokens (inert without `-Dbi.share.secret`, expiring, tamper=404), anonymous resolve + a public BI query fenced to the dashboard's own datasets. **UI** — `/share/:token` guest embed viewer (no shell, no guard): tiles render read-only through the normal VizPlugin→viz-render path, per-tile data via the fenced query with widget controls mapped back to validated agg/field pairs (measure-id parity with the backend); view-bound/expression-measure widgets degrade to an explicit "not embeddable" tile, per-tile errors never take down the page; `/public` added to the space-interceptor's server-global set; mock answers an honest 501 (no HMAC secret offline). Gauntlet green + live-preview verified. **In-app mint dialog added 2026-07-09** — `ShareDashboardDialog` + a Share button on the dashboard editor call `POST /dashboards/{id}/share` and show the `/share/{token}` link with copy + expiry) | S/E |
| BI-7 | Semantic / headless BI API | Could | SHIPPED (2026-07-08: `POST /bi/query` — spec-based measures/dimensions/filters compiled server-side (the declared backend twin of the UI QuerySpec seam), SqlGuard-checked, sandbox-executed; `GET /bi/datasets`. Open follow-up: swapping the UI viz layer onto it) | S/E |
| BI-8 | Widget/Dashboard template marketplace | Could | SHIPPED (2026-07-08: backend — `GET /bi/templates` + parameterized all-or-nothing `apply` writing real Studio-editable components (templates reshaped to UI-native `{vizType, controls}` widgets / `{name, tiles}` dashboards, so applied boards render immediately). **UI** — `/studio/templates` gallery pane over `GET /bi/templates` + an apply dialog (dataset picker + optional id prefix → routes to the created dashboard); nav entry; mock lists offline, apply 501 (writes server-side). Gauntlet green. Cross-space sharing stays `/bundle/*`; an external *marketplace/exchange* remains out of scope by design) | All |

### 3.6 Investigation studios (INV) — UI + backend

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| INV-1 | **Link Analysis Studio**: Entity Projection over a Dataset, shared G6 host, 11 layouts, Louvain communities, pattern matching, saved **Link-Analysis Views** | Should | SHIPPED (2026-07-08: real backend Entity Projection — `POST /inv/projection`, DuckDB-side fold over the sandbox executor, heaviest-first with truncation; the studio is backend-first with the offline sample fold as fallback; saved views persist server-side — `link-analysis-view`/`geo-map-view` joined `ComponentStore.WRITABLE_TYPES`. Open per design §7: `attrCols` mapping surface + the schema-relationship model) | S (edition-added) |
| INV-2 | **Geo Map Analysis Studio**: offline MapLibre/PMTiles basemap, GeoSource/GeoQuery, heatmap, od-routes, time slider + playback, intelligence toolbox (co-location/frequent/stay-points), measure/radius/polygon/notes tools, layer manager + GeoJSON overlays, saved **Geo Views** | Should | SHIPPED (UI, metadata-first; DuckDB-spatial backend = Phase 4) | All |
| INV-3 | **Cases** grouping Incidents; RCA templates; correlation ids end-to-end | Must | SHIPPED | All |
| INV-4 | Cross-studio bridges (e.g. geo co-location → graph dialog) | Could | SHIPPED | All |

### 3.7 Observability & operations (OPS) — backend + UI Ops

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| OPS-1 | One **Signal** ledger; **Events**, Alerts, Notifications as *views* over it; live tail, saved views, CSV export | Must | SHIPPED (R4) | All |
| OPS-2 | **Metrics** (Prometheus-compatible), incl. `inspecto_legacy_api_requests_total` sunset signal | Must | SHIPPED | All |
| OPS-3 | Three-layer audit: file/batch audit, provenance rows, immutable who-did-what **Audit Log** | Must | SHIPPED (actor attribution hardening on Standard — see SEC-7) | All |
| OPS-4 | Durable Run reporting (success rate, p50/p95) | Should | SHIPPED (off by default) | All |
| OPS-5 | Per-edge **Provenance** + conservation invariant → Alerts + Sankey overlay | Should | PARTIAL (built/tested; off by default; verified vs synthetic data only. Executable verification protocol signed 2026-07-08: `docs/ops/provenance-conservation-verification.md` — enable on a real feed, soak through natural variation, cross-check invariants against ground truth, log the outcome. Cannot close offline; needs the first live deployment to run it) | All |
| OPS-6 | Record-level lineage & replay (per-record ancestry) | **Won't (now)** | — (per-batch ancestry is the accepted grain) | — |

### 3.8 Alerts & Incidents (INC) — backend + UI Ops

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| INC-1 | **Alert Rules** watch Metrics; fired **Alerts** with severity | Must | SHIPPED | All |
| INC-2 | **Alert → Incident → Case** lifecycle, object-link graph, SLA, comments | Must | SHIPPED | All |
| INC-3 | **Notification** delivery channels (email/webhook) + per-user preferences | **Must** | SHIPPED (2026-07-07: webhook channel in core, SMTP in connectors, `notify.*` sysprops, `ALERT_FIRED` rule; preferences remain single-global until the auth module adds users) | All |
| INC-4 | Incident workflow depth: queues, escalation, watchers | Should | SHIPPED (2026-07-08: **queues** first-class — `*_queue.toon` / `POST /queues`, members + `round_robin`\|`least_loaded`\|`manual` routing via `QueueRouter`; **assignment** `POST /objects/{id}/assign` (person or queue-routed) advances the workflow + emits `OBJECT_ASSIGNED` (the assignment history); **watchers** `POST /objects/{id}/watch`\|`unwatch` + `GET .../watchers`; **escalation** `*_escalation.toon` policy the SLA sweep applies on breach — severity bump + queue re-route + `OBJECT_ESCALATED` notify. Queue store in-memory (Db parity a noted follow-on, as links/notes began); per-user notification delivery still rides the global channel tags) | All |
| INC-5 | **Diagnosis**: AI-assisted RCA of a failing Run/Source producing an Incident | Should | SHIPPED | All |

### 3.9 Spaces & tenancy (SPC)

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| SPC-1 | Isolated **Spaces** (config/data/audit/duckdb per Space), CRUD without restart, one-time migrator | Must | SHIPPED | All |
| SPC-2 | Whole-Space zip export/import with dry-run preview | Must | SHIPPED | All |
| SPC-3 | **Space Templates** (vertical blueprints: Telecom RA, Fraud, Financial Audit, Link Analysis) | Should | SHIPPED (UI seed packs) | All |
| SPC-4 | **Metadata Bundle v2**: selective config-only transfer with lineage refs, provenance/contentHash, `requires`, drift fit-check | Should | SHIPPED (2026-07-07: `BundleRoutes` export/preview/import over the `ComponentStore` kinds — real content+contentHash, drift fit-check, idempotent import; connection/pipeline/job/view kinds deferred to their own stores) | All |
| SPC-5 | Per-tenant ABAC | Could | PLANNED | E |

### 3.10 Component metamodel & Catalog (MET)

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| MET-1 | Everything authored is a **Component** `{kind, name, config, parts?, wiring?}`; kind registry declares config schemas | Must | SHIPPED | All |
| MET-2 | Derived **Registry** reuse graph + Catalog + lineage graph (canonical edge/node kinds, `CONSUMES` etc.) | Must | SHIPPED | All |
| MET-3 | Single ref derivation (`deriveRefs`) feeding reuse graph, bundles, delete-protection | Must | SHIPPED (R1) | All |
| MET-4 | **Stream** read-model in the Catalog (browsable data origins; IA reorg Phase B) | Should | SHIPPED (2026-07-08: `GET /catalog/streams` — every Source as a data-origin catalog node (connector/connection/pipeline/discovery attrs), shaped to the UI `MetadataNode` contract the mock already served; UI needed no change) | All |
| MET-5 | Draft/published Component version history (W3b) | Could | SHIPPED (2026-07-09: `ComponentStore.write` archives the prior copy under `<typeDir>/.history/<id>.v<N>.toon` — a sub-dir, not a sibling `.toon`, so the registry scan never mis-reads it as a duplicate — keep-N (`-Dcomponents.history.keep`, default 10); `GET /components/{type}/{id}/versions` + `POST …/versions/{v}/restore` (restore is itself a versioned write); reusable `ComponentHistoryDialog` + a History button on the dashboard editor; mock mirrors the archive/list/restore. Reactor 1139/0/0/3 + UI specs/live-walk green) | All |

### 3.11 API & integration (API)

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| API-1 | Versioned **`/api/v1`** business contract: response envelope, error-code catalog, Correlation-ID, gzip; legacy routes byte-for-byte until sunset | Must | SHIPPED (W1) | All |
| API-2 | OpenAPI 3.1 contract (`docs/api/openapi-v1.json`) enforced by `ApiContractTest` | Must | SHIPPED (W2) | All |
| API-3 | Optimistic concurrency: `ContentHash` + ETag / If-None-Match / If-Match on Components | Must | SHIPPED (W3) | All |
| API-4 | `GET /bootstrap` metadata-first boot (features, `authMode`, permissions) | Must | SHIPPED (W3/W6) | All |
| API-5 | Legacy (unversioned) route sunset — **soak-gated** on the usage metric | Should | SHIPPED (2026-07-08: mechanism — `Deprecation`/`Link`/`Sunset` headers + `-Dapi.legacy.routes=off` → 410 (infra exempt, metric keeps counting) — plus the **soak criterion signed** the same day: **30 consecutive days at zero `inspecto_legacy_api_requests_total` on a deployment ⇒ flip that deployment `off`**. Executable runbook: `docs/ops/legacy-api-sunset-runbook.md` (PromQL query, flip procedure, verification curls). Remaining is pure per-deployment ops execution — no engineering, no open decision) | All |
| API-6 | Gateway/IAM drop-in: WSO2 gateway + Keycloak blueprints for Standard | Must (S) | SHIPPED (design + security module seams) | S |
| API-7 | Java embedding API stability policy (SemVer, `@PublicApi`) | Must | SHIPPED | All |

### 3.12 Security (SEC)

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| SEC-1 | **Auth-free common core** — no auth/RBAC/user management code in core; Personal boots login-free | Must | SHIPPED | P |
| SEC-2 | `Authenticator` / `Subject` / `TokenRelay` SPIs, AuthN gate, per-route capability checks, `UNAUTHENTICATED`/`PERMISSION_DENIED` | Must | SHIPPED (W6) | All (no-op on P) |
| SEC-3 | `inspecto-security` module: OIDC resource server (Nimbus/JWKS), `RoleMapper`, Keycloak token relay — reactor-gated behind `edition-standard` | Must (S) | SHIPPED | S/E |
| SEC-4 | HTTPS via pure-JDK `HttpsServer` + keystore | Must (S) | SHIPPED | S/E |
| SEC-5 | BFF session: refresh token never reaches the browser (httpOnly cookie, SameSite=Strict + Origin CSRF) | Must (S) | SHIPPED (W6d) | S/E |
| SEC-6 | UI OIDC login driven by `bootstrap.features.authMode`; offline/Personal = no-op | Must (S) | SHIPPED (W6d/W7) | S/E |
| SEC-7 | RBAC/ABAC hardening: reject X-Actor on Standard, **per-resource** `permissions[]`, `canTriageRequirements` backend route, data-scoped grants | **Must (S)** | SHIPPED (2026-07-07/08: **X-Actor rejected outright when an `Authenticator` is active** (Standard) — actor authoritative from the Subject; Personal unchanged. **`canTriageRequirements` route** shipped — `RequirementRoutes` `/decision`+`/deliver` gated server-side on the capability while submission stays open (with UI-6). **Per-resource `permissions[]`** shipped — the v1 envelope emits `grants ∩ resource state` when a route declares the applicable set (`ApiContext.resourcePermissions`; components/expectations/requirements opted in; design: `archived-documents/plans-archive/resource-permissions-design.md`). **Data-scoped grants** shipped 2026-07-08 (SEC-7d, closes `archived-documents/plans-archive/rbac-groundwork.md` §4 Q2, model signed by product in-session: **attribute scopes**) — `Subject.dataScopes` (null = unscoped, every plain role unchanged); an object's `caseType` attribute is the scoping dimension; `ObjectRoutes` row-filters lists, 404s any direct route to an out-of-scope object (read AND mutate, existence-hiding), and prunes the correlation graph; `RoleMapper` resolves scopes from a `data_scopes` claim ∪ `case:<scope>` role names. Personal edition byte-identical (no Subject ever attaches)) | S/E |
| SEC-8 | Secrets: env/file/keystore; Vault option future | Should | PARTIAL (2026-07-07: `SecretResolver` now does `${ENV}`/`${SYS}`/`${FILE}`/`${KEYSTORE:alias}` (JCEKS, pure-JDK); Vault scope deferred — client not in the lean core) | S/E |
| SEC-9 | Write-root gate (`-Dassist.write.root` → 503 fail-closed) — separate from auth, always on | Must | SHIPPED | All |

### 3.13 Assistant & embedded intelligence (AGT)

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| AGT-1 | **Assistant** skills (7, read-only/draft-only, abstain-only escalation): diagnose, explain, KPI→SQL, NL→schedule, report narrative/SQL, suggest config | Must | SHIPPED | All |
| AGT-2 | Pluggable model transport: **eoiagent** gateway bridge + native Ollama provider; hosted providers isolated in `inspecto-agent-hosted` | Must | SHIPPED | All |
| AGT-3 | Air-gap guarantee: hosted SDKs physically absent from air-gapped builds (`EgressGuardTest` invariant) | Must | SHIPPED | All |
| AGT-4 | Model Settings pane + per-tier connectivity probes | Should | SHIPPED | All |
| AGT-5 | **Embedded intelligence** (`inspecto-intelligence` module): ContextBroker grounding, tool belt L0–L3, autonomy ladder (Explain → Draft → Act-with-approval → bounded autonomy) | Should | P0 SHIPPED 2026-07-07 (sign-off given); P1–P5 open | All (L3 = S+, opt-in) |
| AGT-6 | AI behind every screen; multi-step agent graphs | Could | PLANNED | All |

### 3.14 Agentic framework as a product (EOI) — eoiagent, separate repo

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| EOI-1 | Embeddable, framework-free Java agent library (no Spring; classpath, not JPMS) | Must | SHIPPED | — |
| EOI-2 | Pluggable models: `LlmGateway` seam, OpenAI-compatible + local (Ollama) portability | Must | SHIPPED | — |
| EOI-3 | Governance: approval gate + dry-run for every mutating action | Must | SHIPPED | — |
| EOI-4 | Audit trail + observability of agent decisions | Must | SHIPPED | — |
| EOI-5 | Core vs **application-pack** split (host apps ship packs; core stays generic) | Must | SHIPPED | — |
| EOI-6 | Eval harness for skill/orchestration regression | Should | SHIPPED | — |
| EOI-7 | Cut a **0.1.0 release** + publish artifacts (today: `0.1.0-SNAPSHOT`, local-`.m2`/source-build only; Inspecto CI builds it from source) | **Must** | PARTIAL (2026-07-08: **(a) cut + pinned** — `v0.1.0` tagged on eoiagent `main`, trunk bumped to `0.2.0-SNAPSHOT`, released jars in local `.m2`; both Inspecto agent poms pin `eoiagent.version 0.1.0` (no SNAPSHOT anywhere), reactor green; reproduce with `git checkout v0.1.0 && mvn -o clean install`. Remaining: **(b) publish** — the registry decision (Nexus? GitHub Packages?), infra/product call) | — |

### 3.15 Operator console UX (UI)

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| UI-1 | **Lens** switcher (Business/Builder/Ops) + Capability-gated panes | Must | SHIPPED | All |
| UI-2 | Shared design system (status-badge, empty-state, skeleton, grid, connectivity-banner, data-table family) + no-hardcoded-colors CI gate + `/design` gallery | Must | SHIPPED | All |
| UI-3 | Accessibility: WCAG 2.2 AA, axe-core gate in CI | Must | SHIPPED | All |
| UI-4 | Offline mock-first operation (one `MockStore`, seed packs, v1-envelope parity) | Must | SHIPPED | All |
| UI-5 | Responsive sweep (32 routes × 2 breakpoints) | Must | SHIPPED | All |
| UI-6 | **Requirement** intake: Business submits (KPI/Report/Reconciliation/Rule), Builder triages, delivery recorded | Should | SHIPPED (2026-07-07: `RequirementRoutes` — `/requirements` submit/list + `/requirements/{id}/decision`+`/deliver` over the `requirement` component store; UI moved off generic component CRUD to these routes; offline mock parity) | All |
| UI-7 | **Reconciliation** + **Breaks** (auto-close on re-match; manual resolutions preserved) | Must | SHIPPED | All |
| UI-8 | Settings drawer + consolidated admin settings pane | Should | IN-FLIGHT (uncommitted, another session) | All |

### 3.16 Packaging & editions (PKG)

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| PKG-1 | One fat JAR + jlink runtime; per-edition bundles via `package.ps1 -Edition` | Must | SHIPPED | All |
| PKG-2 | Lean SBOM: framework-free core, network deps isolated in `inspecto-connectors` | Must | SHIPPED | All |
| PKG-3 | Runnable, self-contained example suite (`inspecto/examples/`) | Should | SHIPPED | All |
| PKG-4 | Verify the Standard bundle's jlink module set against Nimbus (until then: run Standard with `-NoRuntime`) | **Must (S)** | SHIPPED (verified 2026-07-07: jdeps + Nimbus probe + boot on the exact 12-module jlink image; `package.ps1` note updated) | S |

---

## 4. Non-functional requirements

| ID | Requirement | Target / evidence | Status |
|---|---|---|---|
| NFR-1 | **Throughput** | DuckDB `Appender` ingest ≈75× JDBC batch (~510k rows/s on the 1M-row bench); auto-derived `duckdb_threads` | SHIPPED |
| NFR-2 | **Footprint** | ~90 MB artifact; zero external runtime services; laptop-to-container | SHIPPED |
| NFR-3 | **Resilience** | Crash-isolated, idempotent Runs; batch-atomic commit; quarantine semantics | SHIPPED |
| NFR-4 | **Air-gap operation** | Full function without egress; hosted-AI SDKs physically absent; offline basemap/geocoder | SHIPPED |
| NFR-5 | **Accessibility** | WCAG 2.2 AA; axe-core CI gate | SHIPPED |
| NFR-6 | **API compatibility** | SemVer; versioned `/api/v1`; legacy routes byte-for-byte until soak-gated sunset; `@PublicApi` embedding policy | SHIPPED |
| NFR-7 | **Compliance posture (Standard)** | SOC2 / ISO27001 / FedRAMP / HIPAA / PCI scope; small SBOM as a deliberate compliance asset | PARTIAL (module shipped; certifications not started) |
| NFR-8 | **Scale ceiling** | Single-node by design; Enterprise distributed tier is the opt-in escape hatch | ACCEPTED CONSTRAINT |
| NFR-9 | **Quality gates** | GAUNTLET (full reactor tests + UI lint/test/build), token lint, a11y gate, live smoke | SHIPPED (process) |
| NFR-10 | **Vocabulary discipline** | One concept → one word; banned synonyms never appear in UI/model/API/docs (GLOSSARY §0) | SHIPPED (enforced in review) |

---

## 5. MoSCoW summary — the reconciled backlog (as of 2026-07-08, post ship-sweep)

> **2026-07-08 ship-sweep:** one session closed ACQ-6/ACQ-7 · PIP-6/PIP-7 · the whole BI section
> (BI-4/5/6/7/8 shipped) · INV-1 · MET-4 · the DAT-3 caveat · ACQ-4's S3/MinIO/NFS/SMB
> half — each verified by the full reactor (1,271+ tests). **Everything still pending below carries a
> concrete scope line (size + blocker + owner), not a bare PLANNED.**

**Delivered baseline (former MUSTs, now shipped):** acquisition framework · Stage-1 ingest · medallion
ELT · authored Pipelines · scheduler/jobs + async runs · Datasets/Queries + live DuckDB execution ·
Studio persistence · component metamodel + R1–R6 rework · multi-space · `/api/v1` contract ·
`inspecto-security` (OIDC/HTTPS/BFF) · UI on v1 with OIDC · Assistant on eoiagent · packaging/editions.

### MUST (remaining — the release-gating set, each scoped)

1. **ACQ-4** — *FULLY CLOSED 2026-07-22*. `connector: azure` (SDK-free SharedKey) closed the tier on
   2026-07-08; the last residual, **GCS-native** (`connector: gcs`: the GCS JSON API + service-account
   OAuth2, beyond the S3-interop mode), shipped 2026-07-22 — SDK-free (RS256 JWT→bearer on JDK crypto),
   stub-tested offline. The prior "defer, offline-blocked" note was based on a stale assumption that
   native GCS needed a Google SDK jar; it does not. See `okf/backend/acquisition/connectors.md`.
2. **SEC-7** — *CLOSED 2026-07-08* (SEC-7d data-scoped grants shipped: attribute-scope model signed by
   product in-session; `caseType` visibility enforced server-side across list/read/mutate/graph on the
   Objects surface; scopes resolved by the security module). Boundary noted: the event/audit streams
   stay capability-gated, not row-scoped — they are ops surfaces, not case data.
3. **EOI-7** eoiagent `0.1.0` — *(a) cut + pin* **DONE 2026-07-08** (v0.1.0 tagged, Inspecto pinned,
   R2 closed — no SNAPSHOT dependency remains). Remaining: *(b) publish artifacts* — the registry
   decision (internal Nexus? GitHub Packages?) — an infra/product call, not code. Owner: product/infra;
   until then CI reproduces the pin from the tag (`git checkout v0.1.0 && mvn -o clean install`).

*Closed 2026-07-07: ING-5 (unified parsing + json/text_regex frontends), **ING-6** (Expectation engine:
`com.gamma.expectation` + `/expectations*`, real DuckDB violation counting + deduped-Incident/notify
consequence chain), INC-3 (webhook + SMTP delivery channels), PKG-4 (jlink/Nimbus verified), PIP-1
caveat (live e2e via `examples/06-serve/pipeline-job`).*

### SHOULD (remaining — each scoped)

- **OPS-5** Provenance conservation on live data — *not code: the verification protocol is written
  and signed (`docs/ops/provenance-conservation-verification.md`); running it needs the first live
  deployment. Owner: ops, first live deployment.*

*Closed 2026-07-08: **ACQ-7** etag/version dedup · **ACQ-6** push discovery (notify + watch) ·
**PIP-7** maintenance library (ledger_prune/db_maintenance/compact) · **PIP-6** job templates ·
**BI-4** scheduled report/export delivery · **INV-1** Link Analysis backend (Entity Projection +
server-side saved views) · **MET-4** Streams read-model (`GET /catalog/streams`) · **DAT-3** caveat
(structured evaluation = `/bi/query`).*

*Closed 2026-07-07: **DAT-6** Postgres state store (all 6 JDBC stores verified vs real Postgres) ·
**SEC-8** secrets (file + JCEKS keystore scopes; Vault still deferred) · **UI-8** Settings drawer
(landed by the parallel session, commits `7e06463`/`12ead9c`) · **SPC-4** Metadata Bundle v2 backend
(`BundleRoutes` export/preview/import over the `ComponentStore` kinds; connection/pipeline/job/view
kinds deferred to their own stores) · **UI-6** Requirements triage backend (`RequirementRoutes` submit/
decision/deliver + UI on the dedicated routes; shipped alongside SEC-7(c)) · **AGT-5 P0** embedded
intelligence spine (new `inspecto-intelligence` module: `IntelligenceAgent` SPI, `/agent/sessions`
+ `/agent/sessions/{id}/ask` control-plane routes, `InspectoPack` on the eoiagent platform with a
3-tool read belt + navigation catalog + policy/prompt profiles; QA-only, OFFLINE-only, no RAG corpus
yet — see `docs/superpower/embedded-intelligence-plan.md` §8 for the documented P0 scope cuts and
P1–P5 remaining).*

### COULD (remaining — each scoped)

- ~~**BI-6 Share dialog**~~ (residual UX) — **DONE 2026-07-09**: `ShareDashboardDialog` + a Share button
  on the dashboard editor (edit mode) mint the link in-app via `POST /dashboards/{id}/share` and show the
  `/share/{token}` viewer URL with copy + expiry; 503 (no `-Dbi.share.secret`) → writes-disabled notice.
  BI-6 (embed viewer) + BI-8 (template gallery) shipped 2026-07-08. **BI-6 is now fully shipped.**
- **MET-5** Component version history — *scoped (see §3.10 row): ~1 shift, no migration.*
- **SPC-5** Per-tenant ABAC — *Enterprise-tier, demand-gated; design rides SEC-7's grants model —
  do not start before the SEC-7 product decision lands.*
- **AGT-5 P1–P5** — *phased per `embedded-intelligence-plan.md` §8; the EOI-7(a) gate lifted
  2026-07-08 (pinned v0.1.0, no moving SNAPSHOT) — P1 is now unblocked engineering.*
- **AGT-6** AI behind every screen / agent graphs — *sequenced after AGT-5 P2 (needs the tool belt +
  autonomy ladder in place); not scoped further on purpose.*
- **E1** Enterprise distributed tier · Stage-2 streaming — *demand-gated strategy items, unchanged.*

*Closed 2026-07-08: **BI-5** measure alerts · **BI-7** headless BI API · **PIP-6** job templates ·
**ACQ-6** push discovery; **BI-6**/**BI-8** backends shipped (fail-closed share tokens; curated
template gallery + apply).*

### WON'T (this horizon — explicit non-goals)

- **Spring/Quarkus migration** — the framework-free core is a deliberate compliance asset.
- **Distributed-by-default** — clustering stays opt-in Enterprise; the single-JVM ethos holds.
- **Per-record lineage/replay** (OPS-6) — per-batch ancestry is the accepted grain.
- **Auth in the common core** — security stays an edition module behind SPIs.
- **Lens as a permission** — Lenses are self-selected views; enforcement is Roles only.
- **Login/user management inside Inspecto** — identity is delegated to external IAM.

---

## 6. Sequencing & dependencies

1. **Security-first ordering holds**: SEC-7 + PKG-4 close out the Standard revenue gate that W6 opened.
2. **Connectors before streaming**: ACQ-4's object-storage half shipped 2026-07-08 (SDK-free s3);
   ACQ-5 (Kafka) shipped the same day on the same SPI once `kafka-clients` landed in the cache.
3. **Parsing unification (ING-5) before Expectation engine (ING-6)** — Expectations bind to the unified
   schema surface.
4. **Backend catch-up for mock-first UI**: ~~INV-1, SPC-4, MET-4, DAT-4~~ — the whole set closed by
   2026-07-08; the mock-first UI surfaces now all have real backends.
5. **AGT-5 (embedded intelligence)** — P0 shipped 2026-07-07 on product-owner sign-off; **EOI-7(a)**
   landed 2026-07-08 (pinned v0.1.0), so P1 no longer builds on a moving SNAPSHOT.
6. **API-5 legacy sunset — CLOSED 2026-07-08**: mechanism + signed soak criterion (30 days at zero)
   + executable runbook (`docs/ops/legacy-api-sunset-runbook.md`); every remaining step is per-deployment
   ops execution, not a decision or an engineering task.

---

## 7. Risks & open questions

| # | Risk / question | Mitigation / owner signal |
|---|---|---|
| R1 | ~~Authored-Pipeline go-live verified only against synthetic data~~ **RESOLVED 2026-07-07** — live seeded `type: pipeline` run verified (`examples/06-serve/pipeline-job`) | — |
| R2 | ~~eoiagent SNAPSHOT churn (moving dependency)~~ **RESOLVED 2026-07-08** — v0.1.0 cut + pinned (EOI-7a); CI builds the tag | — |
| R3 | ~~Standard jlink runtime unverified vs Nimbus~~ **RESOLVED 2026-07-07** — module set verified sufficient (PKG-4) | — |
| R4 | Per-resource permissions & X-Actor rejection incomplete on Standard | SEC-7 before first Standard customer |
| R5 | Provenance conservation checks unproven on live data | OPS-5 live verification alongside R1's seeded run |
| R6 | ~~Structured (non-SQL) Queries still client-compiled~~ **ACCEPTED AS DESIGN 2026-07-22** (product sign-off) — not a risk. The builder UI emits valid SQL that `QueryExecutor` runs; a server-side structured compiler would duplicate that for no functional gain. Server 422 on non-SQL bodies stays the explicit, deliberate contract. Revisit only if an external API consumer must submit structured bodies directly. | Closed — `okf/backend/control-plane/queries.md` |
| R7 | Prompt injection / data egress once intelligence deepens | AGT-5 design: context-as-data, privacy classes P0–P3, approval gates, kill switch |
| R8 | ~~Open product questions: case-type data-scoped grants, `canOnboardConnections` split, sunset timing~~ **RESOLVED 2026-07-22** (product sign-off): case-type grants = SEC-7d attribute-scope model (shipped, no further per-type role UI); `canOnboardConnections` = split out + implemented (Admin-only grant on the connection write routes); sunset timing = **per-deployment** (no global soak number, W8 mechanism stands). | Closed — `okf/backend/editions/auth-security.md` |
| R9 | Feature matrix (2026-07-02) drifting from reality | This document is the reconciled view; update **both** on the next planning pass |

---

## 8. Traceability

| Source | What it grounds |
|---|---|
| [`archived-documents/plans-archive/feature-matrix-editions.md`](archived-documents/plans-archive/feature-matrix-editions.md) | Original H/M/S/N ratings + edition tiers (2026-07-02 planning view) |
| [`archived-documents/plans-archive/api-contract-design.md`](archived-documents/plans-archive/api-contract-design.md) | 33 product-owner API guidelines; W1–W7 delivery worklog |
| [`superpower/living-operational-system.md`](superpower/living-operational-system.md) | Seven-network north star; R1–R6 rework (shipped) |
| [`archived-documents/plans-archive/backend-backlog.md`](archived-documents/plans-archive/backend-backlog.md) | Component-store seam history; Matrix/job-template sequencing |
| [`superpower/embedded-intelligence-plan.md`](superpower/embedded-intelligence-plan.md) | AGT-5 phases P0–P5, autonomy ladder |
| [`roadmap/ROADMAP.md`](roadmap/ROADMAP.md) · [`roadmap/STAKEHOLDER_OVERVIEW.md`](roadmap/STAKEHOLDER_OVERVIEW.md) | Horizons; value proposition; maturity |
| [`EDITIONS.md`](EDITIONS.md) | Edition capability tiers |
| [`GLOSSARY.md`](GLOSSARY.md) | Binding vocabulary (§0 rules; §13 rename status) |
| [`FEATURE_INVENTORY.md`](FEATURE_INVENTORY.md) | Per-feature TOON shapes + runnability constraints |
