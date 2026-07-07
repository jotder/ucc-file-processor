# Inspecto — Product Capabilities

> Audience: product owners & business analysts · Status date: **2026-07-07**
> Full requirement-level detail + MoSCoW: [`../REQUIREMENTS.md`](../REQUIREMENTS.md). Forward plan:
> [`../roadmap/ROADMAP.md`](../roadmap/ROADMAP.md).

## Personas — one console, three Lenses

Everyone uses the **same operator console**; a self-selected **Lens** filters it (a Lens is a view,
never a permission — server-enforced **Roles** arrive with the Standard edition and map onto Lenses):

- **Business** — consume Dashboards/KPIs, investigate provenance & lineage, raise **Requirements**
  (KPI / Report / Reconciliation / Rule requests with a triage lifecycle).
- **Builder** — author in the **Workbench** (Connections, Sources, Pipelines) and the **Studio**
  (Datasets, Queries, Widgets, Dashboards, Link Analysis, Geo Map Analysis).
- **Ops** — operate Runs, the Signal ledger, Alerts → Incidents → Cases.

**Vertical starters:** Space Templates for Telecom Revenue Assurance, Fraud Management, Financial
Auditing, and Link Analysis instantiate a ready-made Space per use case.

## Capability map (status honest as of 2026-07-07)

| Area | What the user gets | Status |
|---|---|---|
| **Acquire** | Connections (SFTP/FTP/FTPS/DB) + scheduled Sources with dedup, watermarks, gap detection | ✅ Shipped |
| **Parse & validate** | CSV/delimited grammars, fixed-width (text+binary), plugin formats, compressed input; schema casting + quarantine | ✅ Shipped (JSON + text/regex frontends: planned MUST) |
| **Process** | Visual **Pipeline** DAG authoring; medallion ELT to a Parquet lakehouse; event-driven incremental runs; async run-now | ✅ Shipped (final live e2e verification pending) |
| **Query** | **Query Library** — reusable Queries with `$`-Parameters, executed live on the embedded engine | ✅ Shipped |
| **Visualize** | Studio: Datasets → Widgets → Dashboards (quick filters, drill-through, PNG export); KPI & Reports gallery | ✅ Shipped |
| **Investigate — where** | **Geo Map Analysis**: fully-offline map, heatmaps, routes, time playback, co-location/stay-point intelligence, saved Geo Views | ✅ Shipped (UI; spatial backend later) |
| **Investigate — who** | **Link Analysis**: entity/link graphs over Datasets, communities, pattern matching, saved Views | 🟡 UI complete, backend projection pending |
| **Operate** | Runs, Signal ledger (events), Prometheus metrics, three-layer audit, run reporting | ✅ Shipped |
| **Respond** | Alert Rules → Alerts → **Incidents** → Cases, SLA, comments, AI Diagnosis | ✅ Shipped (notification delivery channels: MUST remainder) |
| **Reconcile** | Dataset-vs-Dataset Reconciliation producing **Breaks** with auto-close lifecycle | ✅ Shipped |
| **Data quality** | **Expectation** engine (rules validating records against Schemas) | 🟡 UI pane exists; engine = MUST remainder |
| **Govern metadata** | Everything is a **Component**; Catalog + reuse/lineage graphs; **Metadata Bundle** export/import with drift detection | ✅ Shipped (bundle backend endpoints pending) |
| **Multi-tenant** | Isolated **Spaces** with CRUD, export/import, templates | ✅ Shipped |
| **Integrate** | Versioned **`/api/v1`** REST contract (OpenAPI-enforced), gateway/IAM-ready | ✅ Shipped |
| **Secure** | Auth-free Personal; Standard: OIDC SSO, HTTPS, RBAC seams, attributed audit | ✅ Module shipped; hardening = MUST remainder |
| **AI assist** | 7 draft-only assistant skills (diagnose, explain, KPI→SQL, NL→schedule, …), fully offline-capable | ✅ Shipped |
| **AI next** | Embedded intelligence: governed autonomy ladder (explain → draft → act-with-approval) | 📋 Designed, awaiting P0 sign-off |

## What's deliberately NOT in scope (this horizon)

No Spring/framework migration · no distributed-by-default clustering · no per-record replay ·
no login/user management inside the product (identity is delegated to the customer's IAM) ·
no auth code in the free core.

## The remaining MUST list (release-gating)

Object-storage connectors (S3/GCS/Azure) · JSON + text/regex parsing · Expectation engine ·
notification delivery · Standard security hardening + packaging verification · one live end-to-end
pipeline verification · pin the agent framework to a released version.
(Detail and sequencing: [`../REQUIREMENTS.md`](../REQUIREMENTS.md) §5–6.)
