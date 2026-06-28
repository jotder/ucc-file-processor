---
type: Module
title: Agent Hosted Module (inspecto-agent-hosted/)
description: Hosted model providers (external LLM SDKs); physically omitted from air-gapped builds.
resource: inspecto-agent-hosted/
tags: [module, agent-hosted, llm, providers, air-gapped]
timestamp: 2026-06-28T00:00:00Z
---

# Agent Hosted Module (`inspecto-agent-hosted/`)

artifactId `file-processor-agent-hosted`. Provides `ModelProvider` implementations backed by external/hosted
LLM SDKs (langchain4j). It is **physically omitted from air-gapped builds** — the [core](engine.md) and the
[agent](agent.md) module carry no hosted-SDK dependency, so an offline deployment simply doesn't bundle this
jar. See [Hosted providers](../agent/hosted-providers.md).
