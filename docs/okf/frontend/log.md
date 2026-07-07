# Log

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
