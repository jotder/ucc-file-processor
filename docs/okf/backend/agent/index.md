# Agent

The optional AI assist layer: a `ServiceLoader`-discovered assist agent and (separately) hosted model
providers. Both are absent from a minimal/air-gapped build.

# Concepts

* [Assist agent](assist-agent.md) - the `AssistAgent` SPI, `UccAssistAgent`'s seven skills, the abstain-only policy, and the `/assist` routes.
* [Hosted providers](hosted-providers.md) - the hosted-LLM `ModelProvider` module, omitted from air-gapped builds.
* [Embedded intelligence (AGT-5)](embedded-intelligence.md) - the deliberative eoiagent-backed layer: P0 session spine + the P1 investigation tier (analysis tools, Case Store, RCA/impact playbooks, autonomous triage, goalKind seam).
