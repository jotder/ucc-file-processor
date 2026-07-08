# Documentation Index

> The curated map of **current** Inspecto docs. Anything not listed here has been archived under
> [`archived-documents/`](archived-documents/) (historical plans, superseded designs, point-in-time snapshots) — kept for
> provenance, not maintained. When you add or retire a doc, update this index in the same change.

---

## Start here (session essentials, ~800 tokens)

- `CLAUDE.md` — project rules (graphify, skills/agents, living docs, branch policy)
- `.claude/COMMON_MISTAKES.md` — ⚠️ read FIRST
- `.claude/QUICK_START.md` — essential commands
- `.claude/ARCHITECTURE_MAP.md` — file locations

## Durable project knowledge

- [`USER_GUIDE.md`](USER_GUIDE.md) — **end-user guide** to the web app: getting around (navigation, menu
  search, Spaces/Lens), every screen (Business, Operations, Platform → Workbench/Studio/Catalog, Settings,
  Assistant), and the shared UI elements. Written in canonical `GLOSSARY.md` vocabulary.
  ⚠️ Audit 2026-07-07: [`superpower/reviews/user-guide-audit.md`](superpower/reviews/user-guide-audit.md) —
  guide vs glossary vs shipped UI: 4 factual errors, 3 orphan Business panes (KPI & Reports / Requirements /
  Reconciliation, also missing from nav), conflicting **Report** definitions, Alert-Rule authoring hole;
  prioritized P0–P2 fix list. **P0 + most P1/P2 landed 2026-07-07** (correctness fixes, Business nav
  group + guide §2, Report=scheduled-delivery, Alert-Rule authoring pane, ELT lifecycle + persona intro,
  `/overview` rename, `tools/check-vocabulary.mjs` CI guard); still open: KPI-authoring/Measure-reuse
  docs, quarantine remediation, C8/C9/C11 de-jargon, Matrix tense.
- [`PROJECT_NOTES.md`](PROJECT_NOTES.md) — consolidated cross-cutting knowledge that isn't obvious from code
  or git: key decisions (editions/auth), cross-cutting gotchas (TOON schema, DuckDB keywords, sync-bus
  deadlock, …), engine seams & perf, inspecto-ui conventions, and a pointer map to the authoritative docs.
  Consolidated from per-user agent memory 2026-06-19; keep current as durable facts change.
- [`FEATURE_INVENTORY.md`](FEATURE_INVENTORY.md) — advanced reference: every user-facing feature's TOON
  shape + where it's defined, the existing examples, how the release bundle is assembled, runnability
  constraints, and the worked-example build plan. Point-in-time snapshot (2026-06-20, with a 2026-07-07
  addendum listing what shipped since). Pairs with the runnable suite in
  [`../inspecto/examples/`](../inspecto/examples).
- [`REQUIREMENTS.md`](REQUIREMENTS.md) — **current requirements-of-record**: the full platform
  requirement set (UI + backend + agentic) with a reconciled **MoSCoW analysis**, edition mapping,
  NFRs, sequencing, and risks (compiled 2026-07-07; reconciles the 2026-07-02 feature matrix with the
  shipped W1–W7 + R1–R6 work).
- [`BACKLOG.md`](BACKLOG.md) — **consolidated backlog index**: every pending/deferred/open item from
  REQUIREMENTS §5, all `superpower/` plans, flow checklists, review sheets, and live session notes,
  deduplicated, with pointers to the authoritative source doc for each (compiled 2026-07-08).

## Engineering knowledge bundle (OKF, consolidated)

The **one** structured, agent- and human-readable [Open Knowledge Format](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md)
bundle — one concept per file, cross-linked, indexed by graphify. Concepts **summarize and link** the deep
topic docs (each cites its authoritative doc); they don't replace them. *(Consolidated 2026-07-07 from
`docs/okf-backend/` + `inspecto-ui/docs/okf/`.)*

- [`okf/`](okf/index.md) — the master index, with three sections:
  - [`okf/frontend/`](okf/frontend/index.md) — the Angular console: architecture, conventions (incl. the
    `/api/v1` flip), design system, the ~35 feature screens (Studio, Geo Map, Link Analysis, …), services.
  - [`okf/backend/`](okf/backend/index.md) — the Java backend: engine, acquisition, control plane +
    `/api/v1` contract, pipeline-graph, components, config, editions & security, agent, build/run, gotchas.
  - [`okf/agentic/`](okf/agentic/index.md) — **eoiagent** (the embeddable agent framework, separate repo)
    distilled: overview, architecture, governance, ADR log, and the Inspecto integration seam.

## Production investigation (the hub)

- [`ADVANCED_GUIDE.md`](ADVANCED_GUIDE.md) — **Advanced Operations & Internals Guide.** Per-component process,
  events, metrics, attributes, persisted state, `-D` flags, the full Control API, and troubleshooting playbooks.
  The go-to for diagnosing production behaviour. **Living doc — keep current as code changes (see its §0).**

## Operations & reference

- [`configuration.md`](configuration.md) — all TOON config keys (pipelines, sources, schemas, jobs, alerts).
- [`operations.md`](operations.md) — running the service, batch processing, operational procedures. *(Some overlap
  with ADVANCED_GUIDE §3–5; ADVANCED_GUIDE is authoritative for internals.)*
- [`troubleshooting.md`](troubleshooting.md) — focused fixes (DuckDB/pg_duckdb view quirks, partition extraction).
- [`integrations.md`](integrations.md) — warehouse query layer (pg_duckdb), remote connectors, external systems.
- [`parsing-options-reference.md`](parsing-options-reference.md) — parsing options reference
  (+ [`Parsing Options Reference.pdf`](Parsing%20Options%20Reference.pdf)).
- [`plugins.md`](plugins.md) — plugin ingester (segment demux) + execution modes.
- [`performance.md`](performance.md) — benchmarks & tuning. *(Figures re-measured at v3.9.0 — re-verify before
  quoting.)*
- [`api-stability.md`](api-stability.md) — API stability / compatibility policy (Java embedding API).
- [`api/`](api/README.md) — **machine-readable v1 HTTP contract**: `openapi-v1.json` (envelope, error-code
  catalog, Signal, exemplar paths) + canonical `examples/`, enforced by `ApiContractTest` against
  `ErrorCodes.java` and the live server. Authoring workflow in its README; design in
  [`superpower/api-contract-design.md`](superpower/api-contract-design.md).

## Architecture & design

- [`architecture.md`](architecture.md) — Stage-1 M..N multiplexer + Stage-2 enrichment overview, with the
  current platform scope map + multi-space directory layout *(reframed 2026-07-07)*.
- [`flow-graph-design.md`](flow-graph-design.md) — pipeline-as-graph design (IR, lift, validator, executor,
  component registry, T-checklist). **Active.**
- [`flow-live-execution-plan.md`](flow-live-execution-plan.md) — live execution of authored Pipelines as
  `JobType.PIPELINE` (T32). **Active.**
- [`job-framework-design.md`](job-framework-design.md) — **Job Framework**: pluggable Job Types (SPI registry
  evolving the shipped `com.gamma.job` engine), parameterized Triggers, Signals (§8 envelope, one ledger),
  Run Artifacts + queryable result metadata, and hot-deployable **Job Packs** — with worked `sql.template` +
  fraud-pack examples. Subsumes `superpower/backend-backlog.md` §3 (Phase D job templates). **Active (design,
  2026-07-08 — no implementation yet).**
- [`data_acquisition_framework.md`](data_acquisition_framework.md) — acquisition requirement + as-built pointer
  (Phases A–F shipped on `4.x`).
- [`delimited-grammar-design.md`](delimited-grammar-design.md) — delimited-grammar parsing design.
- [`rule-builder-design.md`](rule-builder-design.md) — visual rule/query builder (the **Query Core**:
  projection + nested filter + SQL + live preview; aggregation = its Phase 2). **Active (design).**
- [`superpower/component-model.md`](superpower/component-model.md) — **the unified metamodel**: every artifact
  is a `Component` (kind + config + parts + wiring); pipelines/jobs/dashboards are composites; the relationship
  graph is derived. The frame Studio + the component registry are instances of. Build sequence in
  [`superpower/component-model-adoption-plan.md`](superpower/component-model-adoption-plan.md). **Active (design).**
- [`superpower/report-builder-design.md`](superpower/report-builder-design.md) — **Studio**: KPI / report /
  dashboard builder over the Query Core (datasets · `VizPlugin` registry · charts · dashboards;
  offline-author/DuckDB-later). The visualization head on the rule-builder. Paired with its execution plan in
  [`superpower/studio-implementation-plan.md`](superpower/studio-implementation-plan.md). **Active (design).**
- [`superpower/widget-library-spec.md`](superpower/widget-library-spec.md) — **Widget Library**: refines the
  Studio design with widget identity/tags, a browsable library gallery, the mandatory-vs-advanced (cog) config
  split, a shared Dataset result layer, a standalone `WidgetHost`, plus a boundary analysis. **Milestone 1 —
  Full UI, mock backend** (**done**) / **Milestone 2 — Backend** (backlog, see
  [`superpower/backend-backlog.md`](superpower/backend-backlog.md)). Glossary-bound Type→Instance
  (Visualization Type → Widget).
- [`superpower/ia-vocabulary-reorg.md`](superpower/ia-vocabulary-reorg.md) — **Platform IA reorg**: Workbench/
  Studio/Catalog nav grouping, Stream + Matrix glossary additions, Catalog's Streams/Usage tabs, the
  Processing Status Operations page. **Phases A/B/E done** (UI, mock-first); Phases C (Matrices) / D (Job
  templates) are backend-gated, see [`superpower/backend-backlog.md`](superpower/backend-backlog.md).
- [`superpower/backend-backlog.md`](superpower/backend-backlog.md) — **consolidated backend backlog**: the
  one closed `ComponentStore.WRITABLE_TYPES` enum blocking Widget-Library M2, Matrices, and (partly) Job
  templates, plus the Alert-Rule write endpoints (§4) — sequencing + current-state facts, not started.
- [`superpower/alert-rule-authoring-plan.md`](superpower/alert-rule-authoring-plan.md) — **Alert-Rule
  authoring pane** (audit C3): schema-form dialog + rules CRUD on the Alerts pane, `canAuthorAlertRules`
  capability, mock-first contract mirroring `/decision-rules`. **UI shipped 2026-07-07**; backend writes
  are backlog §4.
- [`superpower/geo-map-analysis-plan.md`](superpower/geo-map-analysis-plan.md) — **Geo Map Analysis studio**:
  revised MoSCoW + phased plan (offline MapLibre basemap, GeoSource seam, shared investigation lib).
  **Phases 0–3a shipped** (mock-first); review sheet
  [`superpower/reviews/geo-map-studio.md`](superpower/reviews/geo-map-studio.md).
- [`superpower/geo-map-case-studies.md`](superpower/geo-map-case-studies.md) — **CS1–CS5 case-study pack**:
  five boundary-pushing seeded investigation scenarios (point-cap stress, impossible travel, weighted
  corridors, fleet dwells, border co-location) with pinned spec invariants. **Shipped.**
- [`superpower/pipeline-case-studies.md`](superpower/pipeline-case-studies.md) — **Pipelines CS1–CS5
  case-study pack**: five boundary-pushing seeded authored pipelines (canvas scale, clone-mode streaming,
  disconnected legs, full parser-format gauntlet, dead-letter torture) + data sources and reusable
  grammars, with pinned spec invariants. **Shipped.**
- [`superpower/metadata-bundle.md`](superpower/metadata-bundle.md) — **Metadata Bundle export/import**
  (Settings → Import & Export): cross-instance, metadata-only transfer of datasets/widgets/dashboards/
  saved views/pipelines + registry pieces, with dependency closure on export and per-item
  overwrite/skip on import. **Shipped** (mock-first; backend endpoints pending the ComponentStore
  enum widening).
- [`superpower/metadata-network-design.md`](superpower/metadata-network-design.md) — **Metadata network
  deep-dive**: configuration-graph audit (4 duplicated ref-derivation sites, the delete-protection
  gaps), the `deriveRefs` consolidation (**R1 shipped 2026-07-06**), per-surface import/export
  placement, and the **bundle v2 envelope** (explicit lineage `refs` + `provenance`/hashes) — with
  JSON Schema + samples under `superpower/schemas/`.
- [`superpower/living-operational-system.md`](superpower/living-operational-system.md) — **ARCHITECTURE
  NORTH-STAR (2026-07-06)**: the platform as a living operational organism — seven networks
  (Data / Signal / Decision / Execution / Metadata / Presentation / Security) over one Component
  metamodel; Everything-is-Metadata coverage map; Queries/Parameters/Result-Sets;
  rework roadmap **R1 (shipped) → R6**; proposed vocabulary pending GLOSSARY adoption.
- [`superpower/api-contract-design.md`](superpower/api-contract-design.md) — **API Contracts v1 (design,
  2026-07-06)**: the Control API redesigned as stable, versioned business contracts — `/api/v1` + bounded
  contexts, response envelope + error-code catalog, bootstrap/metadata-first, capability-based per-resource
  permissions, query/Result-Set wire contract, ETag = `contentHash`, 202+Run async, and the WSO2/Keycloak
  gateway split for Standard. Point-by-point map of the product owner's 33 API guidelines + the
  W1–W7 Standard-edition implementation worklog. **Design only — no code shipped.** HTTP counterpart of
  [`api-stability.md`](api-stability.md).

## Strategy & roadmap (stakeholder-facing)

- [`stakeholders/`](stakeholders/README.md) — **the audience-targeted document set** (2026-07-07):
  executive brief, product capabilities, technical architecture, operations guide — with a per-audience
  reading map. Start here when handing material to a stakeholder.
- [`roadmap/STAKEHOLDER_OVERVIEW.md`](roadmap/STAKEHOLDER_OVERVIEW.md) — high-level platform overview & strategy for a
  mixed exec/technical audience (vision, capability inventory, editions, maturity, roadmap). **Active.**
- [`roadmap/ROADMAP.md`](roadmap/ROADMAP.md) — forward plan (Now/Next/Later horizons, themes, sequencing, success
  measures). **Active.**
- [`roadmap/PRESENTATION.md`](roadmap/PRESENTATION.md) — ~18-slide presentation content (mixed exec+technical) distilling the overview.
- [`roadmap/PRESENTATION_EXEC.md`](roadmap/PRESENTATION_EXEC.md) — ~12-slide **executive** briefing (business framing, light on internals, decision-focused).

## Editions & release

- [`EDITIONS.md`](EDITIONS.md) — edition model (Personal/Standard/Enterprise = build flavors, never branches).
- [`BRANCHING.md`](BRANCHING.md) — branch & release policy (versions = branches; merge-forward; SemVer + CC).

## UI

- [`ui/accessibility-audit.md`](ui/accessibility-audit.md) — inspecto-ui WCAG/a11y audit + remediation.

## Plans

- The **consolidated stakeholder snapshot** is **archived** (frozen 2026-06-13) under
  [`archived-documents/consolidated-2026-06-13/`](archived-documents/consolidated-2026-06-13/README.md). Its live
  equivalents: the per-topic docs above, the stakeholder set in [`stakeholders/`](stakeholders/README.md),
  and the consolidated OKF bundle (above).

## Task-specific topics (load as needed)

Add topic files in `docs/learnings/` and list them here.

---

## Archive

[`archived-documents/`](archived-documents/) holds **all** superseded/historical material, not maintained
*(renamed from `outdated-doc/` 2026-07-07; `superpower/plans-archive/` now lives inside it too)*:
`v2-*` / `v3-*` planning, `refactor-blueprint-v4`, `design-notes`, `design_analysis`,
`test-coverage`, `ticketing_systems_requirement`, the `superpowers/` specs+plans, the
`consolidated-2026-06-13/` stakeholder snapshot, and — archived in the 2026-07-07 reconciliation —
`operator-console.md` (superseded by `USER_GUIDE.md`), `flow-authoring-design.md` (superseded by the
shipped Pipeline editor + component model), `assist-agent-improvement-plan.md` (completed 2026-06-12;
kernel references pre-date eoiagent), `ui-components.md` + `devextreme-migration-plan.md` (superseded
by the OKF frontend section; the migration plan's Option C shipped as the vendored shell). Move a doc
back up and add it to this index if it becomes current again.

---

**Last Updated**: 2026-07-07
