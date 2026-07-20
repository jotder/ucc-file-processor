# Features

The ~35 lazy-loaded screens under `src/app/modules/admin/`. Each is added with two edits (the lazy route +
the nav item) — see [routing & navigation](../conventions/routing-and-navigation.md). Grouped here by the
current navigation — **Operations · Platform (Workbench / Studio / Catalog) · Settings · Assistant** —
which the active **Lens** (Business / Builder / Ops) filters. Screens without a linked page are not yet
documented as concepts.

# Operations

* [Dashboard](dashboard.md) - KPIs + charts landing page (the default route).
* [Runs](runs.md) - ingest operations: Run list + statuses (Run ⊇ Batch ⊇ File).
* [Run detail](run-detail.md) - files + audit grids + batch detail for one Run.
* [Events](events.md) - the Signal Ledger (filters, live tail, saved views).
* [Alerts](alerts.md) - fired alerts.
* [Cases & Incidents](objects.md) - operational objects (one component, two routes).
* [Enrichment](enrichment.md) - stage-2 enrichment jobs, lineage, and rollup reports.
* [Jobs](jobs.md) - scheduled jobs + run history (async run-now, 202 + runId).
* [Diagnoses](diagnoses.md) - diagnostic records.
* [Reconciliation](reconciliation.md) - N-way anchor reconciliation boards (DAT-7): breaks, tolerance, drill-through.
* Processing status · Notification center · Audit logs *(not yet documented)*.

# Platform — Workbench

* [Connections](connections.md) - schema-driven connection workbench (Database/FTP/FTPS/Local/SFTP) + SSH tunnel/proxy.
* [Sources](sources.md) - configured collection tasks.
* [Pipelines](pipelines.md) - the authored-Pipeline (DAG) editor + parser config.
* Expectations · Decision rules *(not yet documented)*.

# Platform — Studio

* [Studio](studio.md) - the BI authoring hub: Datasets, Query Library, Viz Library / Widget Builder,
  Dashboard Builder.
* [Geo Map Analysis](geo-map.md) - offline map investigation studio (GeoSource/GeoQuery, saved Geo Views).
* [Link Analysis](link-analysis.md) - graph investigation studio (Entity Projection, saved Link-Analysis Views).
* [KPI & Reports](kpi-reports.md) - the operational KPI & Reports gallery.
* [Investigation Pivot](investigation-pivot.md) - cross-cutting: switch view (table/graph/map) on a
  selection without losing it, shared by Link Analysis and Geo Map Analysis.

# Platform — Catalog

* [Catalog](catalog.md) - the data catalog (Datasets, KPIs, and a graph view).
* [Stream & Reference Onboarding](onboarding.md) - guided, resumable data-origin authoring over the server-held pipeline draft.
* [Components](components.md) - the reusable component registry.

# Business

* Requirements intake · Reconciliation & Breaks *(not yet documented)*.

# Settings

* [Config](config.md) - the TOON configuration view.
* [Model settings](model-settings.md) - AI model / provider settings.
* [Spaces](spaces.md) - multi-space (project) administration + import/export.
* [Icon settings](icon-settings.md) - processor-icon mapping.
* [Design system gallery](design-system-gallery.md) - the in-app `/design` component gallery.
* Map settings · Notification preferences · Settings drawer + consolidated settings pane *(in-flight,
  uncommitted — document once landed)*.

# Assistant

* [Assistant](assist.md) - the AI assist panel/dialog (per-screen 503 when disabled).
