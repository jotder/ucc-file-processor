# ADR-0006: GroundingGuard — every narrated claim traceable to evidence

**Status:** Accepted **Date:** 2026-06-04 **Deciders:** Kernel maintainers, UCC eng lead

## Context

UCC's `NarrativeGuard` checks generated narration against the facts the model was allowed to use. CxO ADR-0001 mandates a response check that every number maps to a tool output. Both are the same safeguard against the model asserting figures or claims that no tool produced. All three agents narrate (CVVE summarizes validation outcomes too), so the safeguard belongs in the kernel.

## Decision

A ring-1 **`GroundingGuard`** exposes:

```
record Verdict(boolean grounded, List<String> ungrounded) {}
Verdict check(String narration, List<Evidence> allowed);
```

A figure or claim absent from the allowed `Evidence` is flagged **ungrounded**; the host decides the consequence (suppress the claim, repair via `RepairLoop`, downgrade confidence feeding `EscalationPolicy`, or abstain). The guard is a **pure function over text + evidence — no model call** — so it is deterministic, fast, and unit-testable. It does not itself decide policy; it reports.

## Options Considered

### Option A: Trust the system prompt to forbid unsourced claims

| Dimension | Assessment |
|---|---|
| Effort | None |
| Enforceability | None — models drift |
| Auditability | Poor |

**Pros:** no code. **Cons:** not enforceable; fatal for CxO; nothing to test.

### Option B: Deterministic post-hoc grounding check (chosen)

| Dimension | Assessment |
|---|---|
| Enforceability | Real — claims checked against evidence |
| Testability | High — pure function |
| Reuse | Generalizes NarrativeGuard + CxO response check |

**Pros:** enforceable and testable; one implementation serves all three apps. **Cons:** extraction heuristics need maintenance and won't have perfect recall.

## Trade-off Analysis

Imperfect-but-deterministic verification versus unverifiable prompting. Only the former is auditable, and auditability is the entire point for CxO and a clear win for UCC/CVVE. Heuristic maintenance is a manageable, localized cost.

## Consequences

- **Easier:** catching ungrounded figures; auditing answers; reusing one guard across apps.
- **Harder:** maintaining and tuning extraction heuristics per app.
- **Revisit:** the extraction strategy can improve over time; the check itself stays.

## Action Items

1. `GroundingGuard` + `Verdict` in ring-1 `…tool`.
2. Tests: a figure absent from `evidence` is flagged ungrounded.
3. Wire into the `RepairLoop` / `EscalationPolicy` consequence path at the R1 orchestrator (ADR-0002, "worked application").
