# Inspecto — Platform Overview & Strategy

*A configuration-driven data ingestion, processing, and operational-intelligence platform*

**Audience:** business and technical stakeholders · **Status:** current as of 2026-06-19 · **Owner:** Inspecto engineering
**Companion documents:** [ROADMAP.md](ROADMAP.md) (forward plan) · [PRESENTATION.md](PRESENTATION.md) (slide content) · [../ADVANCED_GUIDE.md](../ADVANCED_GUIDE.md) (operations source of truth) · [../EDITIONS.md](../EDITIONS.md) (packaging model)

---

## How to read this document

This is a high-level overview written for a mixed audience. It opens with the business framing (Sections 1–3), moves through the architecture and a capability inventory (Sections 4–6), then covers the deployment/editions model, quality posture, and the forward roadmap (Sections 7–10). Each section is self-contained; executives can stop after Section 3 plus the roadmap (Section 10), while technical reviewers will find the substance in Sections 4–6.

**Contents**

1. Executive summary
2. The problem we solve
3. What Inspecto is
4. Architecture at a glance
5. The processing model (how data flows through)
6. Capability inventory
7. Editions & deployment model
8. Quality, security, and operability
9. Where we are today (maturity snapshot)
10. Roadmap
11. Risks & open decisions
12. Appendix — glossary, metrics & API surface

---

## 1. Executive summary

**Inspecto** is a small, high-throughput, **configuration-driven data platform**. It onboards arbitrary data feeds — delimited files, fixed-width records, proprietary binary formats, database extracts, and files arriving over SFTP/FTP — using a single declarative config file, transforms and partitions them deterministically, and makes the result queryable, observable, and operable. It ships as one self-contained executable with no external runtime services required.

The platform is built around three convictions that differentiate it from heavyweight data stacks:

- **One config file, not a pipeline project.** A new feed is onboarded by authoring (or generating) one declarative file — no code deploy, no DAG framework, no cluster.
- **Lean and self-contained.** The entire engine — including the embedded analytical database — is a single ~90 MB artifact. It runs on a laptop, an air-gapped server, or a container with equal ease, and has zero external runtime dependencies in its core.
- **Operable by design.** Every run is crash-isolated and idempotent; everything that happens is captured as metrics, structured events, three-layer audit, and managed operational objects (alerts, issues, cases) — surfaced through a REST API and an operator web console.

Over the last several release lines the platform has matured from a single-purpose file multiplexer into a layered platform: a **two-stage ETL engine**, a **data-acquisition framework** for remote/secure file collection, a **NiFi-style flow-graph** authoring and execution model, an **operational-intelligence** layer (events → alerts → managed cases), and an **embedded AI assist agent** that turns config authoring, scheduling, and querying into natural-language interactions.

The forward strategy has two thrusts: (1) **commercial readiness** through a clean three-edition packaging model (Personal / Standard / Enterprise) that adds enterprise security and scale without forking the codebase; and (2) **breadth of ingestion and self-service** through additional parsing frontends, object-storage connectors, and a visual flow authoring experience. Both are described in Section 10 and detailed in [ROADMAP.md](ROADMAP.md).

---

## 2. The problem we solve

Organizations that run on data feeds — telecom CDRs, financial transaction extracts, partner files, regulatory submissions, IoT and log exports — face the same recurring tax:

- **Every new feed is a project.** Onboarding a new file format typically means writing bespoke parsing code, standing up orchestration, and wiring monitoring. The marginal cost of the *next* feed never falls.
- **Heavyweight stacks for lightweight jobs.** A distributed processing cluster is overkill for the very common case of "collect these files, normalize them, partition them, make them queryable" — yet that is the tool many teams reach for, paying in operational complexity and cost.
- **Operations is an afterthought.** When a file is late, malformed, or silently dropped, teams discover it downstream. Lineage ("which input produced this output, and how many records survived each step?") is rarely available without custom instrumentation.
- **Collection is fragile and insecure.** Pulling files from partner SFTP/FTP servers, deduplicating re-sent files, detecting gaps in a numbered sequence, and doing it through a bastion with host-key pinning is repetitive plumbing that every team rebuilds.
- **Authoring is expert-only.** Config files, cron expressions, and SQL are barriers for the operators who actually own the feeds.

Inspecto addresses all five: feeds onboard via declarative config (or natural language), the engine is a single lean process, operability and lineage are first-class, the acquisition framework handles secure collection and dedup, and an embedded assist agent lowers the authoring barrier.

---

## 3. What Inspecto is

### 3.1 In one paragraph

The engine is an **M..N multiplexer**: it ingests **M** input files, applies light per-record transformations, and demultiplexes them into **N** output partitions (Hive-partitioned Parquet or CSV), decoupled from the input file count. On top of that **Stage-1 ingest** sits a **Stage-2 enrichment engine** (joins and aggregations over the partitioned output), a **control plane** (HTTP API, scheduler, metrics, audit), and several optional layers that ship in the same build: a **data-acquisition framework** for remote collection, a **flow-graph** model for authoring branching pipelines, an **operational-intelligence** layer, and an **embedded AI assist agent**. An optional Angular **operator web console** is served from the same process.

### 3.2 Design principles

| Principle | What it means in practice |
|---|---|
| **Configuration over code** | A feed is a declarative `*.toon` config. Custom/binary formats plug in via a small SPI, not a fork. |
| **Lean core, optional layers** | The fat JAR bundles only the engine. AI providers, remote connectors, and security ship as separately-assembled modules — absent modules simply aren't discovered at runtime. |
| **Crash-isolated & idempotent** | Each batch is an embarrassingly parallel unit with its own ephemeral database connection. Markers-last commit ordering plus an fsync'd commit log make an interrupted run safe to re-process. |
| **Observable & operable first** | Metrics, structured events, three-layer audit, and managed operational objects are built in — not bolted on. |
| **AI proposes; tested endpoints dispose** | Every state-changing AI suggestion is a validated, confirm-first draft. Generated SQL is validated in a locked-down sandbox. Air-gapped builds physically omit hosted-model SDKs. |
| **Editions are build flavors, never forks** | Personal/Standard/Enterprise are assembled from one codebase via modules and flags. There is exactly one mainline. |

### 3.3 Who uses it

- **Data/platform engineers** author feeds, define flows, and operate the platform.
- **Operations teams** monitor runs, triage late/failed/gapped files, and work managed cases through the console.
- **Analysts** query the partitioned output and Stage-2 rollups directly.
- **Compliance/security** rely on the audit trail, edition-level access control, and the lean dependency footprint.

### 3.4 Positioning — why this shape

Inspecto deliberately occupies a gap between two crowded ends of the market:

- **vs. a distributed processing cluster** (Spark/Flink/managed lakehouse): those are the right tool when a single node genuinely cannot hold the workload. For the very common "collect → normalize → partition → query" job, they impose cluster operations, tuning, and cost that the work does not justify. Inspecto does that job on one node with an embedded columnar engine — and keeps the distributed escape hatch open for when scale truly demands it.
- **vs. a heavyweight integration/ETL suite**: traditional suites are GUI-heavy, license-heavy, and code-light only on the happy path; proprietary formats still mean custom adapters. Inspecto is config-first with a clean plugin SPI, so the *next* feed is cheap.
- **vs. a build-it-yourself stack of scripts + cron + a warehouse**: teams reinvent collection, dedup, gap detection, audit, and lineage every time. Inspecto ships those as first-class, reusable capabilities.
- **vs. a SaaS data platform**: SaaS is a non-starter for air-gapped, sovereign, or regulated deployments. Inspecto runs entirely on-premises (or in a customer VPC) as one artifact, with hosted-AI SDKs physically removable.

The throughline: **leanness *with* operability**. Most lean tools are operationally bare; most operable platforms are heavy. Inspecto is both, which is the wedge for regulated and resource-constrained buyers.

---

## 4. Architecture at a glance

Inspecto is a **framework-free Java application** (no Spring/Quarkus) running on a modern JVM, with an **embedded analytical database** (DuckDB) doing the heavy data work. The deliberate absence of a web framework keeps the dependency tree — and therefore the security attestation surface — small.

### 4.1 Module structure

The product is a small Maven reactor of cleanly separated modules:

| Module | Role |
|---|---|
| **`inspecto/`** (engine core) | The Stage-1/Stage-2 engine, control plane, scheduler, metrics, audit, flow-graph runtime, and operational-intelligence layer. Ships as the self-contained fat JAR. |
| **`inspecto-connectors/`** | Remote connectors (SFTP, FTP/FTPS, database export) and their network dependencies — kept *out* of the core so the core stays lean. Discovered at runtime via the connector SPI. |
| **`inspecto-agent/`** | The optional AI assist agent (config/schedule/SQL/diagnose skills), built on a reusable agent library. |
| **`inspecto-agent-hosted/`** | Hosted model providers (the cloud-AI SDKs). Omitted entirely from air-gapped builds. |
| **`inspecto-ui/`** | The Angular operator web console (single-page app), served from the engine process. |

A planned **`inspecto-security/`** module will carry the Standard-edition authentication/authorization code (see Sections 7 and 10).

### 4.2 The layer cake

```
                 ┌─────────────────────────────────────────────┐
                 │   Operator Web Console (Angular SPA)         │
                 │   Dashboard · Flows · Sources · Events ·     │
                 │   Jobs · Cases/Issues · Config authoring     │
                 └───────────────────────┬─────────────────────┘
                                         │ REST (~30 routes)
 ┌───────────────────────────────────────┴──────────────────────────────┐
 │                          Control plane                                 │
 │   HTTP API · Scheduler (cron/event/manual) · Metrics · Audit          │
 ├───────────────────────────────────────────────────────────────────────┤
 │  AI Assist agent   │  Operational Intelligence  │  Flow-graph runtime  │
 │  (config/SQL/      │  Events → Alerts →         │  Authored pipelines  │
 │   schedule/diagnose)│  Issues/Cases · RCA       │  as branching graphs │
 ├────────────────────┴────────────────────────────┴──────────────────────┤
 │   Stage-2 enrichment (joins/aggregations over partitioned output)      │
 ├───────────────────────────────────────────────────────────────────────┤
 │   Stage-1 ingest — the M..N multiplexer (parse → transform → partition)│
 ├───────────────────────────────────────────────────────────────────────┤
 │   Data Acquisition framework (discover → validate → fetch → dedup)     │
 │   SFTP · FTP/FTPS · DB export · local — via the connector SPI          │
 ├───────────────────────────────────────────────────────────────────────┤
 │   Embedded analytical database (DuckDB) + on-disk Parquet/CSV          │
 └───────────────────────────────────────────────────────────────────────┘
```

Every layer above Stage-1 is *optional* and additive — the engine works with none of them, and each can be adopted independently.

### 4.3 Why these choices

- **Embedded analytical DB instead of a cluster.** DuckDB gives columnar, vectorized SQL performance on a single node and reads/writes Parquet natively. The common "collect → normalize → partition → query" workload runs faster on one well-fed node than the orchestration overhead of a cluster would allow — and with a fraction of the operational cost.
- **Framework-free control plane.** A small hand-built HTTP layer keeps the dependency count (and CVE/attestation surface) low — a measurable asset for regulated deployments.
- **SPI seams everywhere.** Connectors, parsing frontends, output formats, ingest strategies, secrets, and (soon) authentication are all behind service-provider interfaces, so capability is added by *assembling a module*, not by editing the core.

---

## 5. The processing model (how data flows through)

### 5.1 Stage-1: the M..N multiplexer

A Stage-1 run takes a set of input files and produces a set of partitioned outputs:

1. **Discover & collect.** The acquisition framework lists candidate files (locally or from a remote source), validates readiness/stability, fetches them, and deduplicates against a fingerprint ledger so re-sent files are not re-processed.
2. **Parse.** A *parsing frontend* turns raw bytes into rows. Today: a rich **delimited grammar** (CSV and friends, with quote/escape/encoding/compression controls), a **fixed-width** frontend, and a **plugin** frontend for proprietary/binary/multi-event-type formats.
3. **Transform.** Light, per-record transformations and schema casting are applied; records that fail validation are routed to a rejects path rather than silently dropped.
4. **Partition & write.** Rows are demultiplexed into Hive-partitioned Parquet/CSV. The number of output partitions is independent of the number of input files — that is the "M..N" property.
5. **Commit.** Output is committed with markers written *last* and an fsync'd commit log, so a crash mid-run leaves a re-processable, never a half-visible, state.

Each batch runs in isolation with its own ephemeral database connection, which is what makes runs both parallelizable and crash-safe.

### 5.2 Stage-2: enrichment

Stage-2 runs SQL joins and aggregations *over* the Stage-1 partitioned output — daily rollups, cross-feed joins, derived tables — producing further managed outputs. It reuses the same scheduling, audit, and commit machinery.

### 5.3 The flow-graph model (authored pipelines)

Beyond the linear Stage-1 path, Inspecto now supports authoring a pipeline as a **directed graph of nodes** (NiFi-style): sources, parse stages, transforms (filter, validate, route, dedup, split, map/select/derive, merge), and sinks (persistent, materialized, or logical view). This unlocks branching that the single-SELECT legacy path could not express — e.g. *route EMEA rows one way, APAC another, send rejects to a quarantine sink, and dedup before a rollup* — all in one declarative flow.

Authored flows are first-class **jobs**: they run on cron, on an upstream pipeline's commit, manually, or chained after another job; their runs are reported and audited like any other job. The engine validates a flow's structure before running it (DAG checks, node-output contracts) so a broken graph is rejected at author time, not at 3 a.m.

### 5.4 Data-plane provenance (lineage)

Inspecto can record **per-edge record counts** for every flow run — how many records flowed along each relationship out of each node — and check a **conservation invariant**: at non-amplifying nodes, records-in must equal records-out, so silent loss or unexpected amplification raises a managed alert. The operator console paints these counts onto the flow graph as a weighted Sankey overlay, turning the structure diagram into a live data-flow picture for a chosen run. This feature is **off by default** (zero overhead until enabled).

### 5.5 Idempotency & recovery

A defining property worth calling out on its own: **a run that is interrupted at any point is safe to re-run.** Output is committed with markers written *last*, an fsync'd commit log records what has durably landed, and branching flows use a branch-commit coordinator so each branch commits independently and a replay skips already-committed branches. The practical consequence for operators: there is no "did it half-finish?" forensic exercise — re-trigger the run with the same identity and the engine finishes exactly what remained. Deduplication at the acquisition layer ensures the *inputs* are not re-collected either.

### 5.6 Worked scenarios

To make the model concrete, three representative end-to-end flows:

**Scenario A — Onboard a partner CDR feed (no code).** A new partner drops daily call-detail-record files on an SFTP server. An operator defines a connection profile once (host, credentials via a masked secret, optional bastion + host-key pin), then authors a feed config that references it, selects the delimited parsing frontend, declares the schema, and sets a daily schedule. On first run the acquisition framework discovers the files, waits for them to stop changing, fetches only files newer than the last run, deduplicates re-sends, parses and validates rows (routing rejects aside), and writes partitioned Parquet. No bespoke code was written; the next partner is a copy-and-adjust of the same config.

**Scenario B — A file goes missing.** Partner files are numbered sequentially. One night, file `…_0042` never arrives. Sequence-gap detection notices the hole in the numbered sequence, emits a structured event, and the operational-intelligence layer promotes it to a managed **alert** — de-duplicated so a persistent gap doesn't spam. The alert becomes an assignable object with a lifecycle; an operator picks it up, applies an RCA template, links it to the partner case, and the originating event plus the affected run are correlated to it. Nothing was silently lost.

**Scenario C — Author a branching flow visually.** An analyst needs EMEA and APAC rows partitioned separately, invalid rows quarantined, and a deduplicated daily rollup. In the console's flow editor they drag a source, a parse stage, a *route* transform (by region), a *validate* transform (rejects → a quarantine sink), and a *dedup* + *materialized rollup* sink — connecting them on the canvas. The engine validates the graph (DAG + node-output contracts) before it can run, the flow is saved as a first-class job on a daily schedule, and after the first run the provenance overlay shows exactly how many records took each branch — with a conservation alert if any were lost unexpectedly.

---

## 6. Capability inventory

This section is the substance for technical reviewers: what is built and shipping today. Items are grouped by layer.

### 6.1 Ingestion & parsing

- **Delimited grammar** — externalized, configurable CSV-family parsing: delimiter, quote, escape, comment, encoding, compression, null-string handling, strict mode. Surfaces the safe high-performance read knobs of the underlying engine.
- **Fixed-width** parsing (text and binary record slicing).
- **Plugin frontend** — a `StreamingFileIngester` SPI for proprietary/binary/multi-event-type formats (e.g. telecom segment records, TLV/binary). One streaming SPI plus an appender gives roughly a 75× throughput improvement over naive row-by-row handling for large files.
- **Compression** — transparent streaming read of gzip, bzip2, and zip inputs.
- **Schema & segment models** — declarative schemas with field types, validation rules, and reject routing; multi-segment/multi-event-type records via a selector.

### 6.2 Data Acquisition & File Collection framework

A complete framework (delivered across Phases A–F) for getting files *to* the engine reliably and securely:

- **Connector SPI** with shipping connectors: **SFTP**, **FTP/FTPS**, and **database export** (SQL query → CSV → the normal batch path), plus local filesystem.
- **Secure transport** — FTPS (explicit/implicit TLS), SSH **host-key pinning** (`known_hosts`/fingerprint/strict), and traversal **through an SSH bastion** for SFTP, FTP/FTPS, and DB export alike (including FTP passive-data forwarding).
- **Readiness/stability gate** — a file is only collected once it has stopped changing, so partial uploads are never ingested.
- **Reusable connection profiles** — connection details and secrets are defined once and referenced by many feeds; secrets are masked in the API and on edit.
- **Fingerprint ledger & dedup** — path/metadata/checksum strategies so a re-sent file is recognized and skipped; content dedup is configurable.
- **Incremental high-watermark** — only collect files newer than the last run (file-level by modified-time; row-level for DB export by a monotonic watermark column), saving bandwidth on monotonic-arrival sources.
- **Sequence-gap detection** — a missing file in a numbered sequence is detected, raised as an event, and promoted to a managed alert/case.
- **Resilience** — retry with backoff + jitter, a per-source circuit breaker, dead-lettering of corrupt downloads, configurable post-actions (delete/move/rename after success), parallel fetch, and rate limiting.

### 6.3 Control plane, scheduling & observability

- **REST control plane** (~30 routes) covering pipelines, jobs, sources, connections, flows, events, alerts, objects (cases/issues), config authoring, metrics, and AI assist.
- **Scheduler** with cron, interval, event-driven (`on_pipeline`-commit), chained, and manual triggers; misfire catch-up; per-job enable/disable.
- **Metrics** — a dependency-free Prometheus-compatible registry (files discovered/downloaded/failed, bytes transferred, fetch durations, watermark skips, sequence gaps, and more).
- **Three-layer audit** — per-file status, per-batch summary, and input→output lineage.
- **Durable job reporting** — an optional analytical store of every job run with success-rate, p50/p95 durations, and failure-trend queries (off by default).

### 6.4 Operational Intelligence

A layer that turns raw signal into managed, assignable work:

- **Event engine** — a structured event stream (everything above debug), with full-filter search, saved views, CSV export, and live tail.
- **Alert engine** — config-driven alert rules that fire on conditions and promote to managed **alert** objects.
- **Issue & case management** — managed objects with lifecycle state machines (open → assigned → in-progress → resolved → closed), SLA tracking, comments, attachments (metadata), and a first-class **object-link graph** (contains / escalated-from / caused-by / related-to).
- **Root-cause analysis** — reusable RCA templates applied to an object to seed structured investigation.
- **Correlation** — events, runs, and managed objects share a correlation id, so a late-file event, the run it belongs to, and the case it raised are all linked.

### 6.5 AI Assist agent (optional)

- **Seven shipped skills** covering config generation, scheduling, SQL authoring, and diagnosis — each **draft-only / confirm-first**.
- **Natural-language authoring** — "ingest these CDR files daily" becomes a validated config draft to accept or edit; "every weekday 6am after adjustment_etl" becomes a schedule; "revenue per region, last 30 days" becomes validated SQL.
- **Safety model** — generated SQL is validated in a locked-down database sandbox; the agent proposes, tested endpoints dispose; hosted-model SDKs are physically absent from air-gapped builds, and a local model (Ollama) or hosted providers (Anthropic/OpenAI/Gemini) can be routed per deployment.

### 6.6 Operator web console

A single-page Angular application served from the engine process, with operational panes for: dashboard (recent activity + acquisition summary), **Flows** (graph visualization, combined cross-flow view, and a visual flow editor with provenance overlay), **Sources/Acquisition** (metrics + connections CRUD), **Events/Activity** (grid, filters, live tail, export, saved views), **Jobs & Schedules** (schedule management + run reporting), and **Cases/Issues** (list, detail, lifecycle, links, RCA, correlated events). The console is built on a shared design system with accessibility remediation and a no-hardcoded-color guard.

### 6.7 How the layers reinforce each other

The capabilities above are not a feature checklist — they compose. A late file (acquisition) becomes a structured event (event engine), which becomes a managed alert and case (operational intelligence), correlated back to the run that should have consumed it (control plane). A branching flow (flow-graph) produces per-edge counts (provenance) that raise a conservation alert (operational intelligence) when records vanish. An analyst's natural-language request (AI assist) becomes a validated config or SQL that the control plane executes and audits. The correlation id and the managed-object graph are the connective tissue: every signal can be traced to its origin and to the work it created. This composition is what turns a collection of capabilities into an operations *platform*.

---

## 7. Editions & deployment model

Inspecto is packaged as **three editions that are build flavors of one codebase** — never separate branches or forks. An edition is simply *which modules are assembled* and *which runtime flags are set*. This is the same mechanism already used to omit the hosted-AI SDKs from air-gapped builds.

| Aspect | **Personal** | **Standard** | **Enterprise** (future) |
|---|---|---|---|
| Transport | Plain HTTP, localhost-bound | HTTPS (keystore; FIPS provider option) | HTTPS, TLS at gateway/LB |
| Authentication | **None** | Delegated to external IAM (Keycloak/WSO2/Okta/Entra); app is an OIDC resource server | Same, centralized IAM + introspection |
| Authorization | None | **RBAC + ABAC** from token claims | RBAC/ABAC + per-tenant boundaries |
| Secrets | env / file | File + OS keystore, or Vault | Vault / cloud secret manager |
| State | local disk | local disk (optional Postgres) | **shared backends** (Postgres / object store) |
| Scheduler | in-JVM | in-JVM | **distributed coordination** |
| Compliance scope | none | SOC 2 / ISO 27001 / FedRAMP / HIPAA / PCI | inherits Standard + multi-node controls |
| Packaging | core fat-JAR | core + `inspecto-security` module, TLS on | + shared-store modules, coordinator |

**Why this model matters commercially:** fixes and features land **once** in the common core and are inherited by every edition. There is no per-edition maintenance fork. Authentication, multi-tenancy, and distribution are *additive* — they bolt onto seams (an `Authenticator` SPI, pluggable state stores, distributed-scheduler hooks) that the architecture already leaves open. The current core is deliberately **auth-free**, which makes Personal genuinely zero-friction and keeps the security code isolated in the edition that needs it.

The lean dependency tree is itself a Standard/Enterprise selling point: a small software bill of materials means fewer CVEs to attest and a smaller FedRAMP/SOC 2 surface than a framework-based competitor.

### 7.1 Deployment topologies

| Topology | Edition | Shape |
|---|---|---|
| **Single-node, on a workstation/VM** | Personal | One process, localhost-bound, local disk. Ideal for development, evaluation, and small standalone feeds. |
| **Hardened single-node service** | Standard | One process behind TLS, authenticating against the org's IAM, RBAC/ABAC enforced, optional Postgres for status, actor-attributed audit. The common production shape. |
| **Air-gapped / sovereign** | Personal or Standard | Same artifact with hosted-AI SDKs omitted and a local model serving the assist agent; no outbound connectivity required. |
| **Multi-node / shared-state** | Enterprise (future) | State on shared backends (Postgres + object store), distributed scheduler coordination, per-tenant boundaries. Opt-in when scale demands it. |

In every topology it is the **same build**, differing only by which modules were assembled and which flags are set. There is no separate codebase to certify per topology.

### 7.2 Commercial view

The edition model is also the monetization model. **Personal** is the zero-friction, no-auth entry point (and the natural open/community tier). **Standard** is the commercial sweet spot: the moment a buyer needs authentication, authorization, TLS, and a compliance posture, they need the `inspecto-security` module — which is exactly the line between free and paid. **Enterprise** captures the multi-node, multi-tenant, shared-state deployments where distributed coordination and per-tenant isolation matter. Because fixes and features land once in the common core, the cost of maintaining three tiers is close to the cost of maintaining one — the margin structure of editions, without the maintenance structure of forks.

---

## 8. Quality, security, and operability

### 8.1 Engineering quality

- **Test posture:** the engine carries on the order of **800+ passing tests**; the connector, agent, hosted-provider, and UI modules add their own suites. Remote connectors are tested against *embedded* servers (SSH/FTP) so the suite is hermetic and CI-friendly.
- **Deterministic builds & tree hygiene:** a normalized line-ending policy makes "is my tree clean?" deterministic across Windows and Linux.
- **Living documentation:** a single operations source-of-truth document is kept current with every behavioral change (enforced by repository convention), so on-call investigators have an authoritative map of components, events, metrics, persisted state, flags, and API routes.
- **Disciplined release process:** semantic versioning + conventional commits; one mainline; editions assembled per-build; a guarded merge-forward policy so fixes land on the oldest supported line and flow forward.

### 8.2 Security posture

- **Small attack surface** by construction (framework-free, lean deps).
- **Sandboxed AI SQL** — generated SQL runs in a locked-down database sandbox with extension auto-loading off and memory/thread/timeout caps.
- **Path jailing** — config and artifact writes are sanitized and jailed to their roots; unsafe identifiers are rejected on write and treated as absent on read.
- **Secret masking** — connection secrets are masked in the API and preserved correctly across edits.
- **Edition-gated access control** — the Standard edition adds OIDC resource-server validation (issuer/audience/expiry/JWKS) and RBAC/ABAC without touching the core.
- **Air-gap friendly** — hosted-AI SDKs are physically omitted from air-gapped builds; a local model can serve the assist agent.

### 8.3 Operability

- Crash-isolated, idempotent batches; markers-last commit ordering; fsync'd commit logs and branch-commit coordination for branching flows.
- Full metrics, structured events, and three-layer audit out of the box.
- A REST control plane and operator console for monitoring, scheduling, triage, and config authoring.
- A documented troubleshooting playbook per component.

### 8.4 Operational economics (TCO)

The architecture choices translate directly into cost-of-ownership advantages that resonate with budget owners:

- **No cluster to run.** A single process with an embedded engine means no cluster sizing, no node fleet, no broker/coordinator services to patch and monitor. The operational headcount to run Inspecto is a fraction of a distributed stack's.
- **No external runtime services in the core.** The fat JAR bundles its database and parsers; there is no separate warehouse, message bus, or orchestration server to license, host, and secure for the base product.
- **Cheap marginal feeds.** Because a feed is config (or a generated draft), onboarding the *n*-th feed is hours, not a project — the cost curve flattens instead of compounding.
- **Small SBOM, small patch surface.** Fewer dependencies means fewer CVEs to track, fewer emergency patch cycles, and a smaller compliance-attestation effort — a recurring, often-underestimated cost.
- **Predictable scaling.** Vertical scale (a bigger node) covers a wide band before the optional distributed tier is needed, and that tier is adopted only when a real workload requires it — capacity is bought when used, not speculatively.

---

## 9. Where we are today (maturity snapshot)

| Capability area | Maturity | Notes |
|---|---|---|
| Stage-1 ingest (M..N multiplexer) | **Mature / shipped** | Hardened across multiple release lines |
| Delimited & fixed-width parsing | **Mature / shipped** | Externalized grammar; plugin SPI for binary |
| Stage-2 enrichment | **Mature / shipped** | Joins/aggregations over partitioned output |
| Control plane, scheduler, metrics, audit | **Mature / shipped** | ~30 REST routes; cron/event/manual triggers |
| Data Acquisition framework (A–F) | **Shipped** | SFTP/FTP/FTPS/DB-export; dedup; gaps; resilience |
| Operational Intelligence (events/alerts/cases) | **Shipped** | Managed objects, links, RCA, correlation |
| AI Assist agent (7 skills) | **Shipped** | Draft-only; local + hosted model routing |
| Operator web console | **Shipped** | All operational panes live |
| Flow-graph platform (authoring + execution) | **In mainline** | NiFi-style flows run as first-class jobs; visual editor shipped |
| Data-plane provenance / lineage | **In mainline** | Per-edge counts, conservation checks, Sankey overlay (off by default) |
| Auth-free common core (edition realignment) | **In mainline** | Core is auth-free; security becomes an edition module |
| Standard edition security (`inspecto-security`) | **Planned** | OIDC resource-server + RBAC/ABAC behind an SPI |
| Object-storage / NFS-SMB connectors | **Planned** | On the existing connector SPI |
| Unified `parsing:` grammar (JSON / regex frontends) | **Planned** | Additional thin frontends over the shared backend |
| Enterprise distributed tier | **Future** | Shared-state backends + distributed scheduler |

"In mainline" means built, tested, and integrated on the development line, targeting the next release; "shipped" means released on the active line. The detailed status of the flow-graph track and acquisition framework is maintained in the engineering docs.

---

## 10. Roadmap

The forward plan is organized into three horizons — **Now**, **Next**, and **Later** — plus the edition trajectory that cuts across all three. This section is the executive view; the detailed, sequenced plan with rationale lives in [ROADMAP.md](ROADMAP.md).

### 10.1 Strategic themes

1. **Commercial readiness** — make Standard a sellable edition: external-IAM authentication, RBAC/ABAC, HTTPS, and the compliance posture that regulated buyers require — all additively, without forking the core.
2. **Breadth of ingestion** — more places data lives (object storage, network shares) and more formats parsed natively (JSON, regex/text), via the existing SPI seams.
3. **Self-service authoring** — lower the barrier from "expert writes config" to "operator composes a flow visually and the assist agent fills the gaps."
4. **Trust & transparency** — provenance/lineage and conservation checks as a default-on operational guarantee, not an add-on.
5. **Scale-out optionality** — keep the Enterprise distributed seams open without compromising the single-node ethos that makes the product lean.

### 10.2 Now (in mainline, hardening toward the next release)

- **Flow-graph platform** — authoring, validation, execution as first-class jobs, multi-source merge, incremental flows, materialized views, and a visual editor. *Status: built; final hardening and live end-to-end verification with real job configs.*
- **Data-plane provenance** — per-edge counts, conservation invariant → managed alerts, and the Sankey overlay. *Status: built and tested; off by default.*
- **Edition realignment** — the auth-free common core that makes the three-edition model real. *Status: in mainline; commit/release gated on stakeholder go-ahead.*
- **The `sink.view` consumer** — query a flow's logical views over REST. *Status: shipped in mainline.*

### 10.3 Next (committed direction, not yet started)

- **`inspecto-security` module (Standard edition)** — an `Authenticator` SPI plus OIDC resource-server validation, RBAC/ABAC from token claims, HTTPS, and actor-attributed audit. This is the single highest-leverage item for commercialization. *Incremental hardening on the framework-free core — explicitly not a Spring/Quarkus migration.*
- **Object-storage & network-share connectors** — S3 / GCS / Azure Blob / MinIO and NFS/SMB on the existing connector SPI; the analytical engine already speaks object storage natively.
- **Unified `parsing:` grammar** — promote today's frontends under one `parsing:` block and add **JSON** and **text/regex** frontends, each a thin frontend producing rows for the shared backend (existing configs keep working via aliases).
- **Flow authoring polish** — round out the visual editor and add a dedicated run endpoint for authored flows; adapter stream-consumer runtime for streaming sources.
- **Etag/version fingerprint dimensions** — richer dedup for object-store connectors (depends on those connectors landing).

### 10.4 Later (future / vision)

- **Enterprise distributed tier** — shared-state backends (Postgres for status, object store for events, shared secrets), distributed scheduler coordination, work distribution, and per-tenant ABAC. The seams are already open; this is opt-in and against the single-JVM default by design.
- **Richer "AI behind every screen" UX** — inline natural-language authoring across the console (a parallel track; benefits from GPU availability on the deployment).
- **Multi-step agent graphs** — provision → watch → roll back orchestration, beyond today's single-shot generate→validate→return skills.
- **Push-based / event-notification discovery** — react to source-side notifications rather than polling.

### 10.5 Cross-cutting & continuous

- **UI platform currency** — keep the console current with the Angular release train.
- **Agent library bump** — adopt the latest reusable agent-kernel (optional; no behavior change).
- **Documentation & onboarding** — keep the living operations guide and these stakeholder docs current with each change.

### 10.6 Sequencing logic

The recommended order is **(1) edition security → (2) object-storage connectors → (3) unified parsing/JSON → (4) authoring polish**, because: security unblocks commercial deployment (the gating item for revenue); object storage is the most-requested ingestion gap and reuses a proven SPI; parsing breadth widens the addressable feed set; and authoring polish compounds the value of everything beneath it. The Enterprise distributed tier is demand-gated — pulled forward only when a deployment's scale actually requires it.

---

## 11. Risks & open decisions

| Item | Risk / decision | Mitigation / recommendation |
|---|---|---|
| **Single-node ceiling** | The lean, single-JVM design has a throughput ceiling per node. | It is a deliberate trade for operational simplicity and already covers a wide band; the Enterprise distributed tier is the escape hatch when a real workload exceeds it. Keep the seams open (done). |
| **Edition security scope** | Standard-edition auth is the gating item for commercialization; scope creep here delays revenue. | Deliver the minimum sellable security (external-IAM delegation + RBAC/ABAC) first; do *not* build user management into the core — that is the IAM's job. |
| **Flow-graph go-live** | Powerful new surface area; needs live end-to-end verification with real job configs (current verification used synthetic data on a config-less dev backend). | Stand up a representative `type: flow` job with seeded data and verify the full run + provenance path before release. |
| **Connector breadth vs. core leanness** | Each new connector adds dependencies. | Keep all network deps in `inspecto-connectors` (done) so the core fat JAR stays lean and air-gap friendly. |
| **Naming consistency** | Product is "Inspecto"; some shipped docs/UI still say "Inspector" for the console. | Finalize the console name and reconcile in one pass. |
| **AI on air-gapped nodes** | The richest assist UX benefits from a GPU. | Local-model routing (Ollama) already works; treat GPU-class inline UX as a parallel, opt-in track. |

---

## 12. Appendix

### 12.1 Glossary

- **M..N multiplexer** — the Stage-1 property that the number of output partitions is decoupled from the number of input files.
- **Flow / flow-graph** — an authored pipeline expressed as a directed graph of nodes (source/parse/transform/sink).
- **Provenance / conservation** — per-edge record counting for a flow run, and the invariant that records-in equals records-out at non-amplifying nodes.
- **Connection profile** — reusable, named connection + credentials referenced by many feeds.
- **Fingerprint ledger** — the record of seen files used for deduplication (path/metadata/checksum).
- **Watermark** — the high-water mark used for incremental collection (file modified-time, or a monotonic DB column).
- **Managed object** — an alert/issue/case with a lifecycle, links, comments, and RCA.
- **Edition** — a build flavor (Personal/Standard/Enterprise) assembled from one codebase via modules and flags.
- **SPI** — service-provider interface; the seam through which connectors, parsing frontends, secrets, and (soon) auth are added without editing the core.

### 12.2 Representative metrics

Files discovered / downloaded / downloads-failed; bytes transferred; fetch durations; active connections; watermark-skipped; sequence gaps; post-actions-failed; per-job success rate and p50/p95 durations. (Exposed in a Prometheus-compatible format.)

### 12.3 Representative API surface (illustrative)

Pipelines & config authoring · Jobs & schedules (+ run reporting) · Sources & acquisition metrics · Connections CRUD · Flows (catalog, graph, combined, authored CRUD, dry-run, raw, views) · Provenance (per-run edge counts, run list) · Events (search, views, export) · Alerts · Objects (cases/issues, links, graph, comments, attachments, RCA) · Metrics · AI assist.

### 12.4 Source documents

This overview synthesizes: the consolidated reference set (`docs/consolidated/`), the editions model (`docs/EDITIONS.md`), the branching/release policy (`docs/BRANCHING.md`), the flow-graph design (`docs/okf/backend/pipeline-graph/pipeline-graph-design.md`), the data-acquisition framework notes, and the operations source-of-truth (`docs/ADVANCED_GUIDE.md`).

---

*Maintained by Inspecto engineering. For the forward plan see [ROADMAP.md](ROADMAP.md); for the presentation see [PRESENTATION.md](PRESENTATION.md).*
