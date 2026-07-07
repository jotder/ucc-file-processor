---
type: Concept
title: eoiagent architecture
description: Eleven stable ports behind hexagonal adapters, deployment profiles with a fail-closed capability matrix, and the core vs application-pack split.
resource: C:/sandbox/agent-brainstorm/docs/architecture/
tags: [agentic, architecture, hexagonal, ports, application-pack]
timestamp: 2026-07-07T00:00:00Z
---

# eoiagent architecture

* **Eleven core ports** (each with swappable adapters): Model Access (`LlmGateway` — Ollama,
  OpenAI-compatible `baseUrl`, Anthropic, Gemini, routing + stub), Knowledge/RAG (`Retriever` /
  `VectorStore` / `DocumentIngestor`), Tools (`Tool`/`ToolRegistry` — host `@Tool` methods + MCP),
  Runtime (`Planner`/`Orchestrator`/`TaskManager` — ReAct, agentic supervisor, LangGraph, reflection),
  Memory, Scratchpad (virtual-FS context offloading), Safety (`ApprovalGate`/`Guardrail`/`PolicyEngine`),
  Persistence (`CheckpointStore`), Observability (`AuditSink`/`TraceCollector`), Host
  (`AgentService`/`AgentSession`), Config (`ConfigProvider`).
* **Hexagonal discipline (ADR-0004)** — ports import no framework; third-party libs appear only in
  adapters; experimental deps are quarantined behind feature flags (ADR-0010). Enforced by JDK
  Class-File-API arch tests.
* **Deployment profiles** — `OFFLINE` / `ON_PREM_HOSTED` / `CLOUD` select model placement through a
  **capability matrix**: a feature is on only if the matrix permits AND config enables it; classpath
  presence never enables; OFFLINE **fails closed** (hosted models always off).
* **Core vs Application Pack (ADR-0011)** — mechanism is CORE (reused unchanged); policy/content/
  bindings live in a per-product **pack** implementing the SPI (metadata, model profile, knowledge
  sources, tools, navigation catalog, prompts, policy, config). One pack per deployment; a new product
  is a new pack, never a fork; the `AppId` is stamped into every context + audit event.
* **Host integration** — `AgentService.open(...)` → `AgentSession.ask/askStream/close`; the host
  supplies `PageContext` per ask, an `AnswerSink` for streaming, and an `ApprovalHandler`; it gets a
  typed `AgentAnswer` (TEXT / INLINE_ARTIFACT / NAVIGATION / CLARIFICATION / ERROR) — faults come back
  as `ERROR` answers, never raw exceptions. The host depends only on `host` + `core`.
