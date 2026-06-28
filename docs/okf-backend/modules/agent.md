---
type: Module
title: Agent Module (inspecto-agent/)
description: Optional AI assist skills built on the agent-kernel library; discovered via the AssistAgent SPI.
resource: inspecto-agent/
tags: [module, agent, assist, agent-kernel, optional]
timestamp: 2026-06-28T00:00:00Z
---

# Agent Module (`inspecto-agent/`)

artifactId `file-processor-agent`. The **optional** AI assist module: when present on the classpath the core
discovers it via `ServiceLoader<com.gamma.assist.spi.AssistAgent>`; when absent, the assist routes degrade
gracefully. Built on the reusable **`agent-kernel`** library (currently `1.0.0`; `1.1.0` available, bump
optional). The concrete implementation `UccAssistAgent` registers seven read-only/draft-only skills with an
abstain-only escalation policy — see [Assist agent](../agent/assist-agent.md).
