---
okf_version: "0.1"
---

# Inspecto UI — Knowledge Bundle

Curated, agent- and human-friendly documentation for **inspecto-ui**, the Angular operator console
for Inspecto. This bundle follows the [Open Knowledge Format (OKF) v0.1](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md):
each `.md` file is one concept with YAML frontmatter; `index.md` files are progressive-disclosure listings.
Part of the [consolidated bundle](../index.md); the companion backend section lives at [`../backend/`](../backend/index.md).

Start with the [Overview](overview.md) and [Architecture](architecture.md), then drill into conventions,
the shared design system, the feature screens, or the API services.

## Start here

* [Overview](overview.md) - what inspecto-ui is, the tech stack, and how the app boots (against
  `/api/v1`, offline-first).
* [Architecture](architecture.md) - feature-based layout, standalone components, signals, the app shell,
  the shared `inspecto/` libraries.

## Conventions

The binding rules every change must follow (the "definition of done" lives here).

* [Conventions](conventions/) - design tokens, a11y, forms, state, API (`/api/v1` envelope), errors,
  routing, multi-space, mocks, testing/build.

## Shared design system

Reusable components and modules under `src/app/inspecto/` — never re-rolled per feature.

* [Design system](design-system/) - status badge, alert, empty state, skeleton, connectivity banner, chart, grid, data-table family, query, rule.

## Features

The ~35 lazy-loaded screens under `src/app/modules/admin/`, grouped by the Operations / Platform
(Workbench · Studio · Catalog) / Settings / Assistant navigation and filtered by **Lens**.

* [Features](features/) - dashboard, runs, pipelines, sources, connections, the Studio (Datasets,
  Query Library, Viz Library, Dashboard Builder, Geo Map, Link Analysis), catalog, events, alerts,
  incidents, enrichment, jobs, and more.

## Services

* [API services](services/) - the `inspecto/api` resource services, the v1/space/error/auth
  interceptors, and shared async helpers.
