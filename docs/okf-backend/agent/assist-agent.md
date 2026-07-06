---
type: Concept
title: Assist Agent
description: The AssistAgent SPI, UccAssistAgent's seven read-only skills on agent-kernel, abstain-only policy, /assist routes.
resource: inspecto/src/main/java/com/gamma/assist/spi/AssistAgent.java
tags: [agent, assist, spi, agent-kernel, skills]
timestamp: 2026-06-28T00:00:00Z
---

# Assist Agent

The assist surface is an SPI in the core, implemented by the optional [agent module](../modules/agent.md).

* **SPI** — `AssistAgent` (`inspecto/src/main/java/com/gamma/assist/spi/AssistAgent.java`), discovered via
  `ServiceLoader`. When no provider is present the assist routes degrade gracefully.
* **Implementation** — `UccAssistAgent` (`inspecto-agent/src/main/java/com/gamma/agent/UccAssistAgent.java`)
  on the vendored kernel layer (`com.gamma.agent.kernel.*`, ex agent-kernel — discontinued; eoiagent supplies model transport), registering seven **read-only / draft-only** skills: `DiagnoseAndAlertSkill`,
  `ExplainEntitySkill`, `KpiToSqlSkill`, `NlToScheduleSkill`, `ReportNarrativeSkill`, `ReportSqlSkill`,
  `SuggestConfigSkill`. Dispatch runs `SyncOrchestrator` → `CapabilityRegistry` → skill →
  `UccConfidenceEstimator`, then either surfaces the result or `EscalationRung.Abstain` (**abstain-only** — no
  tier-bump, no human handoff; `applyVia` is always `null`).

## Routes

`POST /assist/{intent}` (dispatches by intent; unknown → unsupported, model down → unavailable),
`GET /assist/diagnoses`, `GET/POST /assist/settings` (live reconfigure under the write-gate),
`POST /assist/settings/test` (probe each model tier), `GET /assist/metrics` (counts only, never data values).

The write-back routes are governed by the `-Dassist.write.root` gate (`503` when unset — see
[auth & security](../editions/auth-security.md)). Hosted model backends come from
[agent-hosted](hosted-providers.md).
