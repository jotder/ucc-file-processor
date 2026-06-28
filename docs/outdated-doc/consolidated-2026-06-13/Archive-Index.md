---
metadata:
  document_id: ARCHIVE-INDEX
  title: Archive Index
  last_updated_date: 2026-06-13
  sources_used: [all 44 source markdown files]
  open_questions:
    - Whether the v3-* planning trio should be physically moved to a docs/archive/ folder or kept in place but linked from the README as historical (they are still referenced from the README docs index).
  assumptions_made:
    - This index is advisory — no files were moved or deleted. "Authoritative — keep live" docs remain the deep-detail source of truth that the consolidated set summarizes.
    - Per standing guardrails, nothing was staged/committed/deleted by this process.
---

# Archive Index

Classification of the 44 source markdown files. Three buckets:

- **[ARCHIVE]** — historically relevant (planning, completed-work records, immutable ADRs).
  Superseded by the consolidated set for stakeholder review; retain for provenance.
- **[DELETE]** — pure noise, empty, or flat-out wrong duplicates.
- **[KEEP-LIVE]** — current canonical reference docs that *feed* the consolidated set and remain the
  deep-detail source of truth (linked from the README documentation index). Not obsolete.

## [ARCHIVE] — historical, superseded by the consolidated set

| File | Type | Why archive | Stale facts to not cite |
|---|---|---|---|
| `docs/v2-roadmap.md` | 2.x roadmap (self-labeled archived) | 2.x line fully delivered | "active dev on 3.x" (now 4.x) |
| `docs/v2-plan.md` | 2.x plan / ADR table | shipped; richest 2.x decision feed (→ 08) | `ucc_*` tables/metrics; 3.x pointer |
| `docs/v2-backlog.md` | 2.x backlog / strategy | shipped; v3.0+ "deferred" items now built | Postgres-vs-DuckDB & Javalin-vs-Spring pre-decision text inline; 3.x pointer |
| `docs/design_analysis.md` | 3.x deep-dive review | one-time review folded into 03/08 | "Java 24"; "UCC File Processor"; `ucc-inspecto` file:// paths; old milestone mermaid |
| `docs/superpowers/specs/2026-05-27-batch-processing-design.md` | design spec (shipped feature) | batch-processing internals + locked decisions (→ 08 P-14) | product `file-processor`; path `C:\sandbox\URA\sandbox`; DuckDB 1.5.2.1; Gson 2.11.0 |
| `docs/superpowers/plans/2026-05-27-batch-processing.md` | impl plan (2480 ln) | canonical batch-internals record | **Java 24** (×2); single-module; repo root `C:\sandbox\URA\sandbox` |
| `docs/superpowers/plans/2026-05-28-plugin-ingester.md` | impl plan | early `FileIngester` design (later reworked as `StreamingFileIngester`, v3.11.0) | `FileIngester` SPI superseded; `com.gamma.etl` pre-consolidation |
| `docs/v3-architecture.md` | 3.x assessment + redesign | foundational redesign; folded into 03/05/08 | "Java 24" (A1); "single Maven module"; M0/M1 framing pre-keystone |
| `docs/v3-agent-mvp.md` | assist-agent MVP wish list | agent design rationale; folded into 02/03/08 | MVP "5 skills + optional 6th" framing (all 7 shipped); pre-kernel `String` confidence |
| `docs/v3-plan.md` | 3.x sequenced milestones | milestone record; folded into 04/05/08 | branch `3.x` framing (now 4.x); "draft-only" caveats partly superseded by v4.1 write endpoints |
| `docs/refactor-blueprint-v4.md` | v4 refactor blueprint | all phases complete; record of generics/consolidation | **internal contradiction** "Phases 2–3 not started" (C9) |
| `docs/assist-agent-improvement-plan.md` | session plan (4.x, 2026-06-12) | all workstreams done; record of hosted routing + B5 alerts | `assist.toon` (shipped as `assist-settings.properties`, C15) |
| `inspecto-agent/docs/AGENT_ARCHITECTURE.md` | kernel architecture charter | canonical kernel↔app seam map (→ 03/08) | "UCC"; `3.12.0-SNAPSHOT`/Java 24 §4; "Draft for review" status |
| `inspecto-agent/docs/AGENT_KERNEL_K0_K1_PLAN.md` | kernel K0/K1 build plan | completed-phase record | "no repo created yet"; `0.1.0-SNAPSHOT` |
| `inspecto-agent/docs/AGENT_KERNEL_U0_U1_PLAN.md` | UCC→kernel migration plan | the v4.0 migration record (→ 04/08) | "UCC"/`file-processor-agent`; consumes `0.1.0-SNAPSHOT` (now 1.0.0) |
| `inspecto-agent/docs/AGENT_KERNEL_R1_PLAN.md` | kernel R1 reuse plan | partially executed; trigger-gated | "`0.x`/1.0 not yet frozen" — stale vs kernel 1.x (C5) |

## [ARCHIVE] / immutable — agent-kernel ADRs (keep by convention)

`inspecto-agent/docs/adr/README.md` + `adr-0001` … `adr-0009`. By ADR convention these are
**immutable once Accepted** and retained permanently; their decisions are reproduced in
[08-Decisions](08-Decisions.md) Part A. No staleness of substance (only "UCC" naming context); keep
as the canonical decision record.

## [KEEP-LIVE] — current canonical reference (feed the set; not obsolete)

These remain the deep-detail source of truth and stay linked from the README documentation index;
the consolidated docs summarize and cross-link them rather than replace them:

`inspecto/README.md`, `inspecto-ui/README.md`, `inspecto-ui/docs/ui-components.md`,
`docs/architecture.md`, `docs/configuration.md`, `docs/operations.md`, `docs/plugins.md`,
`docs/operator-console.md`, `docs/integrations.md`, `docs/troubleshooting.md`,
`docs/api-stability.md`, `docs/performance.md`, `docs/test-coverage.md`, `docs/design-notes.md`,
`docs/parsing-options-reference.md`, `docs/delimited-grammar-design.md`.

> Caveat: `parsing-options-reference.md` and `configuration.md` need a refresh to reflect the 4.1
> delimited-grammar feature (Conflict C10); `operator-console.md` has a `:4200`/`:4204`
> inconsistency (C8). They stay live but should be corrected.

## [DELETE] — noise / transient / leftover

| File | Classification | Note |
|---|---|---|
| `SESSION_STATUS.local.md` | **Transient / local working file** | Intentionally local & uncommitted; **stale** (points to `a15c4d4`, before the rebrand commits; uses `inspector-ui`/Java-26-dev framing). It is a live handoff scratchpad, **not** part of the authoritative doc set. Recommendation: **exclude from consolidation and keep refreshing locally** — do not archive, do not delete (it's the user's working file). The durable equivalent is the memory index. |
| `docs/Parsing Options Reference.pdf` *(untracked; referenced by `parsing-options-reference.md`)* | **[DELETE] candidate (leftover)** | An untracked generic DuckDB cheat-sheet PDF whose corrections are already folded into `parsing-options-reference.md`. Per standing guardrail it is **left in place** ("untracked leftover — leave it"); flagged only for awareness. |

**No true duplicate or empty files were found** — every tracked source has distinct content. The
only deletion-adjacent items are the transient local handoff and the untracked PDF leftover, neither
of which this process touches.

## Action summary (advisory only)
- Move the 16 [ARCHIVE] planning/record docs to a `docs/archive/` tree (or add an "ARCHIVED —
  superseded by `docs/consolidated/`" banner), **keeping** the ADRs and [KEEP-LIVE] reference docs
  in place.
- Refresh the three lagging live docs (C8, C10).
- Refresh or retire `SESSION_STATUS.local.md` locally.
