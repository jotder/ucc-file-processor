# Log

## 2026-07-16
* **Plan consolidation sweep**: 19 shipped frontend plans/designs distilled here and archived to
  `docs/archived-documents/plans-archive/` (incidents-mail + case-management, lens-access, menu-builder,
  branding, db-browser, tree-table, reconciliation-board, ui-design-review, w7 `/api/v1` migration,
  studio/report-builder/widget-library plans, link-analysis + geo-map plans, frontend-review plan).
  New concept files: [tree-table](./design-system/tree-table.md),
  [reconciliation](./features/reconciliation.md) (⚠ not yet in the section indexes). Updated:
  [objects](./features/objects.md) (mail UI rewrite — retires the banned "Issues"),
  [spaces](./features/spaces.md) (branding), [catalog](./features/catalog.md) (Data Browser),
  [routing-and-navigation](./conventions/routing-and-navigation.md) (Lens Access matrix, custom Menus,
  command palette, detail-over-list + split), [forms-and-state](./conventions/forms-and-state.md)
  (dialog conventions, optimistic bulk), [data-table](./design-system/data-table.md) (`stateKey`,
  Load more), [studio](./features/studio.md) (widget library + shared result layer).

## 2026-07-07
* **Consolidated**: bundle moved `inspecto-ui/docs/okf/` → `docs/okf/frontend/` under the new
  [consolidated bundle](../index.md); features renamed to the canonical vocabulary
  (`pipelines.md`→[`runs.md`](./features/runs.md), `pipeline-detail.md`→[`run-detail.md`](./features/run-detail.md),
  `flows.md`→[`pipelines.md`](./features/pipelines.md)).
* **Refreshed** to the shipped state: new [studio](./features/studio.md),
  [geo-map](./features/geo-map.md), [link-analysis](./features/link-analysis.md),
  [kpi-reports](./features/kpi-reports.md) features; [features index](./features/index.md) regrouped to
  the Operations/Workbench/Studio/Catalog/Settings navigation (~35 screens);
  [overview](./overview.md) + [api-and-data](./conventions/api-and-data.md) +
  [multi-space](./conventions/multi-space.md) + [mock-backends](./conventions/mock-backends.md) updated
  for the `/api/v1` flip, the interceptor chain, and the OIDC no-op-on-Personal story;
  [architecture](./architecture.md) gains the shared `inspecto/` libraries (geo, investigation, graph,
  component-model, viz). Superseded loose docs (`ui-components.md`, `devextreme-migration-plan.md`)
  archived to `docs/archived-documents/`.

## 2026-06-28
* **Created**: Initial OKF v0.1 bundle for inspecto-ui — [Overview](./overview.md), [Architecture](./architecture.md),
  the [Conventions](./conventions), [Design system](./design-system), [Features](./features), and [Services](./services) sets.
  Consolidated from the in-repo `angular-ui` rules, `docs/PROJECT_NOTES.md` §6, the in-app `/design` gallery, and the source tree.
