# ADR-0008: Audit/observability carries keys and summaries only — never data-plane values

**Status:** Accepted **Date:** 2026-06-04 **Deciders:** Kernel maintainers, UCC eng lead

## Context

All three agents emit audit/observability events. CVVE is multi-tenant SaaS with strict isolation and an immutable audit ledger; UCC processes telecom data; CxO handles proprietary financial figures. If audit events captured actual data-plane values (cell contents, computed figures, raw prompts/outputs with embedded data, user inputs), the audit log itself becomes a PII/secrets sink and a cross-tenant exposure risk. The invariant must be fixed *before* any sink — in-memory or durable — is written.

## Decision

`AgentEvent` variants (`AgentStarted` / `AgentCompleted` / `AgentFailed`, `ModelCalled`, `ToolCalled` / `ToolCompleted`) carry **identifiers, counts, durations, tiers, token usage, confidence, and provenance references** — keys and summaries only. They **never** carry data-plane values: no record contents, no `Evidence.value`, no raw prompt/output text with embedded data. The default `RingBufferAuditSink` and any ring-2 durable sink (e.g. `agent-store-postgres` for CVVE's ledger) inherit and must uphold this invariant. `Evidence.sourceRef` (ADR-0004) is a reference/locator, not the value.

## Options Considered

### Option A: Events carry full payloads for richer debugging

| Dimension | Assessment |
|---|---|
| Debuggability | Maximal — full context in logs |
| Privacy | Unacceptable — PII/secrets in logs |
| Multi-tenant | Cross-tenant leak risk (fatal for CVVE) |

**Pros:** deepest log-only debugging. **Cons:** PII/secrets in the audit trail; cross-tenant exposure; retention/compliance burden; disqualifying for CVVE.

### Option B: Keys / summaries / provenance only (chosen)

| Dimension | Assessment |
|---|---|
| Privacy | Safe by construction |
| Multi-tenant | Tenant-safe |
| Cost | Small events; compliance-friendly |

**Pros:** safe by construction; compliance- and tenant-friendly; small, cheap events. **Cons:** deep debugging requires reproduction with inputs held elsewhere, not log-spelunking.

## Trade-off Analysis

Some debugging convenience versus a safe, compliant, tenant-isolated audit trail. For a multi-tenant V&V platform (CVVE) and a financial decision tool (CxO), the safety invariant is non-negotiable; reproduction-based debugging is an acceptable substitute.

## Consequences

- **Easier:** compliance; multi-tenant safety; cheap event storage; ledgers safe to retain.
- **Harder:** debugging from logs alone; deep diagnosis must reproduce with inputs held outside the audit trail.
- **Revisit:** never for the no-data-plane-values invariant.

## Action Items

1. `AgentEvent` fields are keys/summaries/provenance only — no data-plane values — in ring-1 `…observe`.
2. Document the invariant on `AuditSink`; ring-2 durable sinks (CVVE ledger) must uphold it.
3. `Evidence.sourceRef` is a locator, not a value (ADR-0004).
