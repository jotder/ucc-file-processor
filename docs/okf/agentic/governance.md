---
type: Concept
title: eoiagent governance & safety
description: Approval gate + dry-run for every mutating action, Role×Capability×Profile policy, deterministic guardrails, append-only audit, eval-based certification.
resource: C:/sandbox/agent-brainstorm/docs/specs/approval-governance.md
tags: [agentic, safety, approval, guardrails, audit, eval]
timestamp: 2026-07-07T00:00:00Z
---

# eoiagent governance & safety

Safety is **in code, not in the prompt**:

* **Approval gate + dry-run (ADR-0008)** — every mutating tool call requires a dry-run preview and a
  blocking human decision through the host-supplied `ApprovalHandler`. Hard invariant: **no mutating
  `ToolResult` without a preceding `APPROVED` audit event**; headless/offline contexts fail closed
  (DENIED).
* **PolicyEngine** — RBAC over Role × Capability × DeploymentProfile; the `ToolRegistry.dispatch`
  choke point enforces policy, approval routing, and audit for every call; tools declare `mutating`,
  `requiredRole`, `capability`.
* **Guardrails** — deterministic, offline-safe (regex/heuristics + JSON-schema): input side
  (prompt-injection detection, PII redaction), output side (schema validation with bounded retry).
  No model call in the guard path.
* **Audit (ADR-0009)** — `AuditSink.record(AuditEvent)` is append-only and **never disabled** (distinct
  from logging); kinds cover model calls, tool calls, decisions, approvals, mutations, retrievals,
  errors; a failed APPROVAL/MUTATION write fails the action.
* **Eval-based certification (ADR-0013)** — models are deployment config, not code: a new model is
  adopted by passing the golden-case eval harness (kind/answer/tool-call/navigation/citation
  assertions), which reconstructs tool calls from the audit stream and gates CI against a regression
  baseline.
