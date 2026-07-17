# Log

## 2026-07-17
* **Mock PUT/POST parity — two silent-upsert gaps closed** (follow-through on the Studio
  create-on-edit fix `4dac986`, whose lesson was "the mock upserting silently is exactly what hides
  create/update bugs offline"): (1) `components.handler` PUT `/components/{kind}/{id}` upserted
  unconditionally, but the real backend's `ComponentRoutes.updateComponent` **404s when the id
  doesn't exist** (create is POST only; verified at `ComponentRoutes.java:125-136` via a
  backend-explorer trace) — the mock now 404s the same way. (2) `pipelines.handler` POST
  `/pipelines/authored` upserted on an existing name, but `PipelineRoutes.createFlow` **409s**
  ("use PUT to update") — mock now 409s; note authored-pipeline PUT genuinely IS create-or-replace
  in the backend (`updateFlow`, "URL id is authoritative"), so pipelines PUT stays an upsert —
  the two families deliberately differ and now both mirror their real routes. New specs: PUT-to-
  missing-component 404s; authored POST-duplicate 409s; authored PUT create-or-replace both ways.
  Only caller of components `update()` is `ComponentsService.update` (edit paths only since
  `4dac986`). Mock-only paths (dev preview runs against the real backend) — verified by the
  handler-level specs, 13/13 green scoped run. One backend If-Match nuance NOT mirrored: the real
  PUT also enforces optimistic-locking (409 on stale version); the mock doesn't model versions —
  acceptable until the UI sends If-Match.
* **Mock audit trail records authoring mutations** (BACKLOG §4 minor — "audit trail seed-only"):
  offline, the Audit-log pane only ever showed the 10 canned seed rows — nothing the operator did
  appended to it. New `emitAudit()` in `mock/signals.ts` (an AUDIT signal in the seed's exact shape —
  attributes actor/action/action_category/target_type/target_id; actor is the mock's `'operator'`
  fallback since a `MockRequest` carries no X-Actor) wired into the `components.handler` mutation
  routes — the one seam every Studio/Registry authoring action flows through: POST create →
  `<kind>.created` (config), PUT → `<kind>.updated` (config), DELETE → `<kind>.deleted`
  (destructive), version restore → `<kind>.restored` (config). Rejected mutations (409 duplicate
  create, 409 referenced delete) audit nothing. Seeds are unchanged — they call the exported
  `putComponent` helper directly, which deliberately doesn't audit (auditing lives in the routes, so
  seeding isn't self-auditing). Deliberate scope: ops-side rule authoring (alert/tag/case rules in
  `ops.handler`) can adopt the same `emitAudit` seam later if wanted — noted in BACKLOG. Reactor UI
  1394/0; mock-only path (dev preview runs against the real backend), verified by the new
  handler-level spec.
* **Events live-tail cadence is operator-selectable** (BACKLOG §4 minor — "cadence hardcoded 5 s"):
  the `LIVE_TAIL_MS = 5000` const became a `LIVE_TAIL_SECONDS` options array (2/5/10/30/60 s) + a
  `liveSeconds` field (default 5); a small "Every" `mat-select` appears next to the Live-tail toggle
  only while it's on (`@if (live)`), and changing it calls `restartLiveTail()` (tears down the old
  `visibleInterval` sub and re-arms at the new cadence). The toggle tooltip is now dynamic. No change
  to the visibility-pause behavior (`visibleInterval` still stops polling while the tab is hidden).
  New fake-timer spec proves the poll fires at the chosen cadence and re-arms when it changes;
  reactor UI 1393/0. Live-verified through the real `/events/search` path (polls recur at the
  selected 2 s while visible, zero while hidden).
* **Mock `POST /alerts/evaluate` computes real ledger math** (BACKLOG §4 minor — "mock always
  breaches"): the manual "Evaluate now" sweep used to fabricate exactly one breach off whichever
  rule happened to be first in the store, regardless of its actual metric/threshold. It now mirrors
  the real `AlertService.evaluate` (per the backend trace: ledger rows → `metricValue` →
  comparator) — `ops.handler.ts` gained `rowsInWindow` (the `Ns|Nm|Nh|Nd` duration / `Nb`
  last-N-batches window grammar) + `ledgerMetric` (`error_rate` / `failed_batches` /
  `rejected_files` / `duration_ms` over the pipeline's committed-batch ledger, now exported from
  `demo.handler.ts` as `batches()`/`PIPELINES` since both handlers need the same rows) + `breaches`
  (`gt`/`gte`/`lt`/`lte`). A rule with no `onPipeline` sweeps every pipeline (each breaching pipeline
  fires its own alert); an unrecognized metric (the 4 domain-specific seeded rules —
  `long_calls_per_msisdn_15m`, `irsf_dest_minutes_pct`, `billing_delta_pct`, `quarantined_files` —
  aren't ledger metrics) evaluates to 0 rather than crashing, so it simply never breaches — honest
  "we don't compute this yet", not a fake pass. The 3 generic seeded rules
  (`operations.seed.ts`) gained `onPipeline` + recalibrated `threshold`/`window` so they still
  genuinely breach against the deterministic mock ledger (unrecalibrated, none of the old
  thresholds — 0.1 error rate, 5 rejected files, 30s duration — are ever reachable by the generator,
  confirming the old "always breaches" was pure fiction). Windows are deliberately offset to a
  half-hour mark (`30m`/`510m`/`750m`, not `1h`/`8h`/`24h`) since the ledger's batches are spaced
  exactly 1h apart — landing a window exactly on that grid makes row-count inclusion a knife's-edge
  race against the few-ms clock drift between seed generation and evaluate-time `Date.now()` (caught
  by the rewritten spec, which failed intermittently on the on-the-hour windows before the offset).
  Rewrote `ops.handler.spec.ts`'s sweep test for the real 3-rule fire + an explicit "no rules armed
  → honestly empty" case; relaxed the notification fan-out test to assert trigger *kinds* (a Set)
  since the sweep can now fire more than one alert. Reactor UI 1392/0. Not live-verified in-browser
  this session — the shared dev preview's `environment.ts` has `mockOps: false` (talks to the real
  backend), so this mock-only path isn't reachable without flipping a build-time flag shared by the
  whole team; verification rests on the rewritten unit tests, which exercise the handler directly.
* **Shared chip primitive `<inspecto-chip>`** (BACKLOG §4 minor — "shared chip primitive
  (sources/widgets/events)"): the per-component hand-rolled `rounded-full … text-xs` tag/token/filter
  pills are now one presentational component (`inspecto/components/chip.component.ts`) — two
  `variant`s (`outline` / `soft`) × two `tone`s (`neutral` / `primary`), optional `removable` ✕ that
  emits `(removed)`, content projected (a leading `<mat-icon>` or mono span just goes inside).
  `removable` uses `transform: booleanAttribute` so the bare attribute compiles under strict template
  checking. Adopted on the three named surfaces: **widgets** (the Type/Tag filter toggles now wrap a
  chip in the real `<button>` so `aria-pressed` + keyboard stay on the button; the per-card tag pills),
  **events** (the correlation-filter chip = `soft`+`primary`+`removable`), **collectors** (the detail
  dialog's include/exclude glob pills). Added to the `/design` gallery (live examples + copy snippet).
  The interactive filter toggles keep their own `<button>` — the chip is the visual, not the control.
  Spec covers class computation, remove emit, and axe. Reactor UI 1391/0 · live-verified (gallery chips
  render every variant/tone; a widgets toggle flips `neutral`→`primary` on click).
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
