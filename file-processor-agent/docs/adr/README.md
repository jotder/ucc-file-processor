# Architecture Decision Records — agent-kernel

Load-bearing decisions for the reusable **agent-kernel** (the framework core three agents will share: UCC File-Processor assist, CVVE V&V-as-a-Service, Real-Estate CxO Decision-Support). See [`../AGENT_ARCHITECTURE.md`](../AGENT_ARCHITECTURE.md) for the full design and [`../AGENT_KERNEL_K0_K1_PLAN.md`](../AGENT_KERNEL_K0_K1_PLAN.md) for the K0/K1 build plan.

| ADR | Title | Status |
|---|---|---|
| [0001](adr-0001-framework-agnostic-zero-dep-core.md) | Framework-agnostic core with zero ring-1 runtime dependencies | Accepted |
| [0002](adr-0002-own-repo-three-rings-semver.md) | Own repository, three-ring module structure, independent SemVer (incl. orchestrator deferred to R1) | Accepted |
| [0003](adr-0003-capability-tool-orchestrate-compute.md) | Two-level Capability → Tool; LLM orchestrates & narrates, code computes & validates (incl. transport-free tools / defer MCP) | Accepted |
| [0004](adr-0004-evidence-credibility-tier.md) | Unified Evidence + CredibilityTier provenance model | Accepted |
| [0005](adr-0005-confidence-escalation-pluggable-rungs.md) | Confidence-driven escalation with pluggable terminal rungs | Accepted |
| [0006](adr-0006-grounding-guard.md) | GroundingGuard — every narrated claim traceable to evidence | Accepted |
| [0007](adr-0007-java25-floor-github-packages.md) | Java 25 bytecode floor; GitHub Packages + GitHub Actions release | Accepted |
| [0008](adr-0008-audit-keys-not-data-plane.md) | Audit/observability carries keys and summaries only — never data-plane values | Accepted |

**Convention:** ADRs are immutable once Accepted. To change a decision, add a new ADR that supersedes it. These eight are the kernel's founding set, accepted as design decisions before the repo is created; they move into `agent-kernel/docs/adr/` at K0.

**Consolidation note (2026-06-04):** an initial draft had ten ADRs. Two were merged as facets of larger decisions rather than independent choices: *transport-free tools / defer MCP* folded into **0003** (it's a property of the `Tool` contract), and *defer the synchronous orchestrator to R1* folded into **0002** (it's the rule-of-three applied). The Java-floor and audit ADRs were renumbered to 0007 and 0008.
