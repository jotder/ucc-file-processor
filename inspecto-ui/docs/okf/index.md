---
okf_version: "0.1"
---

# Inspecto UI — Knowledge Bundle

Curated, agent- and human-friendly documentation for **inspecto-ui**, the Angular operator console
for Inspecto. This bundle follows the [Open Knowledge Format (OKF) v0.1](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md):
each `.md` file is one concept with YAML frontmatter; `index.md` files are progressive-disclosure listings.

Start with the [Overview](overview.md) and [Architecture](architecture.md), then drill into conventions,
the shared design system, the feature screens, or the API services.

## Start here

* [Overview](overview.md) - what inspecto-ui is, the tech stack, and how the app boots.
* [Architecture](architecture.md) - feature-based layout, standalone components, signals, the app shell.

## Conventions

The binding rules every change must follow (the "definition of done" lives here).

* [Conventions](conventions/) - design tokens, a11y, forms, state, API, errors, routing, multi-space, mocks, testing/build.

## Shared design system

Reusable components and modules under `src/app/inspecto/` — never re-rolled per feature.

* [Design system](design-system/) - status badge, alert, empty state, skeleton, connectivity banner, chart, grid, data-table family, query, rule.

## Features

The 21 lazy-loaded screens under `src/app/modules/admin/`.

* [Features](features/) - dashboard, pipelines, sources, connections, flows, catalog, events, alerts, cases/issues, enrichment, jobs, and more.

## Services

* [API services](services/) - the `inspecto/api` resource services, interceptors, and shared async helpers.
