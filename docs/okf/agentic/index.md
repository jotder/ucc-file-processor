---
okf_version: "0.1"
---

# eoiagent — Agentic Framework (distilled)

**eoiagent** (the *Enterprise Operational Intelligence Agent Platform*) is an embeddable, plain-Java
"Agent Operating System" that runs *inside* a host application. It powers Inspecto's AI assist model
transport and can power other applications. It is a **separate repo** (`C:/sandbox/agent-brainstorm`,
Maven groupId `com.eoiagent`, version `0.1.0-SNAPSHOT`).

> **Authoritative docs live in that repo** (`docs/` — itself an OKF bundle: architecture, 14 ADRs,
> per-component specs, roadmap/backlog, packaging & licensing, security review, CI gates). This section
> is the distilled map plus the Inspecto-specific integration seam — enough to reason about the
> dependency without leaving this repo.

## Concepts

* [Overview](overview.md) - identity, tech stack, the 18-module reactor, maturity, licensing.
* [Architecture](architecture.md) - 11 core ports, hexagonal adapters, deployment profiles,
  core vs application packs, host integration.
* [Governance & safety](governance.md) - approval gate + dry-run, policy RBAC, guardrails,
  append-only audit, eval-based certification.
* [ADR log](adr-log.md) - the 14 architecture decisions, one line each.
* [Inspecto integration](inspecto-integration.md) - how Inspecto consumes eoiagent (narrow
  model-transport seam, not the full embed), and the risks to watch.
