# UCC 4.x — U0 + U1 Migration Plan (consume `agent-kernel`)

**Status:** **Plan — not started** · depends on K1 (`agent-kernel 0.1.0-SNAPSHOT` published) · branch **`4.x`** of `ucc-file-processor`.
**Date:** 2026-06-04
**Locked context:** UCC assist is **pre-production**, so UCC 4.x may **reshape the `com.gamma.assist` SPI** (real numeric confidence, richer status) — no frozen-adapter constraint. The ETL **engine** (data path) is untouched; the lean ETL **core keeps zero AI dependencies**. Java floor is **25** (UCC bumps 24→25 here). Sync orchestrator is **deferred to R1** — U1 keeps UCC's own in-module dispatch.
**Companion to:** `AGENT_ARCHITECTURE.md` (§3.1 orchestration, §8 responsibility matrix, §12 phases, §13 governance) and `AGENT_KERNEL_K0_K1_PLAN.md` (what the kernel ships).
**Covers:** **U0** (Java 25 bump + the eval safety net) and **U1** (depend on the kernel, migrate skills→capabilities, reshape the assist SPI) → cut **UCC 4.0**. Defers companion modules + `1.0` to **R1**.

---

## 0. Scope & sequencing rules

- **U0 is purely additive and kernel-free.** It bumps the language level and lays a regression net **over the current behavior** — *before* any kernel dependency or SPI reshape. After U0 the suite is still green on the existing `Skill` SPI; nothing has been migrated yet. This is the net U1's risky reshape happens under.
- **U1 is the only phase that touches the SPI.** It adds the kernel dependency, migrates the 7 skills to `Capability`s behind the `AssistAgent` ServiceLoader boundary, reshapes the wire types, and re-greens the suite against the reshaped API — then cuts **UCC 4.0**.
- **The boundary law (the one rule U1 must not break):** the **lean ETL core (`file-processor`) never gains a kernel dependency.** Kernel types (`AgentRequest`/`AgentResult`/`Capability`/`Tool`/…) live **only inside `file-processor-agent`**, behind the `com.gamma.assist.spi.AssistAgent` SPI. The wire records `com.gamma.assist.AssistRequest`/`AssistResult` **stay in the lean core**; `UccAssistAgent` is the adapter that maps wire ⇄ kernel. (§3.2.)
- **Orchestrator stays UCC-side.** Per the locked decision, the kernel ships *ingredients* (escalation, confidence, grounding, deadline, repair, audit) + `CapabilityRegistry.dispatch()`, not an assembled pipeline. U1 wires those ingredients into UCC's existing in-module dispatch; the shared orchestrator arrives at R1 and swaps in later.

---

## 1. Preconditions

| Precondition | State | Notes |
|---|---|---|
| `agent-kernel 0.1.0-SNAPSHOT` published | **K1 deliverable** | U1 pins it; U0 needs nothing from the kernel |
| GitHub `<org>` decided | open (K0 blocker) | U1 adds the GitHub Packages `<repository>` + `settings.xml` server entry to consume |
| UCC suite green on `3.x`/`4.x` HEAD | assumed | U0 starts from a green tree |
| Java 25 toolchain available | required by U0 | Temurin 25; CI runner image must carry it |

---

## 2. U0 — Java 25 bump + the eval net

Two independent workstreams; both are kernel-free and land before U1.

### 2.1 Java 24 → 25 bump (the kernel-floor prerequisite — gates everything)

The kernel's bytecode floor is Java 25 (ADR-0007). UCC targets 24 today, so it **cannot load the kernel jar** until it moves to 25. This is the **first task of U0**, sequenced *before* the eval net so the whole branch builds on 25 from the start.

- Set `maven.compiler.release=25` in the UCC reactor parent pom (all modules — `file-processor`, `file-processor-agent`, plugins, util).
- Bump CI runner + any toolchain config to Temurin 25; update `.mvn`/wrapper if pinned.
- Bump local-dev docs (`README`/contributor notes) to "JDK 25 required".
- **No source rewrite expected:** UCC's existing Java 24 code compiles cleanly at `release=25` (24→25 is a minor, additive language step). Treat any compile error as a real find, not a mechanical bump.
- Run the **full suite** on 25 → green. This is its own commit ("build: target Java 25"), isolated so a toolchain regression is bisectable apart from the eval net.

> **Why first, why isolated:** it's the one change that can break the build for environmental (not logic) reasons. Landing it alone, green, means every later U0/U1 step starts from a known-good 25 baseline.

### 2.2 The eval net — golden fixtures over the **current** SPI (Gap E)

Today UCC has **no capability-level golden-eval harness** — skills are covered by ordinary unit tests but there's no deterministic, fixture-driven regression net pinning each skill's *observable output* end-to-end. U1 reshapes that output (confidence String→double, status, the wire mapping), so we need a net that survives the reshape. U0 builds it **against the current `Skill` SPI with no kernel dependency**, but writes fixtures in a **format that ports to the kernel's `agent-eval` unchanged** (§3.7).

- **Deterministic model.** Stand up a `FakeModelProvider` in UCC **test scope** (a `ModelProvider` that returns scripted `ModelResponse`s by prompt-predicate) so evals never touch Ollama. If a UCC test double already exists, consolidate onto it; otherwise add one — it is thrown away at U1 in favor of the kernel's `FakeModelProvider` (same role).
- **Fixtures as classpath JSON**, one directory per capability: `src/test/resources/eval/<intent>/*.json`. Schema mirrors the kernel's `EvalCase`/`Expect` (intent, screenContext, partialInput, userText → expected status, required data keys, required citation refs, mustValidate, mustAbstainWhenNoData). **Write them in this shape now** so U1's migration is a runner swap, not a fixture rewrite.
- **Coverage — all 7 capabilities**, each with the cases that pin its contract:
  - `explain-entity`, `kpi-to-sql`, `nl-to-schedule`, `suggest-config`, `report-sql`, `report-narrative`, `diagnose-and-alert`.
  - Per capability, at minimum: one happy path (OK + expected data/citations + `validated=true`), one **abstain** path (no data / model unavailable → `UNAVAILABLE`, no invented figures), and one grounding case (a known-good narration passes the guard; a planted ungrounded figure is caught — esp. `report-narrative`/`report-sql`).
- **What U0 fixtures assert vs. defer:** assert `status`, `data` keys, `citations`/refs, `validated`, abstain-on-no-data. **Defer** numeric `minConfidence` assertions — confidence is still the `String` `"local"` until U1 reshapes it to `double`. Add the `minConfidence` assertions in U1 once the field is numeric (this is the one fixture edit U1 makes).
- **Run them as JUnit** (`@TestFactory` over the loaded cases) in the agent module's `mvn verify`. Green = the behavioral baseline is pinned.

### 2.3 U0 acceptance criteria

- [ ] Full UCC reactor builds + tests green on **Java 25** (isolated bump commit).
- [ ] `FakeModelProvider` (test scope) drives evals with **no Ollama**.
- [ ] Golden fixtures exist for **all 7 capabilities** (happy / abstain / grounding), in the kernel-portable JSON shape, green via `@TestFactory`.
- [ ] **Zero kernel dependency** anywhere in UCC (verify `mvn dependency:tree`). U0 changed nothing about the SPI.
- [ ] No change to the ETL engine/data path; no commit/push/tag without explicit ask.

---

## 3. U1 — Consume the kernel & reshape the assist SPI

Everything here lives in **`file-processor-agent`** (the optional AI module) except the deliberate wire reshape in §3.4. Cut **UCC 4.0** at the end.

### 3.1 Add the dependency

- Add the **GitHub Packages** `<repository>` + a `~/.m2/settings.xml` server entry (PAT with `read:packages`) so UCC can resolve the kernel.
- `file-processor-agent` depends on **`agent-kernel-core`**, **`agent-provider-ollama`**, and (test) **`agent-eval`** (jar + `test-jar`), **pinned** to `0.1.0-SNAPSHOT` during the churn, bumped to a tagged version when one exists. The **lean `file-processor` core takes none of these** (§3.2).
- Local dev loop: `mvn install` the kernel to `.m2` for tight iteration; UCC pins a concrete version in PRs.

### 3.2 The boundary law — keep the lean core kernel-free

This is the architectural crux. The split:

```
file-processor (LEAN ETL CORE — zero AI deps, unchanged dependency-wise)
  com.gamma.assist.AssistRequest / AssistResult / Diagnosis   ← wire records, STAY here
  com.gamma.assist.spi.AssistAgent                            ← ServiceLoader SPI, STAYS here
        ▲  (in-process boundary; ETL core sees only these)
        │  implements
file-processor-agent (AI MODULE — depends on agent-kernel)
  UccAssistAgent  ← ADAPTER: AssistRequest → AgentRequest → dispatch → AgentResult → AssistResult
  7 × Capability  (kernel types)        UccAgentContext implements AgentContext
  Tools (ex-oracles)                    GroundingGuard, EscalationPolicy(abstain), AuditSink
```

- **No kernel import ever appears under `com.gamma.assist` in the lean core.** Add a tiny CI guard (mirror the kernel's "no-runtime-deps" check) asserting `file-processor` has **no `com.gamma.agentkernel`** dependency. This makes the boundary law enforceable, not just aspirational.
- `UccAssistAgent.assist(AssistRequest)` becomes the **mapping seam**: build a kernel `AgentRequest` from the wire request, `CapabilityRegistry.dispatch(...)`, map the kernel `AgentResult` back to a wire `AssistResult`. The ETL core and `ControlApi` are unaware the kernel exists.

### 3.3 Migration map (current UCC → kernel)

| Current UCC (file-processor-agent) | Becomes | Where it lives | Notes |
|---|---|---|---|
| `com.gamma.agent.skill.Skill` (×7) | `Capability` impls | agent module | `id/tier/run` → `spec()/run(AgentRequest, AgentContext)`; read tier via `ctx.effectiveTier(spec.defaultTier())` |
| `SkillRegistry` | `CapabilityRegistry` (kernel) | agent module | `dispatch()` keeps the unknown-intent → `UNSUPPORTED` behavior |
| `AssistContext` (record of UCC handles) | `UccAgentContext implements AgentContext` | agent module (ring-3) | typed UCC handles (`catalog/reports/statusStore/configs/docs`) exposed via `handle(Class)`; `tools()/models()/retriever()/audit()` from kernel |
| `NarrativeGuard` (static, `report-narrative`) | `GroundingGuard` impl | agent module | generalize "every figure in narrative appears in report" to the `Verdict check(narration, allowed-evidence)` contract; feeds `RepairLoop` as today |
| SQL/figure oracles (e.g. `SqlOracle`, KPI validators) | `Tool` impls (`ToolResult`+`Evidence`) | agent module | deterministic, transport-free; `Evidence.sourceRef` = catalog node / report anchor (the old `Citation`) |
| `RepairLoop` | kernel `RepairLoop` (ported in K1) | kernel | delete UCC copy; consume kernel's |
| `DocRetriever` | kernel `DocRetriever`/`Retriever` (ported in K1) | kernel | UCC's `DocRetriever.fromEnvironment()` env-glue stays as a ring-3 factory that builds the kernel retriever |
| `com.gamma.agent.model.*` (ModelTier/Request/Provider/Router/OllamaModelProvider/AssistProfile) | kernel `…model` + `agent-provider-ollama` (ported in K1) | kernel | delete UCC copies; `ModelRouter.fromEnvironment()` UCC env-glue → ring-3 factory over kernel `ModelRouter.fromProfile/of` |
| `AuditEvent` + `Consumer<AuditEvent>` sink | `AgentEvent`/`AuditSink` + `RingBufferAuditSink` | kernel + agent module | UCC's injected `Consumer` becomes an `AuditSink`; **keys/summaries only** (ADR-0008) preserved — UCC already does this |
| `Citation` (wire) | stays on `AssistResult`; sourced from `Evidence` | lean core + adapter | the adapter maps `AgentResult.evidence` → wire `Citation`s |

**Env-reading factories** (`ModelRouter.fromEnvironment`, `DocRetriever.fromEnvironment`) are **UCC ring-3 glue**, not kernel API — they read UCC's system properties/env and construct the generic kernel objects. They move into the agent module's wiring, not the kernel.

### 3.4 The assist SPI reshape (the one deliberate breaking change)

On the **wire records in the lean core** (`com.gamma.assist`), gated to the 4.0 major:

- `AssistResult.confidence`: **`String` → `double`** (real numeric confidence from the kernel `ConfidenceEstimator`, replacing the placeholder `"local"`).
- **Richer `Status`** if needed (today `OK/UNSUPPORTED/UNAVAILABLE`; keep unless a real new outcome emerges — don't widen speculatively).
- `ControlApi` continues to **map at the HTTP boundary** (status → HTTP code; serialize the now-numeric confidence). The `/assist/{intent}` route shape and the `assist.read` scope are unchanged.
- The factory methods (`answer/draft/unsupported/unavailable`) update their `confidence` argument type; the adapter (§3.2) supplies the kernel's computed value.

> **Backward-compat guardrail interaction:** `AssistResult`/`AssistRequest` are `@PublicApi(since=3.3.0)`. This reshape is an **intentional** break, legitimate **only** because UCC assist is pre-production and this is the **4.0 major**. The `milestone-verify` backward-compat dimension must be scoped to "everything *except* the deliberately-reshaped `com.gamma.assist` wire types + their `.toon`"; the ETL engine's public API and `.toon` stay byte-stable as always.

### 3.5 Wire real confidence + the abstain rung

- Implement a UCC `ConfidenceEstimator` composing the deterministic signals UCC already has: validator pass/fail, grounding coverage (`GroundingGuard`), oracle agreement, evidence credibility, model self-report.
- Wire an `EscalationPolicy` with the **`Abstain` terminal rung** (UCC's default posture): attempt → estimate → if below `CapabilitySpec.confidenceThreshold`, abstain → `UNAVAILABLE` (no invented answer). UCC does **not** wire `BumpModelTier`/`HumanHandoff` at U1 (single-tier-per-capability, no HITL) — those rungs exist in the kernel for CVVE/CxO.
- This replaces the current implicit "model unavailable → unavailable" with an explicit, tested confidence gate — and the U0 fixtures gain their `minConfidence` assertions here.

### 3.6 The event-driven path stays ring-3 (UCC-side)

`diagnose-and-alert` is **not** a synchronous skill — it's driven by `FailureReactor` subscribing to the `SourceService` event bus, writing to `DiagnosisStore`, backing `GET /assist/diagnoses`. Since the kernel defers async/event orchestration to R1:

- The **diagnosis logic** becomes a `Capability` + `Tool`s (heuristic + model root-cause) like the others.
- The **reactor, executor, store, and event subscription stay in UCC's agent module (ring-3)** — they invoke the capability off the ingest thread, exactly as today. The kernel's failure-event seam (shared with CVVE/CxO) is an R1 concern.
- `auditDiagnosis(...)` keeps emitting keys-only audit (`batchId`/`pipeline`/`severity`) through the new `AuditSink`.

### 3.7 Migrate the eval net onto the kernel harness

- Swap the throwaway U0 runner for the kernel's **`agent-eval`** (`EvalRunner`, `EvalCase`/`Expect`, `EvalCaseLoader.fromClasspath`, `FakeModelProvider`, `Evals.asTests`). The **U0 JSON fixtures carry over unchanged** (they were authored in this shape) — only the loader/runner classes change, and the `minConfidence` assertions get filled in (§3.5).
- Delete UCC's temporary `FakeModelProvider`; use the kernel's.
- Result: the **same fixtures that pinned 3.x behavior now run through the reshaped 4.0 `Capability` SPI** — direct proof the migration preserved observable behavior.

### 3.8 U1 acceptance criteria

- [ ] `file-processor-agent` depends on the kernel; **lean `file-processor` core has zero `com.gamma.agentkernel` deps** (CI guard passes).
- [ ] All 7 skills are `Capability`s; `SkillRegistry`→`CapabilityRegistry`; `AssistContext`→`UccAgentContext implements AgentContext`.
- [ ] `NarrativeGuard`→`GroundingGuard`; figure oracles→`Tool`s; `RepairLoop`/`DocRetriever`/model layer now consumed from the kernel (UCC copies deleted).
- [ ] `AssistResult.confidence` is `double`; `ControlApi` maps at the HTTP boundary; `/assist/*` route + scopes unchanged.
- [ ] `ConfidenceEstimator` + `EscalationPolicy(Abstain)` wired; abstain-below-threshold tested.
- [ ] `diagnose-and-alert` capability runs under UCC's ring-3 `FailureReactor` (event path unchanged); keys-only audit preserved.
- [ ] Eval net runs on the kernel `agent-eval` with the **same fixtures**, now incl. `minConfidence`; **full suite green**.
- [ ] `milestone-verify` run with backward-compat scoped to exclude the intentional `com.gamma.assist` reshape; all other dimensions clean.
- [ ] Cut **UCC 4.0** (only on explicit ask).

---

## 4. Sequenced task list

**U0** (kernel-free; keep the suite green at each step):
1. **Java 24→25 bump** across the reactor + CI; full suite green; isolated commit. ← gates the rest.
2. Add/consolidate a test-scope `FakeModelProvider`; wire `@TestFactory` eval runner (throwaway, kernel-shaped JSON).
3. Author golden fixtures for all 7 capabilities (happy / abstain / grounding); green. → **U0 done.**

**U1** (ordered so the build stays green; the reshape lands behind the green eval net):
4. Add kernel dependency + GitHub Packages repo/settings; pin `0.1.0-SNAPSHOT`.
5. Delete UCC copies of the ported pieces (model layer, `RepairLoop`, `DocRetriever`); consume the kernel's; re-green. ← smallest, most mechanical first.
6. `UccAgentContext implements AgentContext`; figure oracles → `Tool`s; `NarrativeGuard` → `GroundingGuard`.
7. Skills → `Capability`s; `SkillRegistry` → `CapabilityRegistry`; keep `UccAssistAgent` dispatching internally.
8. Reshape the wire `AssistResult.confidence` String→double; map in `ControlApi`; make `UccAssistAgent` the wire⇄kernel adapter.
9. Wire `ConfidenceEstimator` + `EscalationPolicy(Abstain)`; `AuditEvent`→`AgentEvent/AuditSink`.
10. Move `diagnose-and-alert` to a capability under the existing ring-3 `FailureReactor`.
11. Swap the eval net onto kernel `agent-eval`; fill in `minConfidence`; full suite green.
12. `milestone-verify` (backward-compat scoped); docs; cut **UCC 4.0**. → **U1 done.**

---

## 5. Explicitly NOT in U0/U1 (→ R1 / later)

- **The shared sync orchestrator** — UCC keeps its in-module dispatch; swaps to the kernel's assembled pipeline when R1 ships it (proven by a 2nd consumer).
- **`BumpModelTier`/`HumanHandoff` rungs**, multi-tier escalation, HITL — not UCC's posture; kernel provides them for CVVE/CxO.
- **Kernel `1.0` upgrade** — UCC stays on `0.x`/SNAPSHOT through 4.0; upgrades to `1.0` at its own pace post-R1.
- **The shared failure-event seam** (async reactor in the kernel) — UCC's reactor stays ring-3 until R1 generalizes it across CVVE/CxO.
- **Any ETL engine / data-path change** — untouched throughout.

---

## 6. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Java 25 toolchain not on the CI runner | U0 task 1 is isolated and first; a missing toolchain fails fast and visibly before any migration work. |
| Kernel dep leaks into the lean ETL core | CI guard asserts `file-processor` has zero `com.gamma.agentkernel` deps (§3.2); the boundary law is enforced, not trusted. |
| The `AssistResult.confidence` reshape trips the backward-compat guardrail | Intentional 4.0 break; scope `milestone-verify`'s compat dimension to exclude the `com.gamma.assist` reshape; everything else stays byte-stable. |
| Behavior drifts during the skill→capability rewrite | The U0 eval net (same fixtures) runs before and after; any observable change is a failing fixture, not a silent regression. |
| Churning against `0.1.0-SNAPSHOT` while the kernel API still moves | UCC pins the version; `mvn install` for tight loops; kernel API is additive-by-default so a kernel change can't silently break UCC. |
| `diagnose-and-alert`'s async path complicates the "all skills are capabilities" model | Keep the reactor/store/executor in ring-3; only the *logic* becomes a capability — the event machinery is explicitly out of scope until R1. |

---

## 7. Guardrail interactions (standing UCC rules)

- **Lean core:** the *lean ETL core* takes **no new deps** (and specifically no kernel dep) — unchanged. The *agent module* gains the kernel deps (that's its job). The ring-1 kernel itself stays zero-dep (K-phase concern).
- **Backward-compat / `.toon` / byte-identical SQL:** fully upheld for the ETL engine. The **only** sanctioned break is the `com.gamma.assist` wire reshape, justified by pre-production status + the 4.0 major.
- **Tests green before tagging; docs updated; no commit/push/tag without explicit ask.** U0 and U1 each end on a green full suite; 4.0 is cut only when asked.
