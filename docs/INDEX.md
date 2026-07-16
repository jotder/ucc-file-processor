# Documentation Index

> The curated map of **current** Inspecto docs. Anything not listed here has been archived under
> [`archived-documents/`](archived-documents/) (historical plans, superseded designs, point-in-time snapshots) —
> kept for provenance, not maintained. When you add or retire a doc, update this index in the same change.
>
> **Structure (binding — see root `CLAUDE.md` "Documentation lifecycle"):** current knowledge lives in the
> **[OKF bundle](okf/index.md)** + the small root canon below · in-flight plans live in
> [`superpower/`](superpower/) · everything else is archive. *(Consolidated 2026-07-16: ~80 shipped
> plans/reviews distilled into OKF and archived; the former root reference docs relocated into OKF.)*

---

## Start here (session essentials, ~800 tokens)

- `CLAUDE.md` — project rules (vocabulary, doc lifecycle, skills/agents, branch policy)
- `.claude/COMMON_MISTAKES.md` — ⚠️ read FIRST
- `.claude/QUICK_START.md` — essential commands
- `.claude/ARCHITECTURE_MAP.md` — file locations

## Root canon (durable, audience-facing)

- [`USER_GUIDE.md`](USER_GUIDE.md) — **end-user guide** to the web app: navigation, Spaces/Lens, every
  screen, shared UI elements. Canonical `GLOSSARY.md` vocabulary.
- [`GLOSSARY.md`](GLOSSARY.md) — ⚠️ **canonical vocabulary, BINDING** — single source of truth for every
  concept's name, the banned synonyms, and the UI→model→backend rename map.
- [`PROJECT_NOTES.md`](PROJECT_NOTES.md) — consolidated cross-cutting knowledge that isn't obvious from
  code or git: key decisions, gotchas, engine seams & perf, pointer map.
- [`REQUIREMENTS.md`](REQUIREMENTS.md) — **requirements-of-record**: full platform requirement set with
  reconciled MoSCoW, edition mapping, NFRs, sequencing.
- [`BACKLOG.md`](BACKLOG.md) — **the consolidated open-items index** (one line + pointer each). Refreshed
  2026-07-16 from all archived plans' deferrals.
- [`FEATURE_INVENTORY.md`](FEATURE_INVENTORY.md) — every feature's TOON shape + where defined, examples,
  packaging, runnability. Pairs with the runnable suite in [`../inspecto/examples/`](../inspecto/examples).
- [`ADVANCED_GUIDE.md`](ADVANCED_GUIDE.md) — **Advanced Operations & Internals Guide** (the production
  investigation hub): per-component process, events, metrics, persisted state, `-D` flags, full Control
  API, troubleshooting playbooks. **Living doc.**
- [`EDITIONS.md`](EDITIONS.md) — edition model (Personal/Standard/Enterprise = build flavors, never branches).
- [`BRANCHING.md`](BRANCHING.md) — branch & release policy (versions = branches; merge-forward; SemVer + CC).

## The knowledge bundle — OKF (current as-built truth)

The **one** structured, agent- and human-readable [OKF](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md)
bundle — one concept per file, cross-linked, indexed by graphify. As of 2026-07-16 it also **contains the
former root reference docs** (each index lists them):

- [`okf/`](okf/index.md) — master index, three sections:
  - [`okf/frontend/`](okf/frontend/index.md) — the Angular console: architecture, conventions, design
    system (incl. data-table + tree-table), the feature screens, services.
  - [`okf/backend/`](okf/backend/index.md) — the Java backend: engine (incl. Stage-1 architecture,
    DB/persistence layer, plugins), acquisition (incl. the full framework doc), control plane (`/api/v1`,
    API-stability policy, queries, jobs + Job Framework, metadata bundle, multi-space), pipeline-graph
    (incl. the full design doc), components, config (incl. the configuration + parsing-options
    references), editions & security, agent, build-run (incl. operations reference, troubleshooting,
    performance), gotchas, integrations, architecture layers.
  - [`okf/agentic/`](okf/agentic/index.md) — **eoiagent** (the embeddable agent framework, separate repo)
    distilled + the Inspecto integration seam.

## Contracts, runbooks, audits

- [`api/`](api/README.md) — **machine-readable v1 HTTP contract**: `openapi-v1.json` + canonical
  `examples/`, enforced by `ApiContractTest`; `schemas/` (metadata-bundle JSON Schema + samples).
- [`ops/`](ops/) — operational runbooks: backup/restore, UAT seeding, maintenance.
- [`ui/accessibility-audit.md`](ui/accessibility-audit.md) — the **living** inspecto-ui WCAG/a11y
  findings register (referenced by `okf/frontend/conventions/accessibility.md`).

## Stakeholder set (audience-targeted)

- [`stakeholders/`](stakeholders/README.md) — per-audience reading map: executive brief, product
  capabilities, technical architecture, operations guide, **testing guide** (added 2026-07-16).
- [`roadmap/`](roadmap/) — stakeholder overview, roadmap (Now/Next/Later), presentation decks.

## In-flight plans (`superpower/` — plans live here ONLY while active)

- [`superpower/embedded-intelligence-plan.md`](superpower/embedded-intelligence-plan.md) — AGT-5: P0
  spine shipped + hardened; **P1–P5 open** (§8 = live phasing).
- [`superpower/storage-layout-and-sharing-plan.md`](superpower/storage-layout-and-sharing-plan.md) —
  storage-layout contract (shipped L0→S3) + **open UI Exchange-surfaces track** (§3.6).
- [`superpower/modularization-optimization-plan.md`](superpower/modularization-optimization-plan.md) —
  PROPOSED, analysis unexecuted; headline Musts indexed in `BACKLOG.md`.
- [`superpower/a2ui-agent-rendered-ui-spike.md`](superpower/a2ui-agent-rendered-ui-spike.md) — A2UI
  design spike (agent-emitted declarative UI over the component-model host) — proposed, not started.
- [`superpower/living-operational-system.md`](superpower/living-operational-system.md) — standing
  **architecture north-star** (seven networks over one Component metamodel); R1–R6 all shipped.
- [`superpower/geo-map-case-studies.md`](superpower/geo-map-case-studies.md) — Geo Map CS1–CS5
  case-study pack (spec-pinned demo seeds) — reference.
- [`superpower/pipeline-case-studies.md`](superpower/pipeline-case-studies.md) — Pipelines CS1–CS5
  case-study pack (spec-pinned demo seeds) — reference.

---

## Archive

[`archived-documents/`](archived-documents/) holds **all** superseded/historical material, not maintained:
[`plans-archive/`](archived-documents/plans-archive/) (every shipped/superseded plan & design — incl. the
2026-07-16 consolidation sweep: ui-design-review, incidents-mail + case-management, job-framework-design,
api-contract-design, component-model, the vocabulary renames, and ~60 more),
[`superpower-reviews/`](archived-documents/superpower-reviews/) (all 37 screen review sheets, incl.
`user-guide-audit.md`), the `consolidated-2026-06-13/` stakeholder snapshot, and the pre-4.x planning sets.
Move a doc back up and re-list it here if it becomes current again.

---

**Last Updated**: 2026-07-16
