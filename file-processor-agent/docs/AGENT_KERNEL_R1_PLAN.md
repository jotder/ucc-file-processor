# agent-kernel — R1 Plan (reuse phase: companion modules, shared orchestrator, `1.0`)

**Status:** **In progress (one slice landed).** The **sync orchestrator** (§4) was extracted early from UCC — `SyncOrchestrator` in the new ring-2 `agent-orchestration` module, consumed by UCC (2026-06-05, ADR-0009); behavior-preserving, no ring-1 change. **Everything else stays trigger-gated:** the companion modules (§3), the async/streaming orchestrator variants (§4), the ring-1 reshape pass incl. `CredibilityTier` (§5), and the `1.0` freeze (§6) begin only when the **2nd consumer** (CVVE or CxO) first builds on the kernel · not before then.
**Date:** 2026-06-04
**Locked context:** the **rule of three** governs this phase — a concept enters ring-1 only once **≥2 apps share it**; the kernel stays `0.x`/SNAPSHOT until a 2nd consumer has **reshaped** the API, then freezes **`1.0`**. The sync orchestrator and all ring-2 companions were **deliberately deferred to here** so the 2nd consumer shapes them rather than UCC guessing alone.
**Companion to:** `AGENT_ARCHITECTURE.md` (§3.1 orchestration, §8 matrix, §10 rings, §12 phases, §13 governance), `AGENT_KERNEL_K0_K1_PLAN.md` (ring-1 shipped), `AGENT_KERNEL_U0_U1_PLAN.md` (UCC consuming `0.x`).
**Covers:** **R1** — add the ring-2 companions the 2nd consumer needs, ship the shared orchestrator(s), let real second-app usage reshape the `0.x` API, then **freeze `1.0`**. UCC upgrades `0.x`→`1.0` at its own pace afterward.

---

## ▶ Resume here (next developer)

**Done so far (2026-06-05):** the **synchronous orchestrator** only — `SyncOrchestrator` in the new pure
ring-2 module `agent-orchestration` (kernel `main`), consumed by UCC (`4.x`). Behavior-preserving, no
ring-1 change, both repos' CI green. See ADR-0009 and §4. This was extracted early **because it was the
one non-speculative slice** (UCC's own tested dispatch was its spec); it did **not** require a 2nd consumer.

**Pending — do NOT start without the trigger.** The rest of R1 is **demand-driven** (§0): it begins only
when **CVVE or CxO** actually starts building on the kernel, and *that* consumer's needs pick the module
set (§2). Building any of it from UCC alone is the speculative guessing this phase exists to avoid (§8).
When a 2nd consumer starts, resume at **§7 task 1** (identify the trigger → select its module set). The
open work, all gated:

- [ ] **Async** + **streaming** orchestrator variants in `agent-orchestration` (§4) — CVVE state-machine / CxO streaming.
- [ ] Ring-2 companions the trigger needs (§3): `agent-kernel-spring`, `agent-store-postgres`, `agent-hitl`, `agent-provider-langchain4j`.
- [ ] Ring-1 **reshape pass** (§5) — `CredibilityTier` resolution (the headline `0.x` decision), `AgentContext.tenantId()`, escalation-rung completeness; each accepted reshape is a new ADR.
- [ ] **`1.0` freeze** (§6) once a 2nd consumer is green and the API has stopped moving.
- [ ] **UCC follow-ups (independent of the trigger, optional):** (a) `agent-eval`'s `EvalRunner` still calls plain `registry.dispatch` — it could route through `SyncOrchestrator` so evals exercise the confidence/abstain gate, but that changes `minConfidence` semantics in UCC fixtures, so it was deliberately left out of the sync slice; (b) UCC's `0.x`→`1.0` upgrade (a separate "U2" PR, §6) lands at UCC's own pace after the freeze.

**Guardrails (unchanged):** ring-1 zero-dep; companions are separate artifacts depending on core, never the
reverse; no commit/push/tag without an explicit ask; the kernel stays `0.x`/SNAPSHOT until §6.

---

## 0. Trigger & guiding principle

R1 is **demand-driven, not scheduled.** It begins the moment **CVVE or CxO** starts building against the kernel — whichever lands first. Until then the kernel sits at the green `0.x` K1 delivered, with UCC consuming it (post-U1).

The phase exists to satisfy the **rule of three**:

- K0/K1 shipped ring-1 shaped by **one** consumer (UCC). That shape is *provisional* — `0.x`, SNAPSHOT, additive-by-default.
- R1 introduces the **second** consumer. Its real needs either **confirm** a ring-1 abstraction (it fits unchanged → evidence it's right) or **reshape** it (it doesn't fit → change it now, while still `0.x` and only two consumers deep).
- Only after that second-consumer pass do we **freeze `1.0`**. The freeze is the *output* of R1, not an input.

> **Why this ordering is the whole point:** the orchestrator and the infra companions (Postgres, HITL, async state machine) are exactly the pieces most likely to be wrong if guessed from UCC alone. Holding them until a structurally different consumer (async multi-tenant SaaS, or streaming chat) forces their real shape is the cheapest way to avoid a bad `1.0`.

---

## 1. What R1 delivers

1. **The ring-2 companion modules** the triggering consumer needs (separate artifacts, opt-in deps — never bolted onto ring-1). §3.
2. **The shared orchestrator(s)** — the assembled pipeline(s) deferred from K1, now shaped by ≥2 real orchestration models. §4.
3. **A deliberate ring-1 reshape pass** — promote/adjust the abstractions the 2nd consumer stressed; resolve the `0.x` escape hatches (notably `CredibilityTier`). §5.
4. **`1.0` freeze** — once the 2nd consumer is green on the kernel and the API has stopped moving. §6.

Ring-1 stays **zero runtime deps** throughout (ADR-0001); everything new in R1 is ring-2 or a ring-1 *interface* refinement.

---

## 2. The two candidate triggers (module sets differ by who lands first)

R1's concrete content depends on which app triggers it. Both are mapped so the plan is ready either way.

### 2.1 If CVVE triggers (async, multi-tenant SaaS, HITL)

Structurally the **most different** from UCC — exercises async orchestration, tenancy, durable audit, and human handoff. Drives:

| Need | Companion module | Shapes in ring-1 |
|---|---|---|
| Async **state-machine** orchestration (intake→queue→OCR→validate→HITL→terminal; idempotency, retries, dead-letter, webhooks) | **`agent-orchestration`** (async variant) | confirms/extends `Capability`, `EscalationPolicy`, `Deadline`; proves the orchestrator seam isn't sync-only |
| **Human-in-the-loop** review below `minConfidence` | **`agent-hitl`** | promotes the `HumanHandoff(queue)` rung from a stub to a real, wired terminal rung |
| **Immutable audit ledger** (durable, per-tenant) | **`agent-store-postgres`** | proves `AuditSink`/`AgentEvent` are sufficient as a durable contract; ADR-0008 (keys-only) upheld at the durable tier |
| **Multi-tenant** isolation | **`agent-kernel-spring`** (+ tenancy wiring) | confirms `AgentContext.tenantId()` is the right opaque seam |
| Schema-driven validation + async OCR confidence | (CVVE ring-3 bindings) | confirms `Tool`/`ToolResult`/`ConfidenceEstimator` generalize off UCC's SQL world |

### 2.2 If CxO triggers (streaming chat, RAG, credibility-tiered provenance)

Exercises **streaming**, real provenance vocabularies, and a different provider. Drives:

| Need | Companion module | Shapes in ring-1 |
|---|---|---|
| **Streaming** conversational tool-calling (SSE, first token <5s) + scenario recompute | **`agent-orchestration`** (streaming variant) | proves the orchestrator must support incremental/streamed emission, not just request→result |
| **Gemini behind LangChain4j**, provider-swappable | **`agent-provider-langchain4j`** | confirms `ModelProvider` SPI generalizes past Ollama; second provider validates the abstraction |
| **pgvector RAG** ("RAG never supplies numbers") | **`agent-store-postgres`** (vector Retriever) | confirms `Retriever`/`ContextBudget` are sufficient; embedding retrievers stay ring-2 as designed |
| **Credibility-tiered provenance + reconciliation** | (CxO ring-3 + ring-1 refinement) | **the decisive test for `CredibilityTier`**: does CxO's real tier vocabulary fit the enum + `tierLabel` escape hatch, or force promotion to an app-extensible interface? (§5) |
| Spring Modulith wiring | **`agent-kernel-spring`** | confirms the Spring companion serves a non-CVVE Spring shape too |

### 2.3 If both land close together

Build the **union** of the modules above, but sequence by whoever is first to a green build — and treat the **second** of the two as the rule-of-three confirmation for the shared pieces (`agent-orchestration`, `agent-kernel-spring`, `agent-store-postgres` all end up exercised by two non-UCC apps, which is the strongest possible `1.0` evidence).

---

## 3. Ring-2 companion modules (admission rules)

Each companion is a **separate Maven artifact** under `com.gamma.agentkernel`, depending on `agent-kernel-core` (+ its own infra deps), **never** the reverse. Ring-1 takes no dep on any of them.

| Module | Purpose | Heavy deps (ring-2 only) |
|---|---|---|
| `agent-orchestration` | the assembled pipeline(s): run-capability → estimate-confidence → escalate → ground → audit; sync + async/streaming variants | none required (pure) — or minimal |
| `agent-kernel-spring` | Spring Boot / Modulith auto-config: register capabilities/tools, wire context, expose endpoints | `spring-context`/`spring-boot` |
| `agent-store-postgres` | durable `AuditSink` (CVVE ledger) + pgvector `Retriever` (CxO RAG) | `postgresql`, jdbc/jpa, pgvector |
| `agent-hitl` | the `HumanHandoff` rung wired to a real review queue | queue/store deps as needed |
| `agent-provider-langchain4j` | LangChain4j-backed `ModelProvider` (Gemini etc.) | `langchain4j` + provider |

**Admission rule (ADR-0002 / §13):** a module is built only when the triggering consumer actually needs it. Don't pre-build the others speculatively — that reintroduces exactly the one-consumer guessing R1 exists to avoid.

---

## 4. The shared orchestrator (deferred from K1 — assembled here)

K1 shipped the **ingredients** (`EscalationPolicy`, `ConfidenceEstimator`, `GroundingGuard`, `Deadline`, `RepairLoop`, `AuditSink`) + `CapabilityRegistry.dispatch()`. R1 assembles them into an actual pipeline — now informed by **two** orchestration models, not one:

- **Sync orchestrator** (UCC/CxO-non-streaming shape): request → `effectiveTier` attempt → estimate → escalate(rungs) → ground → audit → result. This is the pipeline UCC currently hand-rolls in its module; R1 ships it shared, and UCC swaps its in-module dispatch for it (at UCC's pace, §6).
  > **DELIVERED (UCC slice, 2026-06-05) — ADR-0009.** `SyncOrchestrator` shipped in the new pure ring-2 module `agent-orchestration` (resolve → escalate(estimate, abstain) → emit one `AgentCompleted`); **UCC consumes it now** (didn't wait for §6 — the extraction was behavior-preserving, full reactor green unmodified). **No ring-1 change was needed** — the K1 seam is confirmed for the sync case. Grounding stays where capabilities invoke it (not a top-level orchestrator step), matching UCC's current behavior. The **async** + **streaming** variants below remain trigger-gated.
- **Async / state-machine orchestrator** (CVVE shape) and/or **streaming orchestrator** (CxO shape): same ingredients, different emission/lifecycle. These live in `agent-orchestration` as distinct entry points over the **same** ring-1 primitives.

The design test: if all variants compose from the *same* ring-1 ingredients with no ring-1 changes, the K1 seam was right. Any ingredient that **can't** serve a second orchestration model is the reshape signal for §5.

---

## 5. Ring-1 reshape pass + escape-hatch resolution

Before freezing, do a deliberate pass over the abstractions the 2nd consumer stressed:

- **`CredibilityTier` (the headline `0.x` decision):** K1 shipped enum + `Evidence.tierLabel` String escape hatch, explicitly "revisit at `1.0` once a 2nd consumer exercises real tier vocabularies." CxO's credibility-tiered provenance is that exercise. Decide: **keep enum + label** (if the label hatch proved sufficient) or **promote to an app-extensible interface** (if CxO's vocabulary genuinely doesn't fit). Record the outcome as a new ADR superseding the relevant part of ADR-0004.
- **`AgentContext.tenantId()`** — confirm the opaque-tenant seam survives CVVE's real multi-tenancy, or refine.
- **Escalation rungs** — `HumanHandoff(queue)` moves from stub to wired (CVVE); confirm the sealed set is complete.
- **Orchestrator seam** — fold back any ingredient that couldn't serve the 2nd orchestration model (§4).
- **`ModelProvider` / `Retriever`** — confirm a 2nd provider (LangChain4j/Gemini) and a vector retriever fit the SPIs unchanged.

Each accepted reshape is an **ADR** (immutable-supersede convention); additive changes ship as `0.x` minors; the breaking ones are batched into the `1.0` cut.

---

## 6. The `1.0` freeze

Freeze criteria — **all** must hold:

- [ ] The triggering 2nd consumer (CVVE or CxO) has a **green build** on the kernel.
- [ ] The reshape pass (§5) is done; `CredibilityTier`'s fate is **decided** and ADR'd.
- [ ] The API has **stopped moving** (no breaking change in the last consumer-driven iteration).
- [ ] Each ring-1 abstraction is exercised by **≥2 apps** (the rule-of-three discharge).
- [ ] Companion modules the 2nd consumer needs are published and green.
- [ ] Migration notes written for `0.x`→`1.0` (for UCC's later upgrade).

Then: cut **`agent-kernel 1.0.0`**, tagged, published to GitHub Packages. SemVer now binds — breaking SPI changes become majors; SPI evolution stays additive-by-default.

**UCC's path to `1.0`:** UCC stays on its pinned `0.x` through its 4.0 and upgrades to `1.0` **deliberately, at its own pace** — a separate UCC PR (call it U2 if/when scoped), not part of R1. If R1 shipped the shared sync orchestrator, that UCC upgrade is also when UCC swaps its hand-rolled dispatch for the shared one.

---

## 7. Sequenced tasks (conditional on the trigger)

1. **Identify the trigger** (CVVE or CxO) and select its module set (§2). Don't build the other's modules yet.
2. Stand up `agent-kernel-spring` if the trigger is Spring-based (both candidates are) — the wiring substrate the others plug into.
3. Build the triggering consumer's companions (orchestration variant + store/hitl/provider as needed), each its own artifact, each green in the kernel's CI with **no app on the classpath** for the pure ones.
4. Assemble the orchestrator(s) in `agent-orchestration` over the K1 ingredients (§4).
5. Run the reshape pass (§5); land additive changes as `0.x` minors; ADR each accepted reshape; **resolve `CredibilityTier`**.
6. Get the 2nd consumer to a **green build**; iterate until the API stops moving.
7. Verify the `1.0` freeze checklist (§6); write `0.x`→`1.0` migration notes.
8. Cut + publish **`1.0.0`**. → **R1 done.**

---

## 8. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Building companions speculatively (the other consumer's, or all five) | Admission rule (§3): build only what the trigger needs; the 2nd-of-two becomes confirmation, not pre-work. |
| Freezing `1.0` too early (API still wrong) | The freeze is gated on the API *stopping* + every abstraction at ≥2 apps — not on a date. |
| Orchestrator can't serve the 2nd model | That's the design signal, not a failure: fold the gap back into ring-1 (§5) before `1.0`. |
| Ring-1 dep creep via a companion | CI guard (from K0) still fails any compile/runtime dep added to `agent-kernel-core`; companions carry their own deps in isolation. |
| `CredibilityTier` decision slips past `1.0` | It's an explicit freeze gate (§6) — `1.0` cannot cut with the escape hatch unresolved. |
| UCC forced to upgrade on R1's schedule | Decoupled: UCC stays on pinned `0.x`; `1.0` upgrade is a separate, paced UCC PR (U2). |

---

## 9. Explicitly NOT in R1

- **A 3rd consumer's needs** — R1 is the 2nd-consumer pass; the 3rd app (whichever of CVVE/CxO didn't trigger) either confirms `1.0` or drives a `1.x` minor, not R1.
- **MCP / ADK transport** — still deferred (ADR-0003); tools stay transport-free until there's real demand.
- **Forcing UCC onto `1.0`** — UCC upgrades at its own pace (§6).
- **Restructuring ring-1 beyond what the 2nd consumer actually stresses** — no speculative abstraction; rule of three governs.
