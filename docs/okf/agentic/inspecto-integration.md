---
type: Concept
title: Inspecto ↔ eoiagent integration
description: Inspecto consumes eoiagent as a narrow model transport (EoiGatewayModelProvider over LlmGateway), with the reasoning layer vendored in inspecto-agent — not the full Agent-OS embed.
resource: inspecto-agent/src/main/java/com/gamma/agent/
tags: [agentic, eoiagent, inspecto, model-transport, assist, intelligence]
timestamp: 2026-07-16T00:00:00Z
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
  surface (`format=json`, 120 s timeout). In `inspecto-agent`, consumed artifacts are
  **`com.eoiagent:eoiagent-core` + `eoiagent-model` only**. Transport behaviors deliberately kept
  Inspecto-side (eoiagent's stock adapters don't carry them): native `ResponseFormat.JSON` for
  Ollama, per-request client timeouts from `ProviderSettings`; token accounting maps eoiagent
  `Usage` `0` back to the kernel's `-1 = unknown` convention.
* **Embedded intelligence (AGT-5 P0, shipped 2026-07-07 + hardened 2026-07-08)** — the exception to
  "transport only": module `inspecto-intelligence` **does** embed the eoiagent platform
  (`eoiagent-core/app-api/platform/model`) — `InspectoPack` (ApplicationPack) +
  `InspectoIntelligenceAgent`, surfaced through the core `IntelligenceAgent` SPI and the
  `/agent/sessions` routes (SSE `/ask/stream`); ONNX one-doc RAG corpus proven offline; UI
  navigation catalog auto-derived from `app.routes.ts`. Open scope cuts: **QA-only**
  (`incident_explain` waits on an eoiagent host seam) and **local-models-only** — tracked in the
  active plan [`embedded-intelligence-plan.md`](../../superpower/embedded-intelligence-plan.md) §8.
* **Air-gap invariant** — `langchain4j-open-ai` is excluded in `inspecto-agent`; the `EgressGuardTest`
  invariant holds; hosted model SDKs stay in `inspecto-agent-hosted`.
* **Runtime floor** — eoiagent jars are class-file v69 ⇒ the agent modules need a **JDK 25+ runtime**
  (bundled JDK 26 qualifies); a bare JDK 24 fails with `UnsupportedClassVersionError`.
* **Version pin** — eoiagent is pinned to the **released `0.1.0`** (EOI-7a, 2026-07-08: tag `v0.1.0`
  on eoiagent `main`; both agent poms pin `eoiagent.version 0.1.0`, no SNAPSHOT anywhere). Remaining
  EOI-7b: publish artifacts to a registry (infra/product call); until then CI reproduces the pin with
  `git checkout v0.1.0 && mvn -o clean install`. Note: the eoiagent docs describe no named consumer —
  the Inspecto-side facts live in this repo.

Related: [assist agent](../backend/agent/assist-agent.md) · [agent module](../backend/modules/agent.md) ·
archived plans: [`agent-kernel-replacement-plan.md`](../../archived-documents/plans-archive/agent-kernel-replacement-plan.md)
· [`agent-brainstorm-assessment.md`](../../archived-documents/plans-archive/agent-brainstorm-assessment.md).
