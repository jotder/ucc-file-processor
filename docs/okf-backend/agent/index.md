# Agent

The optional AI assist layer: a `ServiceLoader`-discovered assist agent and (separately) hosted model
providers. Both are absent from a minimal/air-gapped build.

# Concepts

* [Assist agent](assist-agent.md) - the `AssistAgent` SPI, `UccAssistAgent`'s seven skills, the abstain-only policy, and the `/assist` routes.
* [Hosted providers](hosted-providers.md) - the hosted-LLM `ModelProvider` module, omitted from air-gapped builds.
