---
type: Concept
title: Hosted Model Providers
description: The inspecto-agent-hosted module — external LLM ModelProviders, physically omitted from air-gapped builds.
resource: inspecto-agent-hosted/
tags: [agent, hosted, llm, providers, air-gapped]
timestamp: 2026-06-28T00:00:00Z
---

# Hosted Model Providers

[`inspecto-agent-hosted/`](../modules/agent-hosted.md) supplies `ModelProvider` implementations backed by
external/hosted LLM SDKs (langchain4j). It is **physically omitted from air-gapped builds** — neither the
[core](../modules/engine.md) nor the [assist agent](assist-agent.md) module depends on a hosted SDK, so an
offline deployment simply doesn't bundle this jar (and the assist agent then runs only against any
locally-available model, or abstains). Model/provider selection is configured via the `/assist/settings`
route.
