# Features

The 21 lazy-loaded screens under `src/app/modules/admin/`. Each is added with two edits (the lazy route +
the nav item) — see [routing & navigation](../conventions/routing-and-navigation.md). Grouped here by the four
nav groups (Pipelines · Acquisition · Operations · Settings) plus Dashboard and Assistant.

# Dashboard & Assistant

* [Dashboard](dashboard.md) - KPIs + charts landing page (the default route).
* [Assistant](assist.md) - the AI assist panel/dialog (per-screen 503 when disabled).

# Pipelines

* [Pipelines](pipelines.md) - pipeline list + runs.
* [Pipeline detail](pipeline-detail.md) - files + audit grids + batch detail for one pipeline.
* [Flows](flows.md) - the NiFi-style flow (graph) authoring editor + parser config.

# Acquisition

* [Sources](sources.md) - configured data sources.
* [Connections](connections.md) - schema-driven connection workbench (Database/FTP/FTPS/Local/SFTP) + SSH tunnel/proxy.
* [Catalog](catalog.md) - the data catalog (tables, KPIs, and a graph view).

# Operations

* [Events](events.md) - the operational event stream (filters, live tail, saved views).
* [Alerts](alerts.md) - fired alerts.
* [Cases & Issues](objects.md) - operational objects (one component, two routes).
* [Enrichment](enrichment.md) - stage-2 enrichment jobs, lineage, and rollup reports.
* [Jobs](jobs.md) - scheduled jobs + run history.
* [Diagnoses](diagnoses.md) - diagnostic records.

# Settings

* [Config](config.md) - the TOON configuration view.
* [Model settings](model-settings.md) - AI model / provider settings.
* [Components](components.md) - the reusable component registry (grammar/schema/transform/sink/rule).
* [Spaces](spaces.md) - multi-space (project) administration.
* [Icon settings](icon-settings.md) - processor-icon mapping.
* [Design system gallery](design-system-gallery.md) - the in-app `/design` component gallery.
