# ADR-0002: Own repository, three-ring module structure, independent SemVer

**Status:** Accepted **Date:** 2026-06-04 **Deciders:** Kernel maintainers, UCC eng lead

## Context

The agent code currently lives inside UCC's `file-processor-agent` module, interleaving generic runtime (reusable) with UCC-specific bindings (not reusable). Three agents will consume the core on independent release cadences and ownership boundaries. We must decide how to house, version, and evolve shared code without coupling the apps' build/deploy lifecycles or letting one consumer's needs ossify the API.

## Decision

The kernel lives in its **own repository** (`agent-kernel`), published as versioned artifacts under `com.gamma.agentkernel`, on **independent SemVer**. Three rings:

- **Ring 1** — pure core (`agent-kernel-core`, zero deps, ADR-0001).
- **Ring 2** — optional companion modules, each a separate opt-in artifact: providers, spring starter, postgres store, hitl, orchestration, eval.
- **Ring 3** — per-app bindings that live in the app's own repo and **never travel** into the kernel.

Reuse is **library-level**: each app embeds a *pinned* kernel version as a jar; there is no shared running service, so apps may run different kernel versions. A concept enters the kernel only when **≥2 apps share it** (the rule of three). The API stays `0.x`/SNAPSHOT until a 2nd consumer reshapes it, then freezes at `1.0`.

### Worked application: the synchronous orchestrator stays out of the core until R1

The rule of three has an immediate, load-bearing consequence worth recording explicitly, because it will be questioned ("why isn't the orchestrator in the core?"). UCC runs a synchronous pipeline — dispatch capability → estimate confidence → escalate → ground narration → audit. It is tempting to extract that as a ring-1 orchestrator at K1. We **do not**. CVVE needs an async state machine and CxO a streaming loop; the orchestration shell is the *most consumer-divergent* piece of the design, so it is precisely the last thing that should enter the core on a single consumer's shape.

Therefore K1 ships the **ingredients** — `EscalationPolicy`, `ConfidenceEstimator`, `GroundingGuard` (ADR-0006), `Deadline`, `RepairLoop`, `AuditSink` (ADR-0008) — plus `CapabilityRegistry.dispatch()` for plain dispatch, but **no assembled pipeline**. UCC keeps composing these in its own module until **R1**, when a shared synchronous orchestrator lands in ring-2 (`agent-orchestration`) once a 2nd consumer has exercised the building blocks. UCC then swaps to it in a later U-phase. (Shipping it in ring-1 at K1 was considered and rejected: faster UCC integration, but a premature freeze of the core's most divergent component.)

## Options Considered

### Option A: Keep agent code in the UCC repo; extract "later"

| Dimension | Assessment |
|---|---|
| Setup cost | Low now |
| Reuse | Poor — CVVE/CxO can't consume cleanly |
| API shape | Risk — shaped by UCC alone |

**Pros:** no repo setup today. **Cons:** the API is defined by one consumer; cross-team ownership is muddy; extraction debt compounds.

### Option B: Monorepo holding kernel + all apps

| Dimension | Assessment |
|---|---|
| Refactors | Atomic across kernel + apps |
| Lifecycle coupling | High — shared build/deploy/access |
| Cadence | Contradicts independent release schedules |

**Pros:** atomic cross-cutting changes. **Cons:** couples unrelated deploy lifecycles, build times, and access control; contradicts the apps' independent cadences.

### Option C: Separate kernel repo; companions as modules; bindings stay in app repos (chosen)

| Dimension | Assessment |
|---|---|
| Ownership | Clean (CODEOWNERS); apps file issues upstream |
| Cadence | Fully independent; apps pin versions |
| Surface area | Opt-in companions; lean core |

**Pros:** clean ownership; independent cadence; opt-in companion surface; lean core. **Cons:** cross-repo dev friction during early churn — mitigated by SNAPSHOT publishing and local `mvn install`.

## Trade-off Analysis

Some cross-repo coordination during the churn phase versus clean decoupling and genuine reuse. Because the three apps ship on independent schedules and stacks, decoupling is the only structure that serves all of them; the coordination cost is temporary and tooling-solvable.

## Consequences

- **Easier:** independent releases; opt-in surface area; clear ownership; lean core preserved.
- **Harder:** cross-repo changes during early churn; must publish SNAPSHOTs for downstream dev loops.
- **Revisit:** ring membership per the rule-of-three; `1.0` freeze only after a 2nd consumer has reshaped the API. At **R1**, fold the proven synchronous-orchestrator assembly into ring-2 `agent-orchestration` and migrate UCC to it.

## Action Items

1. Create the `agent-kernel` repo with parent + `agent-kernel-core` + the two ring-2 modules UCC needs (provider-ollama, eval).
2. `CODEOWNERS` names kernel maintainers.
3. Document the rule-of-three admission test; apps pin concrete versions, bumps are deliberate PRs.
4. K1 ships escalation/confidence/grounding/deadline/repair/audit ingredients + `CapabilityRegistry.dispatch()` — **no assembled orchestrator** in ring-1; schedule the shared sync orchestrator for R1 as ring-2 `agent-orchestration`.
