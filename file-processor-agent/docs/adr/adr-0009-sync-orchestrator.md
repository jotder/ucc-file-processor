# ADR-0009: Ship the assembled synchronous orchestrator in a ring-2 `agent-orchestration` module

**Status:** Accepted **Date:** 2026-06-05 **Deciders:** Kernel maintainers, UCC eng lead

> Mirror of `agent-kernel/docs/adr/adr-0009-sync-orchestrator.md` (canonical). Bundled here with the
> agent module's ADR set for self-containment.

## Context

K1 shipped the orchestration **ingredients** in ring-1 — `CapabilityRegistry.dispatch()` (plain resolve +
run), `EscalationPolicy`, `ConfidenceEstimator`, `GroundingGuard`, `Deadline`, `RepairLoop`, `AuditSink` —
but deliberately **deferred the assembled pipeline to R1** (ADR-0003 §"orchestrate"; the note on
`CapabilityRegistry`). The reason was the rule of three: the orchestrator is one of the pieces most likely
to be wrong if guessed from a single consumer, so the assembly waited until a second consumer (CVVE/CxO)
could shape it.

In the meantime UCC hand-rolled exactly one orchestration shape inside its own module
(`UccAssistAgent.assist`): resolve the capability → run it through `EscalationPolicy.run(estimator)`
(attempt → estimate → abstain below threshold) → emit one keys-only `AgentCompleted`. The R1 plan (§4)
anticipated this: *"This is the pipeline UCC currently hand-rolls in its module; R1 ships it shared, and
UCC swaps its in-module dispatch for it."*

Opening R1, the **synchronous** orchestrator is the one slice that is *not* speculative: UCC's existing,
tested dispatch is its specification. Extracting it both removes the duplication and serves as the design
test for the K1 seam — *does the assembled pipeline compose from the existing ring-1 ingredients with no
ring-1 change?*

## Decision

Ship the assembled **synchronous** orchestrator as `SyncOrchestrator` in a new, **pure ring-2** module
`agent-orchestration` (depends only on `agent-kernel-core`; an enforcer bans any other compile/runtime
dep). It composes the K1 ingredients into the single sync flow:

1. **Resolve** `request.capabilityId()` against the injected `CapabilityRegistry`; an unknown id →
   `AgentResult.unsupported(...)` (still audited).
2. **Run with escalation** via the injected `EscalationPolicy` + `ConfidenceEstimator` (the abstain
   posture, tier-bump, and human-handoff rungs are all the policy's concern, not the orchestrator's).
3. **Audit** exactly one `AgentCompleted` to `ctx.audit()` — keys/summaries only (ADR-0008).

Wire/UI mapping, human-readable logging, pre-agent short-circuiting, and `RuntimeException` containment
stay with the **caller** (transport concerns, not orchestration). UCC consumes `SyncOrchestrator` and
deletes its hand-rolled dispatch; it preserves its `[ASSIST]` operator log via a thin `AuditSink`
decorator.

**No ring-1 change was required** — confirming the K1 seam. The orchestrator is ring-2 (not ring-1)
because it is an assembly an app opts into; ring-1 stays the minimal primitives (ADR-0001/0002).

This **supersedes ADR-0003's deferral note for the synchronous case only.** The **async** and
**streaming** orchestrator variants remain deferred to a later R1 increment, shaped by the second
consumer that actually needs them (CVVE async/state-machine, CxO streaming) — building them now from UCC
alone is the speculative guessing R1 exists to avoid.

## Options Considered

### Option A: Leave the orchestrator hand-rolled in each app
**Pros:** zero kernel change. **Cons:** every consumer re-derives the same resolve→escalate→audit spine;
the K1 "ingredients compose" claim stays unproven; CVVE/CxO would copy UCC's code rather than share it.

### Option B: Put the orchestrator in ring-1 core
**Pros:** one artifact. **Cons:** violates the ring model (ADR-0002) — ring-1 is minimal primitives, not
assembled pipelines; forces every core consumer to carry the assembly even if it wires its own.

### Option C: Ship `SyncOrchestrator` in a pure ring-2 `agent-orchestration` module (chosen)
**Pros:** shares the proven spine; keeps ring-1 minimal and zero-dep; gives the async/streaming variants a
natural home as they arrive; opt-in for consumers. **Cons:** one more module. Accepted — it is the home
the R1 plan already designated for the orchestrator(s).

## Trade-off Analysis

A new module versus eliminating duplicated orchestration and discharging the K1 design test. The sync
extraction is low-risk because it is behavior-preserving (UCC's full suite passes unmodified) and
ring-1-neutral. The cost is one pure module; the benefit is a shared, tested pipeline plus evidence the
seam is right.

## Consequences

- **Easier:** a second consumer composes the same sync pipeline instead of re-deriving it; the K1 seam is
  proven (no ring-1 change); UCC's `UccAssistAgent` shrinks to wire-mapping + resilience.
- **Harder:** nothing material; consumers that want a *different* lifecycle (async/streaming) still wait
  for those variants.
- **Revisit:** when CVVE/CxO triggers R1, add the async and/or streaming orchestrator entry points in this
  same module over the **same** ring-1 ingredients. If a variant cannot be expressed without a ring-1
  change, that change is the R1 reshape signal (R1 §5) — fold it back before `1.0`.

## Action Items

1. `SyncOrchestrator` in `agent-orchestration` (`com.gamma.agentkernel.orchestrate`); pure ring-2
   (enforcer: only `agent-kernel-core`). ✅
2. Update the `CapabilityRegistry` note to point at `SyncOrchestrator` for the sync assembly; async/
   streaming still deferred. ✅
3. UCC consumes it; deletes hand-rolled dispatch; preserves `[ASSIST]` log via an `AuditSink` decorator;
   full reactor green unmodified. ✅
4. Mark R1 §4 "sync orchestrator" delivered (UCC slice); leave the async/streaming + companion-module work
   trigger-gated. ✅
