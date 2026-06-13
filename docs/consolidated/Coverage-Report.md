---
metadata:
  document_id: COVERAGE-REPORT
  title: Coverage Report (Traceability Matrix)
  last_updated_date: 2026-06-13
  sources_used: [all 44 source markdown files]
  open_questions: None
  assumptions_made:
    - Every tracked source markdown file (plus the local SESSION_STATUS handoff) is accounted for; node_modules READMEs are excluded as third-party noise.
    - Target doc IDs: 01 Exec · 02 Product · 03 Arch · 04 ImplStatus · 05 Roadmap · 06 Ops · 07 UserGuide · 08 Decisions · AX Appendix.
---

# Coverage Report — Traceability Matrix

## 1. Source → consolidated document(s)

Every source file and which authoritative document(s) it contributed to.

| # | Source file | Contributed to |
|---|---|---|
| 1 | `inspecto/README.md` | 01, 02, 03, 04, 06, 07, AX |
| 2 | `docs/architecture.md` | 03, 07 |
| 3 | `docs/configuration.md` | 02, 03, 07 |
| 4 | `docs/operations.md` | 03, 06, 07 |
| 5 | `docs/plugins.md` | 02, 03, 07 |
| 6 | `docs/operator-console.md` | 02, 06, 07 |
| 7 | `docs/integrations.md` | 03, 06, 07, AX |
| 8 | `docs/troubleshooting.md` | 06, 07 |
| 9 | `docs/api-stability.md` | 03, 08, AX |
| 10 | `docs/design-notes.md` | 03, 08, AX |
| 11 | `docs/performance.md` | 03, 06, AX |
| 12 | `docs/test-coverage.md` | 04, AX |
| 13 | `docs/parsing-options-reference.md` | 03, 05, 07, AX |
| 14 | `docs/delimited-grammar-design.md` | 03, 04, 05, 07, 08 |
| 15 | `docs/refactor-blueprint-v4.md` | 03, 04, 05, 08 |
| 16 | `docs/design_analysis.md` | 01, 03, 08 |
| 17 | `docs/v3-architecture.md` | 02, 03, 05, 08 |
| 18 | `docs/v3-agent-mvp.md` | 01, 02, 03, 05, 08 |
| 19 | `docs/v3-plan.md` | 04, 05, 08 |
| 20 | `docs/v2-roadmap.md` | 04, 05, 08 |
| 21 | `docs/v2-plan.md` | 04, 05, 08 |
| 22 | `docs/v2-backlog.md` | 02, 05, 08 |
| 23 | `docs/assist-agent-improvement-plan.md` | 03, 04, 05, 08 |
| 24 | `docs/superpowers/specs/2026-05-27-batch-processing-design.md` | 03, 08 |
| 25 | `docs/superpowers/plans/2026-05-27-batch-processing.md` | 03, 04 |
| 26 | `docs/superpowers/plans/2026-05-28-plugin-ingester.md` | 03, 05 |
| 27 | `inspecto-agent/docs/AGENT_ARCHITECTURE.md` | 03, 08 |
| 28 | `inspecto-agent/docs/AGENT_KERNEL_K0_K1_PLAN.md` | 04, 08 |
| 29 | `inspecto-agent/docs/AGENT_KERNEL_U0_U1_PLAN.md` | 04, 08 |
| 30 | `inspecto-agent/docs/AGENT_KERNEL_R1_PLAN.md` | 05, 08 |
| 31 | `inspecto-agent/docs/adr/README.md` | 08 |
| 32 | `inspecto-agent/docs/adr/adr-0001-framework-agnostic-zero-dep-core.md` | 03, 08 |
| 33 | `inspecto-agent/docs/adr/adr-0002-own-repo-three-rings-semver.md` | 03, 08 |
| 34 | `inspecto-agent/docs/adr/adr-0003-capability-tool-orchestrate-compute.md` | 03, 08 |
| 35 | `inspecto-agent/docs/adr/adr-0004-evidence-credibility-tier.md` | 03, 08 |
| 36 | `inspecto-agent/docs/adr/adr-0005-confidence-escalation-pluggable-rungs.md` | 03, 08 |
| 37 | `inspecto-agent/docs/adr/adr-0006-grounding-guard.md` | 03, 08 |
| 38 | `inspecto-agent/docs/adr/adr-0007-java25-floor-github-packages.md` | 03, 06, 08 |
| 39 | `inspecto-agent/docs/adr/adr-0008-audit-keys-not-data-plane.md` | 08 |
| 40 | `inspecto-agent/docs/adr/adr-0009-sync-orchestrator.md` | 03, 08 |
| 41 | `inspecto-ui/README.md` | 03, 06, AX |
| 42 | `inspecto-ui/docs/devextreme-migration-plan.md` | 04, 05, 08 |
| 43 | `inspecto-ui/docs/ui-components.md` | 03, 07, AX |
| 44 | `SESSION_STATUS.local.md` | 01, 04 |

**Coverage: 44 / 44 source files mapped (100%).** Node_modules READMEs and other third-party files
are intentionally excluded as non-project noise.

## 2. Consolidated document → primary sources (reverse view)

| Target | Primary sources |
|---|---|
| **01 Executive Summary** | inspecto/README, v3-architecture, v3-agent-mvp, design_analysis, v2-backlog, SESSION_STATUS |
| **02 Product Requirements** | inspecto/README, v3-agent-mvp, v2-backlog, operator-console, v3-architecture, configuration |
| **03 Architecture and design** | architecture, design-notes, v3-architecture, design_analysis, configuration, plugins, api-stability, performance, integrations, refactor-blueprint-v4, delimited-grammar-design, parsing-options-reference, v3-agent-mvp, AGENT_ARCHITECTURE, ui-components, superpowers spec, ADRs 0001–0009 |
| **04 Implementation Status** | v3-plan, refactor-blueprint-v4, devextreme-migration-plan, assist-agent-improvement-plan, SESSION_STATUS, test-coverage, design-notes, v2-plan, v2-roadmap, api-stability, delimited-grammar-design, AGENT_KERNEL_K0_K1/U0_U1, superpowers batch plan |
| **05 Roadmap** | v3-plan, v3-agent-mvp, v2-roadmap, v2-plan, v2-backlog, refactor-blueprint-v4, parsing-options-reference, delimited-grammar-design, AGENT_KERNEL_R1, assist-agent-improvement-plan, devextreme-migration-plan, superpowers plugin plan |
| **06 Operations** | operations, integrations, troubleshooting, configuration, inspecto/README, operator-console, performance, inspecto-ui/README, adr-0007 |
| **07 User Guide** | inspecto/README, configuration, plugins, operator-console, parsing-options-reference, delimited-grammar-design, integrations, troubleshooting, ui-components |
| **08 Decisions** | ADRs 0001–0009 + adr/README, design-notes, api-stability, v2-plan, v2-backlog, v3-architecture, v3-agent-mvp, v3-plan, refactor-blueprint-v4, assist-agent-improvement-plan, devextreme-migration-plan, AGENT_ARCHITECTURE, AGENT_KERNEL_U0_U1, superpowers spec, delimited-grammar-design |
| **AX Appendix** | api-stability, test-coverage, performance, integrations, parsing-options-reference, inspecto/README, inspecto-ui/README, AGENT_ARCHITECTURE |

## 3. Source-utilization summary

| Utilization | Count | Files |
|---|---|---|
| Fed ≥4 target docs (highest-value sources) | 8 | inspecto/README (7), delimited-grammar-design (5), v3-agent-mvp (5), v3-architecture (4), refactor-blueprint-v4 (4), v3-plan (4*), assist-agent-improvement-plan (4), parsing-options-reference (4) |
| Fed 2–3 target docs | most | the topic + ADR + v2 docs |
| Fed exactly 1 target doc | 2 | adr/README (08), adr-0008 (08) |
| Not used / dropped | 0 | — |

\* Counting AX where applicable. No source file was left uncontributing; the only intentionally
excluded inputs are the local transient handoff's *future-obligation* details (used for 01/04 status
only) and the untracked `Parsing Options Reference.pdf` (binary, summarized via its `.md` companion).
