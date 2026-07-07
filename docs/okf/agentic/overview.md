---
type: Overview
title: eoiagent overview
description: Embeddable plain-Java agent platform — JDK 25, LangChain4j-based, 18-module reactor, offline-first, permissive-only licensing.
resource: C:/sandbox/agent-brainstorm (separate repo)
tags: [agentic, eoiagent, overview, langchain4j]
timestamp: 2026-07-07T00:00:00Z
---

# eoiagent overview

An **embeddable agent platform**, not a chatbot: it analyzes metadata/schemas, drafts pipelines/SQL,
investigates production problems, executes **gated** operational actions, and gives page-aware
in-product help (signature output: a `NavigationIntent` that routes the user to the right screen).

* **Stack** — JDK 25 (`release=25`; class-file v69), Maven, **no Spring/Quarkus** (ADR-0001).
  Foundation: **LangChain4j 1.16.3** (BOM-pinned) for models/RAG/tools/memory/MCP;
  `langchain4j-agentic` + **LangGraph4j** for orchestration; in-JVM **ONNX MiniLM** embeddings;
  JDK `HttpClient` transport (ADR-0002).
* **Reactor** — 18 modules: a BOM; `core` (all ports + domain records, zero framework imports);
  adapter modules per port (`model`, `knowledge`, `tool`, `runtime`, `memory`, `scratchpad`, `safety`,
  `persistence`, `observability`, `config`); the host facade (`host`); the Application-Pack SPI
  (`app-api`) + bootstrap (`platform`); `eval`; a worked reference pack (`app-reference`); `examples`.
* **Maturity** — phases 0–2 implemented, phase 3 + the 3.5 "wire everything into the live ask() path"
  integration pass largely done per the 2026-07-03 hardening docs; builds green and runs fully offline.
  Version **`0.1.0-SNAPSHOT`** — no release tag yet (see
  [Inspecto integration](inspecto-integration.md) for why that matters here).
* **Licensing** — permissive-only dependency policy (Apache-2.0/MIT/BSD; no *GPL) enforced by a
  license-report gate in CI (ADR-0012, formally still Proposed).
