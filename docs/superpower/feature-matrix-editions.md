# Inspecto — Feature Matrix & Edition Alignment (MoSCoW)

**Compiled 2026-07-02** from `docs/FEATURE_INVENTORY.md`, `docs/roadmap/ROADMAP.md`,
`docs/roadmap/STAKEHOLDER_OVERVIEW.md`, `docs/EDITIONS.md`, `docs/GLOSSARY.md`, and current session state.
Vocabulary is canonical per `docs/GLOSSARY.md` (Pipeline · Source · Dataset · Incident · Measure · Widget).

**Positioning.** Inspecto is an all-in-one data acquisition + data management + BI + investigation platform —
one lean artifact intended to replace **Apache NiFi** (visual pipelines + collection), **Tableau / Superset**
(self-service BI), **Jira** (operational incident/case work), and the glue-script layer (dedup, gap detection,
retention, lineage) that usually surrounds them.

**Editions** (build flavors, never branches): **P** = Personal · **S** = Standard · **E** = Enterprise.
✅ = in edition · ➕ = edition adds it · — = not in edition. Tier placements not already fixed in
`EDITIONS.md` are marked *(proposed)*.

**Status legend:** `SHIPPED` released · `MAINLINE` built/tested on master · `UI-PROTO` UI shipped, mock-backed
(no real persistence) · `PLANNED` committed direction · `FUTURE` demand-gated.

---

## 1. What we HAVE

| # | Capability | Detail (compressed) | Status | P | S | E |
|---|---|---|---|---|---|---|
| H1 | **Data acquisition framework** | Connection profiles (secrets masked), SFTP/FTP/FTPS/DB-export + local; bastion tunnel, host-key pinning; stability gate; dedup (path/metadata/checksum, EXACTLY_ONCE); incremental watermark; sequence-gap detection; retry/backoff, circuit breaker, rate limit, post-actions | SHIPPED | ✅ | ✅ | ✅ |
| H2 | **Stage-1 ingest (M..N multiplexer)** | Delimited grammar (engine choice, junk/tail skip), fixed-width text+binary, plugin SPI (multi-segment/binary), gz/bz2/zip, multi-schema dispatch, batch planning, auto-chunking, crash-isolated idempotent commits | SHIPPED | ✅ | ✅ | ✅ |
| H3 | **Schema & validation** | Declarative Schemas (types, classification/PII tags), transform rules (DIRECT/EXPR/CONCAT_DT/FILENAME_DATE), multi-format dates, reject routing + quarantine | SHIPPED | ✅ | ✅ | ✅ |
| H4 | **Medallion data plane (ELT)** | Raw files → Hive-partitioned Parquet **Tables** (bronze→silver) → Stage-2 enrichment/joins/rollups → **Derived Tables / Matrix** (gold); **Views** (logical, `/views` API); DuckLake/Postgres catalog stub | SHIPPED | ✅ | ✅ | ✅ |
| H5 | **Incremental, event-driven medallion** | `on_pipeline`-commit Triggers fire enrichments/Pipelines per Batch; `incremental_column` watermarks; cron + catch-up; chained + manual Runs | SHIPPED | ✅ | ✅ | ✅ |
| H6 | **Authored Pipelines (visual DAG)** | NiFi-style Steps: filter/route/derive/select/validate/dedup/split/merge + persistent/view Sinks; DAG validation at author time; run as first-class Jobs; visual editor | MAINLINE | ✅ | ✅ | ✅ |
| H7 | **Provenance & lineage** | Per-edge record counts, conservation invariant → Alerts, Sankey overlay per Run; asset lineage graph (`/catalog/graph`); file→Table→Pipeline-step stitch (`/lineage?store=`) | MAINLINE | ✅ | ✅ | ✅ |
| H8 | **Jobs & Scheduler** | Executables (Pipeline/Job), cron/event/manual/chained Triggers, misfire catch-up, enable/disable, durable Run reporting (success rate, p50/p95) | SHIPPED | ✅ | ✅ | ✅ |
| H9 | **Operational intelligence** | Event stream (search/saved views/export/live tail), Alert Rules on Metrics, **Alert → Incident → Case** lifecycle, object-link graph, RCA templates, correlation ids, SLA tracking, comments | SHIPPED | ✅ | ✅ | ✅ |
| H10 | **Observability & audit** | Prometheus-compatible Metrics, three-layer audit (file/batch/lineage), structured Events | SHIPPED | ✅ | ✅(+actor-attributed) | ✅ |
| H11 | **BI Studio** | Dataset/Chart(Widget)/Dashboard authoring; Visualization Type plugin registry (incl. scatter/funnel); **Measures**; KPI & Reports gallery; dashboard quick-filters, drill-through, time grain, PNG export; a11y alt text | UI-PROTO | ✅ | ✅ | ✅ |
| H12 | **Component metamodel + Registry** | Every artifact = `Component {kind, config, parts, wiring}`; derived reuse graph across Pipelines/Jobs/Dashboards/Widgets | MAINLINE | ✅ | ✅ | ✅ |
| H13 | **Multi-space (multi-project)** | Isolated Spaces (config/data/audit/duckdb), space CRUD, per-space export/import w/ dry-run, UI switcher | SHIPPED | ✅ | ✅ | ✅ |
| H14 | **AI Assistant** | 7 draft-only skills (config gen, scheduling, SQL, diagnose); sandboxed SQL; local (Ollama) or hosted model routing; air-gap builds omit hosted SDKs | SHIPPED | ✅ | ✅ | ✅ |
| H15 | **Operator console** | Dashboard, Runs, Sources, Events, Jobs, Incidents/Cases, Pipelines editor, Catalog, Studio; shared design system, tiered data-table, WCAG gate | SHIPPED | ✅ | ✅ | ✅ |
| H16 | **Packaging & editions machinery** | One fat JAR (~90 MB, zero runtime services), jlink runtime, per-edition bundles (`package.ps1 -Edition`), runnable example suite, ServiceLoader/SPI seams | SHIPPED | ✅ | ✅ | ✅ |

> **Honesty notes.** H6/H7: mainline, final live verification pending. H11 + the redesigned Pipelines/parser
> authoring UI are **mock-backed** — the backend `ComponentStore` kind enum is closed, so Studio artifacts don't
> persist for real yet (see M2/M3). Core is auth-free by design; Standard security (M1) is not yet built.

## 2. MUST have (gates the all-in-one claim / revenue)

| # | Feature | Why it's a must | Replaces / unblocks | Effort | P | S | E |
|---|---|---|---|---|---|---|---|
| M1 | **`inspecto-security` module** — Authenticator SPI, OIDC resource server (external IAM), RBAC+ABAC, HTTPS/FIPS, actor-attributed tamper-evident audit | The revenue gate; nothing sells into regulated buyers without it | Commercial deployment | L | — | ➕ | ➕ |
| M2 | **Studio persistence** — widen backend `ComponentStore` kinds so Datasets/Widgets/Dashboards persist server-side | Studio is currently mock-backed; without it the BI story is a demo | Tableau / Superset | M | ✅ | ✅ | ✅ |
| M3 | **Live BI query path** — Widgets/Measures execute real DuckDB queries against Datasets (Tables/Derived Tables/Views) | Dashboards must render real data, not fixtures | Tableau / Superset | M–L | ✅ | ✅ | ✅ |
| M4 | **Object-storage & share connectors** — S3/GCS/Azure Blob/MinIO + NFS/SMB on the connector SPI | Most-requested ingestion gap; modern feeds live in buckets | NiFi + scripts | M–L | ✅ | ✅ | ✅ |
| M5 | **Unified `parsing:` + JSON/NDJSON + text/regex frontends** | JSON is table stakes; regex covers LDIF/flat-XML | NiFi processors | M | ✅ | ✅ | ✅ |
| M6 | **Expectation engine** — declarative data-quality rules (non-null/range/regex/referential) authorable and attached to Jobs/Pipeline Steps, failures → Incidents | User-stated requirement ("design Rule, attach to job"); today only schema casts + reject routing | Great-Expectations-style scripts | M | ✅ | ✅ | ✅ |
| M7 | **Pipeline authoring wired to backend** — dedicated run endpoint for authored Pipelines, connection workbench + parser dialog persisted (today prototype), config CRUD completion (one-click apply of Assistant drafts) | Closes author→validate→run→observe loop entirely in the console | NiFi authoring | M | ✅ | ✅ | ✅ |
| M8 | **Notification channels** — Alert/Incident delivery via email/webhook, per-user preferences | An alerting platform that can't notify anyone can't replace Jira/ops scripts | Jira + pager glue | S–M | ✅ | ✅ | ✅ |

## 3. SHOULD have (would have been better already)

| # | Feature | Why | Effort | P | S | E |
|---|---|---|---|---|---|---|
| S1 | **Decision Rule surface** — first-class business-routing rule authoring (today buried in `transform.route` config) | Completes the three-rule-engine model (Expectation / Alert Rule / Decision Rule) | M | ✅ | ✅ | ✅ |
| S2 | **Link Analysis Studio** (Entity/Link graph, P3 — designed, deferred) | The heart of the **xDR-style investigation** positioning: pivot records as business entities inside Cases | L | *(proposed)* — | ➕ | ➕ |
| S3 | **Streaming source consumer** — adapter stream-consumer runtime (land-then-ack seam exists) | Kafka-ish feeds without a second tool | M | ✅ | ✅ | ✅ |
| S4 | **Scheduled reports & export delivery** — Dashboard/Report on a Trigger → PDF/PNG/CSV to email/webhook | Superset/Tableau parity for ops reporting | M | *(proposed)* — | ➕ | ➕ |
| S5 | **Row-level calculated columns in Studio Datasets** (deferred from BI pass) | Analyst self-service without Pipeline round-trip | S | ✅ | ✅ | ✅ |
| S6 | **Incident workflow depth** — assignment queues, SLA policies w/ escalation, watchers | Full Jira displacement for ops work (lifecycle/links/RCA already exist) | M | ✅ | ✅ | ✅ |
| S7 | **Etag/version fingerprint dedup dimensions** (follows M4) | Correct dedup semantics for object stores | S | ✅ | ✅ | ✅ |
| S8 | **Optional Postgres state store** | Standard-tier durability beyond local disk | M | — | ➕ | ✅ |
| S9 | **Maintenance job library** — retention/compaction/vacuum/archival as configurable Jobs (partially exists: cleanup) | Kills the last "maintenance scripts" category | S | ✅ | ✅ | ✅ |

## 4. NICE to have (demand-gated / vision)

| # | Feature | Trigger | P | S | E |
|---|---|---|---|---|---|
| N1 | **Enterprise distributed tier** — shared backends (Postgres/object store/secrets), distributed scheduler, work distribution, per-tenant ABAC | A workload that outgrows one node | — | — | ➕ |
| N2 | **AI behind every screen** — inline NL authoring across console; multi-step agent graphs (provision→watch→rollback) | GPU availability / demand | ✅ | ✅ | ✅ |
| N3 | **Push/event-notification discovery** (react to source-side events vs polling) | A source that emits notifications | ✅ | ✅ | ✅ |
| N4 | **Public/embedded dashboard sharing** (tokened read-only links, iframe embed) | External-consumer demand | — | ➕ | ➕ |
| N5 | **Alerting on Measures** (BI-threshold alerts, distinct from ops Alert Rules) | BI adoption | ✅ | ✅ | ✅ |
| N6 | **Semantic layer / headless BI API** (Measures & Datasets queryable by external tools) | Ecosystem demand | — | ➕ | ➕ |
| N7 | **Cross-unit parallelism / Stage-2 streaming** | A per-unit-sequencing bottleneck | ✅ | ✅ | ✅ |
| N8 | **Widget/Dashboard template gallery & import/export marketplace** | Community tier growth | ✅ | ✅ | ✅ |

## 5. Replacement map (the "get rid of" view)

| Incumbent | Inspecto answer today | Remaining gap to full displacement |
|---|---|---|
| **Apache NiFi** | Acquisition framework (H1) + visual Pipeline DAGs (H6) + provenance (H7) + backpressure-ish resilience | M4 object storage, M5 JSON/regex, M7 run endpoint + persisted authoring, S3 streaming consumer |
| **Tableau / Superset** | BI Studio (H11): Datasets, Widgets, Measures, Dashboards, KPI gallery, drill-through, export | M2 persistence, M3 live query path, S4 scheduled delivery, S5 calc columns |
| **Jira (ops use)** | Alert → Incident → Case with lifecycle, links, RCA, SLA, correlation (H9) | M8 notifications, S6 queues/escalation |
| **Maintenance & glue scripts** | Dedup/gap/watermark/retry (H1), maintenance Jobs, three-layer audit, lineage | S9 fuller maintenance library, S7 etag dedup |
| **xDR-style investigation** | Events + correlation + Cases + RCA (H9) + lineage/provenance (H7) | S2 Link Analysis (Entity/Link pivoting) — the defining gap |

## 6. Sequencing (aligned with `roadmap/ROADMAP.md`)

1. **M1 security** (revenue gate) → 2. **M2+M3 Studio real-backed** (makes the BI claim true) →
3. **M4 object storage** → 4. **M5 parsing breadth** → 5. **M6 Expectations + M7 authoring closure + M8 notifications** →
then S-tier by demand; N1 stays demand-gated.
