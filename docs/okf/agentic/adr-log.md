---
type: Concept
title: eoiagent ADR log
description: The fourteen architecture decision records, one line each.
resource: C:/sandbox/agent-brainstorm/docs/adr/
tags: [agentic, adr, decisions]
timestamp: 2026-07-07T00:00:00Z
---

# eoiagent ADR log

| ADR | Decision |
|---|---|
| 0001 | Embeddable plain-Java library — no Spring/Quarkus; plain constructors/builders + ports. |
| 0002 | JDK 25 + Maven; JDK `HttpClient` transport (no Netty). |
| 0003 | LangChain4j 1.16.3 as the one AI foundation, BOM-pinned. |
| 0004 | Hexagonal ports & adapters; port changes require an ADR; arch-test enforced. |
| 0005 | Hybrid orchestration: langchain4j-agentic for MVP, LangGraph4j for stateful/checkpointed flows, one `Orchestrator` port. |
| 0006 | Local/on-prem model access standardized on the OpenAI-compatible `baseUrl` client; engine swap = config. |
| 0007 | `InMemoryEmbeddingStore` embedded/offline; pgvector in production. |
| 0008 | Every mutating action goes through `ApprovalGate` + dry-run, enforced in the runtime. |
| 0009 | Persisted append-only audit of every decision/tool-call/action; tracing optional. |
| 0010 | Experimental/single-maintainer deps quarantined behind ports + feature flags. |
| 0011 | Reusable CORE + per-product Application Pack; new product = new pack, never a fork. |
| 0012 | Permissive-only dependency licensing (Apache-2.0/MIT/BSD), CI-gated. *(Proposed)* |
| 0013 | Models are pluggable deployment config, certified by the eval harness. |
| 0014 | v1 ships classpath jars + `Automatic-Module-Name`; no JPMS `module-info`. |
