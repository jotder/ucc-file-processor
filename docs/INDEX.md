# Documentation Index

> The curated map of **current** Inspecto docs. Anything not listed here has been archived under
> [`outdated-doc/`](outdated-doc/) (historical plans, superseded designs, point-in-time snapshots) — kept for
> provenance, not maintained. When you add or retire a doc, update this index in the same change.

---

## Start here (session essentials, ~800 tokens)

- `CLAUDE.md` — project rules (graphify, skills/agents, living docs, branch policy)
- `.claude/COMMON_MISTAKES.md` — ⚠️ read FIRST
- `.claude/QUICK_START.md` — essential commands
- `.claude/ARCHITECTURE_MAP.md` — file locations

## Durable project knowledge

- [`PROJECT_NOTES.md`](PROJECT_NOTES.md) — consolidated cross-cutting knowledge that isn't obvious from code
  or git: key decisions (editions/auth), cross-cutting gotchas (TOON schema, DuckDB keywords, sync-bus
  deadlock, …), engine seams & perf, inspecto-ui conventions, and a pointer map to the authoritative docs.
  Consolidated from per-user agent memory 2026-06-19; keep current as durable facts change.
- [`FEATURE_INVENTORY.md`](FEATURE_INVENTORY.md) — advanced reference: every user-facing feature's TOON
  shape + where it's defined, the existing examples, how the release bundle is assembled, runnability
  constraints, and the worked-example build plan. Point-in-time snapshot (2026-06-20). Pairs with the
  runnable suite in [`../inspecto/examples/`](../inspecto/examples).

## Engineering knowledge bundles (OKF)

Structured, agent- and human-readable [Open Knowledge Format](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md)
bundles — one concept per file, cross-linked, and indexed by graphify. They **summarize and link** the deep
topic docs (each concept cites its authoritative doc); they don't replace them.

- [`okf-backend/`](okf-backend/index.md) — the Java backend: engine, acquisition, control plane, flow-graph,
  components, config, editions, agent, build/run, gotchas.
- [`../inspecto-ui/docs/okf/`](../inspecto-ui/docs/okf/index.md) — the Angular frontend: architecture,
  conventions, design system, the 21 feature screens, API services.

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
- [`operator-console.md`](operator-console.md) — the operator console / UI panes.
- [`parsing-options-reference.md`](parsing-options-reference.md) — parsing options reference
  (+ [`Parsing Options Reference.pdf`](Parsing%20Options%20Reference.pdf)).
- [`plugins.md`](plugins.md) — plugin ingester (segment demux) + execution modes.
- [`performance.md`](performance.md) — benchmarks & tuning. *(Figures re-measured at v3.9.0 — re-verify before
  quoting.)*
- [`api-stability.md`](api-stability.md) — API stability / compatibility policy.

## Architecture & design

- [`architecture.md`](architecture.md) — Stage-1 M..N multiplexer + Stage-2 enrichment overview. *(Review: framed
  pre-flow-engine; for the flow model see flow-graph-design + ADVANCED_GUIDE §5.3.)*
- [`flow-graph-design.md`](flow-graph-design.md) — pipeline-as-graph design (IR, lift, validator, executor,
  component registry, T-checklist). **Active.**
- [`flow-live-execution-plan.md`](flow-live-execution-plan.md) — live execution of authored flows as `JobType.FLOW`
  (T32). **Active.**
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
  templates — sequencing + current-state facts, not started.
- [`superpower/geo-map-analysis-plan.md`](superpower/geo-map-analysis-plan.md) — **Geo Map Analysis studio**:
  revised MoSCoW + phased plan (offline MapLibre basemap, GeoSource seam, shared investigation lib).
  **Phases 0–3a shipped** (mock-first); review sheet
  [`superpower/reviews/geo-map-studio.md`](superpower/reviews/geo-map-studio.md).
- [`superpower/geo-map-case-studies.md`](superpower/geo-map-case-studies.md) — **CS1–CS5 case-study pack**:
  five boundary-pushing seeded investigation scenarios (point-cap stress, impossible travel, weighted
  corridors, fleet dwells, border co-location) with pinned spec invariants. **Shipped.**

## Strategy & roadmap (stakeholder-facing)

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
  [`outdated-doc/consolidated-2026-06-13/`](outdated-doc/consolidated-2026-06-13/README.md). Its live
  equivalents: the per-topic docs above, the stakeholder narrative in
  [`roadmap/STAKEHOLDER_OVERVIEW.md`](roadmap/STAKEHOLDER_OVERVIEW.md), and the OKF bundles (above).
- [`assist-agent-improvement-plan.md`](assist-agent-improvement-plan.md) — assist-agent improvement plan (active).

## Task-specific topics (load as needed)

Add topic files in `docs/learnings/` and list them here.

---

## Archive

[`outdated-doc/`](outdated-doc/) holds superseded/historical material, not maintained:
`v2-*` / `v3-*` planning, `refactor-blueprint-v4`, `design-notes`, `design_analysis`,
`test-coverage`, `ticketing_systems_requirement`, the `superpowers/` specs+plans, and the
`consolidated-2026-06-13/` stakeholder snapshot. Move a doc back up and add it
to this index if it becomes current again.

---

**Last Updated**: 2026-07-01
