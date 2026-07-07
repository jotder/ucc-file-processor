---
metadata:
  document_id: CONFLICT-REPORT
  title: Conflict Report
  last_updated_date: 2026-06-13
  sources_used: [all 44 source markdown files]
  open_questions:
    - C2 (console naming) and the C4/C5 version specifics are genuine open items, surfaced rather than merged.
  assumptions_made:
    - Per the Consolidation Principles, contradictions are flagged here rather than silently merged. Where a clear recency/specificity override exists (e.g. Java 25 supersedes Java 24), the resolution is stated; where no override exists (e.g. console name), it is left open.
---

# Conflict Report

Structured contradictions found across the source set. **Impact** = risk if a stakeholder acted on
the wrong value. "Resolution" reflects the current truth used in the consolidated docs (recency +
specificity); items with no clear override are flagged as open.

| # | Origin A | Origin B | Contradictory data point | Impact | Resolution / status |
|---|---|---|---|---|---|
| **C1** | `inspecto/README.md`, `operations.md`, `configuration.md`, `operator-console.md` ("**Inspecto**" / "Inspector") | `design_analysis.md`, `superpowers/*` ("**UCC File Processor**" / "`file-processor`"), all `inspecto-agent/docs/*` ("UCC") | Product name | **Medium** | **Inspecto** is current. Historical docs predate the rebrand. ArtifactIds (`file-processor`) and `com.gamma.*` packages legitimately remain. |
| **C2** | `operator-console.md`, `inspecto-ui/README.md` ("**Inspector**" console) | `assist-agent-improvement-plan.md` R3 (proposes "**Inspecto Console**") | Web-console name | **Low** | **Open.** R3 lists this as an unresolved decision. Consolidated docs use "Inspector" (the shipped name) and flag the open item. |
| **C3** | `api-stability.md`, `operations.md`, `inspecto/README.md`, `adr-0007` ("**Java 25+**") | `design_analysis.md` (§1 "Java 24"), `AGENT_ARCHITECTURE.md` §4 ("Java 24"), `superpowers/2026-05-27-batch-processing.md` ("Java 24" ×2), `v3-architecture.md` A1 ("Java 24") | Java version floor | **High** | **Java 25+** is current (ADR-0007; v4.0 U0 bump). "Java 24" describes the pre-bump state only. A stakeholder must not set the build floor to 24. |
| **C4** | `inspecto/README.md` (two-module reactor + optional modules) | `v3-architecture.md` A1 ("A single Maven module"), `superpowers/2026-05-27-batch-processing.md` ("single … module `inspecto/`") | Module structure | **Medium** | **Two-module reactor** (`inspecto/` + `inspecto-agent/`) + optional `inspecto-agent-hosted/` + standalone `inspecto-ui/`. Single-module is pre-v3.0. |
| **C5** | Project memory / `SESSION_STATUS.local.md` (kernel **1.1.0** released; project consumes **1.0.0**) | `AGENT_KERNEL_*` plans, `AGENT_ARCHITECTURE.md`, ADRs (only ever name **`0.1.0-SNAPSHOT`**; "`1.0` not yet frozen", R1 "one slice landed") | agent-kernel version & freeze status | **Medium** | Kernel has reached **1.x**; Inspecto consumes **1.0.0** (1.1.0 bump optional). The R1 plan is the most stale on this axis. |
| **C6** | `inspecto-ui/README.md`, `ui-components.md`, `operations.md` (`inspecto-ui/`, `src/app/inspecto/`, `inspecto-` selectors, `<inspecto-chart>`) | `SESSION_STATUS.local.md` (`inspector-ui/`), `devextreme-migration-plan.md` (`inspector-ui2/`, `src/app/ucc/`, `ucc-` selectors, `uccAuthGuard`), `assist-agent-improvement-plan.md` (`UccAssistAgent`) | UI dir + code namespace + selectors | **Medium** | Current: `inspecto-ui/`, `src/app/inspecto/`, `inspecto-` selectors. `inspector-ui`/`inspector-ui2`/`ucc` are pre-rename transitional states. |
| **C7** | `operations.md` (`inspecto_*` metrics, `inspecto_status_*` tables, `inspecto.events`) | `v2-plan.md` (`ucc_*` metrics, `ucc_status_*` tables, `ucc.events`, `ucc-status.db`) | Metric / table / logger names | **Medium** | **`inspecto_*`** is current (engine rename pass); legacy `ucc_status_*` migrated in place on first connect; legacy `ucc-status.db` still picked up. Affects scrape configs / status-DB queries. |
| **C8** | `inspecto/README.md`, `operations.md`, `inspecto-ui/README.md` (dev SPA `:4204`) | `operator-console.md` (prose "run the SPA on **:4200**"; troubleshooting row cites `:4200`; yet its own command uses `:4204`) | Dev SPA port / CORS origin | **Low–Med** | **:4204** is current (the proxy + README). `operator-console.md` is internally inconsistent — the `:4200` references are stale. |
| **C9** | `refactor-blueprint-v4.md` header ("**Phase 1/2/3 implemented**… **All phases complete**", 2026-06-11/12) | `refactor-blueprint-v4.md` (same file — leftover sentence "**Phases 2–3 not started**") | Refactor phase status | **Medium** | **Internal contradiction.** Phases 1–3 are complete (dominant, dated statements + test counts). The "Phases 2–3 not started" fragment is stale leftover. |
| **C10** | `delimited-grammar-design.md` ("**IMPLEMENTED (4.1)**" — `processing.grammar`, native SQL\*Plus routing, `encoding`/`compression`/`strict_mode`/`null_strings` pass-through, row filters) | `parsing-options-reference.md` (§2: only delimiter/has_header/engine/skip_* reach DuckDB; §8 lists "surface the [NATIVE] knobs" as not-yet-done roadmap step 1) **and** `configuration.md` (documents only inline `csv_settings`, no grammar file) | Which delimited-parsing features exist | **Medium** | The delimited grammar + native routing + pass-through knobs **shipped in 4.1** (most recent, most specific). `parsing-options-reference.md` §2/§8 and `configuration.md` are **not updated** — they understate current capability. (The broader `parsing:` frontends — fixedwidth/json/text_regex/xml — remain [PROPOSED] consistently.) |
| **C11** | `v3-plan.md` (M1 = Metadata Graph, M2 = Smart Config, M3 = assist…) | `design_analysis.md` mermaid (M1 = Smart Config, M2 = Assist Platform…) | Milestone numbering | **Low** | `v3-plan.md` is authoritative; `design_analysis.md` flags its own diagram as predating the keystone insertion ("read M0→M1(graph)→M2(config)…"). Self-resolved. |
| **C12** | `design_analysis.md` (file:// links to `c:/sandbox/ucc-inspecto/...`) | actual repo `C:\sandbox\ucc-file-processor` | Repo path in links | **Low** | `ucc-inspecto` paths are wrong/broken — a transitional name that never matched the repo. |
| **C13** | `superpowers/2026-05-27-*` (repo root `C:\sandbox\URA\sandbox`; product path `…\file-processor`) | actual repo `C:\sandbox\ucc-file-processor` | Repo root path | **Low** | Historical path; superseded. |
| **C14** | `devextreme-migration-plan.md` header ("**PLANNING ONLY — not scheduled**… Nothing here has been started") | same file's phase table + epilogue ("**DONE ✅**" through Phase 6; "old app retired… renamed") | Migration status | **Low–Med** | **Internal contradiction.** The migration is **complete** (body + epilogue + `inspecto-ui/README.md` "Replaced the original DevExtreme app in v4.1"). The header banner is stale. |
| **C15** | `assist-agent-improvement-plan.md` A1 (persist settings as **`assist.toon`**) | same file's status note (persisted as **`assist-settings.properties`** — "a pragmatic deviation") | Assist settings persistence format | **Low** | `assist-settings.properties` is what shipped; `assist.toon` was the spec idea. Self-resolved in the doc. |
| **C16** | `adr-0005` (Inspecto rung list `[BumpModelTier, Abstain]`) | `AGENT_KERNEL_U0_U1_PLAN.md` (Inspecto wires **only `Abstain`** at U1) | Inspecto escalation rungs | **Low** | Not a true contradiction: the ADR states the design capability; U1 states what Inspecto actually wired (`Abstain` only). Documented for the decision log. |
| **C17** | `performance.md` / current docs (DuckDB **1.5.2**) | `superpowers/2026-05-27-*` (DuckDB JDBC **1.5.2.1**) | DuckDB version pin | **Low** | Minor pin drift across time; latest perf docs cite 1.5.2. Historical only. |

## Notes on resolution approach
- **High-impact (C3)** resolved decisively in favour of the current value (Java 25) — a wrong build
  floor is a hard failure.
- **Internal contradictions (C9, C14)** are flagged in-place in the relevant consolidated docs
  rather than silently overwritten.
- **Genuinely open (C2)** left unresolved with the open question surfaced in 01/08.
- **C10** is the one capability conflict where two docs disagree about *what exists today*; resolved
  toward "implemented" on recency/specificity, with the lagging docs flagged for update — no
  guessing or invented merge.
