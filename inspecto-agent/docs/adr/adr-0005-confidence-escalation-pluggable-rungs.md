# ADR-0005: Confidence-driven escalation with pluggable terminal rungs

**Status:** Accepted **Date:** 2026-06-04 **Deciders:** Kernel maintainers, UCC eng lead

## Context

The three agents share one control loop — *assess confidence, then escalate when it's too low* — but their terminal action differs. UCC bumps the model tier (SMALL→MEDIUM→LARGE) and ultimately abstains. CVVE escalates to human review (HITL). CxO abstains ("no data") rather than guess. Hard-coding UCC's tier-bump as *the* escalation would be useless to CVVE and CxO.

## Decision

A ring-1 **`EscalationPolicy`** runs: attempt → **`ConfidenceEstimator`** → if below the capability's threshold, walk an ordered list of **`EscalationRung`** (a sealed type: `BumpModelTier`, `HumanHandoff(queue)`, `Abstain`). Apps compose the rung list to their needs:

- UCC → `[BumpModelTier, Abstain]`
- CVVE → `[HumanHandoff]`
- CxO → `[Abstain]`

Capabilities read their tier via **`ctx.effectiveTier(spec.defaultTier())`** so `BumpModelTier` can re-run an attempt at a higher tier — this is the single contract capability authors must honour. `ConfidenceEstimator` composes deterministic signals: validator pass/fail, grounding coverage (ADR-0006), oracle agreement, schema conformance, evidence credibility (ADR-0004), and optionally the model's self-report.

## Options Considered

### Option A: Hard-code tier-bump escalation

| Dimension | Assessment |
|---|---|
| Simplicity | High — matches UCC today |
| Reuse | None — no HITL or abstain path |

**Pros:** simplest; matches UCC's current behavior. **Cons:** unusable for CVVE (HITL) and CxO (abstain).

### Option B: Pluggable sealed rungs + a composable estimator (chosen)

| Dimension | Assessment |
|---|---|
| Reuse | High — one loop serves all three |
| Testability | High — deterministic, rung-by-rung |
| Extensibility | Additive — new terminal rungs |

**Pros:** one escalation engine for all three apps; deterministic and testable; new terminal actions are additive. **Cons:** capabilities must honour `effectiveTier`; a little indirection.

## Trade-off Analysis

A single contract authors must follow (`effectiveTier`) versus one reusable escalation engine that spans tier-bump, human-handoff, and abstain. The contract is small and documented; the reuse payoff spans all three agents.

## Consequences

- **Easier:** shared escalation across apps; deterministic, fast tests; new rungs added without touching the loop.
- **Harder:** capability authors must read tier via `ctx.effectiveTier` — the one coupling point between `EscalationPolicy` and `Capability`.
- **Revisit:** the rung set may grow; the loop shape is stable.

## Action Items

1. `EscalationPolicy`, sealed `EscalationRung`, and `ConfidenceEstimator` in ring-1 `…reason`.
2. Document the `effectiveTier` contract on `Capability` (ADR-0003).
3. Per-rung tests: tier-bump succeeds, human-handoff parks, abstain returns `UNAVAILABLE`.
