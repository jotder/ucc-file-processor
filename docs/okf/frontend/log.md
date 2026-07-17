# Log

## 2026-07-17
* **Create-on-edit fixed across every Studio save path** (BACKLOG §4 "dataset editor save()" row —
  the verify found the whole family): the four Studio services (datasets/widgets/dashboards/queries)
  and the shared `SavedViewStore` (geo/link views) always POSTed `components.create`, which the real
  backend **409s on an existing id** (probed live) — so *every edit-and-save was broken against the
  live backend*: dataset/widget/dashboard/query editors, add-widget-to-existing-dashboard, and
  save-view-under-same-name. Each `save` gained `{update?: boolean}` → PUT; editors pass their
  `editing` state (ids are immutable on edit everywhere, so update never renames). **The mock now
  mirrors the 409** (`components.handler` POST rejects an existing id) — the divergence (mock POST
  upserted) is exactly why this bug family was invisible offline. The save-as-rule dialog gained the
  house inline duplicate-id guard (`uniqueNameValidator`) it was missing, since the honest mock
  surfaces its silent-overwrite as a 409. Suite-wide check: no other flow relied on POST-upsert.
* **Requirements "Delivered via" is now a real Component link** (BACKLOG §4, the C1 follow-up the
  requirements-intake review flagged): the deliver dialog's note field became a **cross-kind
  autocomplete** (`componentRefOptionLoader` in `entity-option-loaders` — datasets/queries/widgets/
  dashboards/rules/reconciliations + pipelines/jobs/decision-rules, in the house `<kind>/<id>` form;
  suggestions assist, free text stays valid). A delivered note that is a **pure ref list**
  (`<kind>/<id>` tokens split on space/`+`/`,` — prose never becomes an edge) now derives Registry
  **`delivered-by` edges**: new `requirementRefs` structural derivation + `RefRel += 'delivered-by'`;
  requirements join the Catalog ▸ Usage reuse graph as nodes (`loadRequirements`, `REQUIREMENT_KIND`
  registered). No backend change — the note stays the transport; the existing mock seeds'
  ref-list notes (`reconciliation/x + dashboard/y`) now light up as edges. The dialog also moved
  `ngModel` → reactive `FormControl`. Live-verified: submit → accept → deliver with picker →
  `requirement/churn_kpi_req → dashboard/default_overview` renders in the Usage graph.
* **Config pane ported to `<inspecto-schema-form>`** (BACKLOG §4 — the last spec-driven `ngModel`
  authoring form): the dynamic `FieldSpec` grid is now mapped to `AttributeSpec[]` and rendered by the
  shared schema-form (three-tier disclosure: required visible, the rest under "Optional settings").
  Dotted `FieldSpec.path`s become flat `fN` control keys (schema-form's `form.get(key)` splits on `.`),
  reassembled into the nested config by two pure, unit-tested helpers (`toAttrSpecs` / `assembleConfig`
  — LIST fields keep comma-separated-text editing, schema-form has no chips type). Enter submits via
  `(submitted)`; the live "Assembled config" preview now tracks the schema-form's `form.valueChanges`.
  The type/safety/path controls moved to reactive `FormControl`s, so `FormsModule` is gone from the
  pane. Two root-cause fixes rode along, found by walking the pane against the **live backend**:
  * **`FieldSpec` contract drift** — the UI model + mock served an invented shape
    (`INTEGER/BOOLEAN/ARRAY`, `options`, `default`), so against the real
    `com.gamma.config.spec.FieldSpec` (`INT/LONG/BOOL/ENUM/LIST/…`, `enumValues`, `defaultValue`,
    `label`, `pattern`) every field silently degraded to a bare text input — also true of the old
    pane. `models.ts` + the mock's `CONFIG_SPECS` now mirror the backend record; live-verified:
    ENUMs render as selects, INT/LONG as numbers, BOOL as toggles, LIST as comma text, and
    POST /validate returns real `ConfigLoader` findings (severity badges).
  * **Schema-form number fix (all ~12 dialogs)** — the number case used a `[type]` *binding*, which
    Angular's `NumberValueAccessor` selector (static `type="number"`) never matches, so every number
    field emitted **strings**. Split into a static-`type` case; number attributes now emit numbers
    (regression spec added).
* **BACKLOG §4 quick wins shipped**: (1) the **space switcher** now reloads at the current lens's
  home route (`LENS_HOME[currentLens]`) instead of the hard-coded `/overview` — a Business user
  switching space lands on KPI & Reports, not a page their lens never uses; (2) **KPI & Reports**
  (the Business-lens home) now renders a distinct **error state** ("Couldn't load dashboards" +
  Retry) when the dashboards fetch fails, instead of masquerading as the "No dashboards yet" empty
  state (new `loadError` signal + spec). No design-system change.
* **ui-design-review residuals shipped** (R2/R3 leftovers): per-target **column suggestions**
  (`columnOptionLoader` — expectation `column`/`refColumn`; decision-rule when-clause columns now
  probed per target, hardcoded CDR shape deleted), object-create **tag chips** (registry-suggested) +
  assignee suggestions + dirty-guard, the **command registry**
  (`inspecto/commands/` — New incident / New case / New job via the `?create=1` handshake), and the
  data-table **keyboard layer** (`/` quick-filter focus; opt-in `[keyNav]` j/k/Enter/x, piloted on
  the mail list). Updated: [forms-and-state](./conventions/forms-and-state.md),
  [routing-and-navigation](./conventions/routing-and-navigation.md),
  [data-table](./design-system/data-table.md). Still deliberately deferred: R6 true offset paging,
  R8 pivot-bar (see BACKLOG §4).

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
