---
type: Module
title: Agent Module (inspecto-agent/)
description: Optional AI assist skills — vendored kernel layer (com.gamma.agent.kernel) + eoiagent model transport; discovered via the AssistAgent SPI.
resource: inspecto-agent/
tags: [module, agent, assist, eoiagent, vendored-kernel, optional]
timestamp: 2026-07-07T00:00:00Z
---

# Agent Module (`inspecto-agent/`)

artifactId `file-processor-agent`. The **optional** AI assist module: when present on the classpath the core
discovers it via `ServiceLoader<com.gamma.assist.spi.AssistAgent>`; when absent, the assist routes degrade
gracefully. The concrete implementation `UccAssistAgent` registers seven read-only/draft-only skills with an
abstain-only escalation policy — see [Assist agent](../agent/assist-agent.md).

The former external `agent-kernel` library is **discontinued**: its reasoning layer is now **vendored
in-tree** at `inspecto-agent/src/main/java/com/gamma/agent/kernel/**` (package `com.gamma.agent.kernel`;
`ModelProfile` at `com.gamma.agent.model`). Model transport is **eoiagent**
(`com.eoiagent:eoiagent-core|-model:0.1.0-SNAPSHOT`) via the `EoiGatewayModelProvider` bridge, plus a native
`OllamaModelProvider`. `langchain4j-open-ai` is **excluded** from this module — the `EgressGuardTest`
air-gap invariant enforces it.
