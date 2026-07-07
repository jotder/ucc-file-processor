---
type: Concept
title: Inspecto ↔ eoiagent integration
description: Inspecto consumes eoiagent as a narrow model transport (EoiGatewayModelProvider over LlmGateway), with the reasoning layer vendored in inspecto-agent — not the full Agent-OS embed.
resource: inspecto-agent/src/main/java/com/gamma/agent/
tags: [agentic, eoiagent, inspecto, model-transport, assist]
timestamp: 2026-07-07T00:00:00Z
---

# Inspecto ↔ eoiagent integration

Since 2026-07-07 (the agent-kernel replacement), Inspecto's integration is **deliberately narrow** —
a model transport, *not* the full eoiagent host embed:

* **Vendored reasoning layer** — the discontinued agent-kernel's orchestration layer lives in-tree at
  `inspecto-agent/src/main/java/com/gamma/agent/kernel/**` (`SyncOrchestrator`, `Orchestrations`;
  `ModelProfile` at `com.gamma.agent.model`). The historical `agentkernel.*` sysprop names are kept
  for deployment compatibility.
* **Transport bridge** — `EoiGatewayModelProvider` bridges the vendored `ModelProvider` seam onto
  eoiagent's `LlmGateway`/`Lc4jChatGateway`; a native `OllamaModelProvider` keeps the same public
  surface (`format=json`, 120 s timeout). Consumed artifacts: **`com.eoiagent:eoiagent-core` +
  `eoiagent-model` only** — `eoiagent-host`/`platform`/pack SPI are not in play.
* **Air-gap invariant** — `langchain4j-open-ai` is excluded in `inspecto-agent`; the `EgressGuardTest`
  invariant holds; hosted model SDKs stay in `inspecto-agent-hosted`.
* **Runtime floor** — eoiagent jars are class-file v69 ⇒ the agent modules need a **JDK 25+ runtime**
  (bundled JDK 26 qualifies); a bare JDK 24 fails with `UnsupportedClassVersionError`.
* **Risks to watch** — eoiagent is `0.1.0-SNAPSHOT`, resolved from the local `.m2` (CI builds it from
  source, `jotder/inspect-agent`). Until a `0.1.0` release is cut and published, Inspecto is pinned to
  a moving SNAPSHOT (REQUIREMENTS EOI-7). Note: the eoiagent docs describe no named consumer — the
  Inspecto-side facts live in this repo (`superpower/agent-kernel-replacement-plan.md`).

Related: [assist agent](../backend/agent/assist-agent.md) · [agent module](../backend/modules/agent.md) ·
plan: [`agent-kernel-replacement-plan.md`](../../superpower/agent-kernel-replacement-plan.md).
