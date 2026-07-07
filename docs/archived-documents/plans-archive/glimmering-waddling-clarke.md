# R1 (first slice) — extract the shared **sync orchestrator** into `agent-kernel`

## Context

UCC 4.0 shipped; the kernel sits at green `0.1.0-SNAPSHOT` consumed by `file-processor-agent`. The
roadmap's next phase is **R1** (`AGENT_KERNEL_R1_PLAN.md`). R1 is *deliberately* demand-driven — most of
it (`agent-kernel-spring`, `agent-store-postgres`, `agent-hitl`, `agent-provider-langchain4j`, the
async/streaming orchestrators, and the `CredibilityTier` resolution) is shaped by the **2nd consumer**
(CVVE or CxO), neither of which has started. Building those from UCC alone is the exact speculative
guessing R1 exists to avoid (R1 §8, first risk). The user has chosen to **open R1 now**, so this plan
takes the **one slice of R1 that is buildable from UCC without guessing**: the **shared *synchronous*
orchestrator** (R1 §4).

Why this slice is non-speculative: UCC *already hand-rolls* this exact pipeline in
`UccAssistAgent.assist()` — resolve capability → `EscalationPolicy.run(estimator)` → emit one
`AgentCompleted` → (map to wire). K1 shipped the *ingredients* (`EscalationPolicy`,
`ConfidenceEstimator`, `Deadline`, `RepairLoop`, `GroundingGuard`, `AuditSink`) +
`CapabilityRegistry.dispatch()` but explicitly **deferred the assembled pipeline to R1**
(`CapabilityRegistry.java:12`). R1 §4 says verbatim: *"This is the pipeline UCC currently hand-rolls in
its module; R1 ships it shared, and UCC swaps its in-module dispatch for it."* Extracting it is therefore
a behavior-preserving refactor that **proves the K1 seam** (the design test of R1 §4: do the ingredients
compose into an orchestrator with **no ring-1 change?**), not a new abstraction guess.

Intended outcome: a new ring-2 module `agent-orchestration` housing a `SyncOrchestrator`; UCC consumes it
and deletes its hand-rolled dispatch; full reactor stays green CPU-only; **ring-1 unchanged; no `1.0`
freeze; everything trigger-specific stays deferred.**

## Scope

**In:** new ring-2 kernel module `agent-orchestration` + `SyncOrchestrator` (sync variant only); UCC
`UccAssistAgent` consumes it; an ADR + R1-plan/`CapabilityRegistry` comment reconciliation; tests both
sides; full reactor green locally.

**Out (deferred — trigger-driven, per R1 §2/§5/§6):** async & streaming orchestrator variants; all other
ring-2 companions (spring/postgres/hitl/langchain4j); the `CredibilityTier` / `tenantId` / escalation-rung
**ring-1 reshape pass**; switching `agent-eval`'s `EvalRunner` from plain `dispatch` to the orchestrator
(would ripple into UCC golden fixtures — left as-is); any `1.0` freeze; **any UCC version cut** (this lands
as internal commits on `4.x`; it ships whenever the next UCC release is independently cut). No ETL/data-path
change. No `AssistRequest`/`AssistResult` wire change.

## Design

### A. Kernel — new ring-2 module `agent-orchestration` (pure, zero infra deps)
Mirror the `agent-eval` module shape (parent `agent-kernel-parent:0.1.0-SNAPSHOT`, depends only on
`agent-kernel-core` + test-scope JUnit). Register in parent `pom.xml` `<modules>` and add a
`<dependencyManagement>` entry so UCC references it without a version. Stays at the kernel's existing
`0.1.0-SNAPSHOT` — **no version bump.** Optionally add an enforcer `bannedDependencies` allowing only
`agent-kernel-core` (matches ring-1's hygiene; nice-to-have, not required).

- **`com.gamma.agentkernel.orchestrate.SyncOrchestrator`** (new package `orchestrate`):
  ```java
  public final class SyncOrchestrator {
      public SyncOrchestrator(CapabilityRegistry registry,
                              ConfidenceEstimator estimator,
                              EscalationPolicy escalation) { /* null-checks */ }

      public AgentResult run(AgentRequest request, AgentContext ctx) {
          long t0 = System.nanoTime();
          Capability cap = registry.get(request.capabilityId()).orElse(null);
          AgentResult result = (cap == null)
                  ? AgentResult.unsupported(request.capabilityId())
                  : escalation.run(cap, request, ctx, estimator);
          ctx.audit().emit(completed(request, result, t0));   // one event per call
          return result;
      }
  }
  ```
  This reproduces `UccAssistAgent.assist()`'s core **exactly** (resolve → escalate → audit), composing
  only existing K1 ingredients — **no ring-1 type changes**. The `completed(...)` helper builds the
  `AgentCompleted` from standard fields (status, evidence count, `screenContext.keySet()`, `servedBy`,
  `servedBy()!=null`, confidence) and reads the documented `data["repaired"]` Boolean convention →
  `repairRounds` 0/1, tokens 0 — byte-identical to UCC's current event. The wire mapping (`toWire`), the
  registry-null short-circuit, the `RuntimeException`→`unavailable` guard, and human-readable logging
  **stay UCC-side** (they are app/transport concerns, not orchestration).

### B. UCC — `UccAssistAgent` consumes the orchestrator
- Replace the inline `escalation`/`estimator` use with a `SyncOrchestrator` field built from the same
  `CapabilityRegistry`, `UccConfidenceEstimator`, and `EscalationPolicy([Abstain])` it already constructs
  (built in `init()` once `registry` exists).
- `assist()` becomes: keep the `registry==null` short-circuit + the try/catch; inside, build the
  `AgentRequest`, call `orchestrator.run(agentReq, context)`, then `toWire(...)`. The capability-resolve,
  escalation, and audit-emit move **out** of `UccAssistAgent` into the orchestrator.
- **Preserve the INFO log:** wrap the injected `AuditSink` in a thin UCC `LoggingAuditSink` decorator
  (logs the existing `[ASSIST] intent=… status=…` line, then delegates) and pass that wrapped sink into
  `UccAgentContext`. The orchestrator emits via `ctx.audit()` → wrapper logs + forwards to the captured
  sink. Delete the now-dead private `audit(...)` method.
- `agent-eval` `EvalRunner` is **not** rewired (stays on plain `dispatch`) — out of scope.

### C. UCC pom
Add to `file-processor-agent/pom.xml` (version via existing `${agentkernel.version}` = `0.1.0-SNAPSHOT`):
```xml
<dependency>
  <groupId>com.gamma.agentkernel</groupId>
  <artifactId>agent-orchestration</artifactId>
  <version>${agentkernel.version}</version>
</dependency>
```

### D. Docs / ADR (kernel)
- New `docs/adr/adr-0009-sync-orchestrator.md` — the assembled **sync** orchestrator now lives in
  `agent-orchestration`; supersedes the "assembled pipeline deferred to R1" note of ADR-0003 **for the
  sync case only**; async/streaming variants remain deferred.
- Edit the `CapabilityRegistry.java:12` comment ("…deferred to R1") to point at `SyncOrchestrator` for the
  sync assembly while noting async/streaming are still R1-deferred.
- Mark R1 §4 "Sync orchestrator" as **delivered (UCC slice)** in `AGENT_KERNEL_R1_PLAN.md`; leave §2/§3/§5/§6
  intact (still trigger-gated).

## Critical files

**Kernel (`C:\sandbox\agent-kernel`) — new:**
- `agent-orchestration/pom.xml` (mirror `agent-eval/pom.xml`)
- `agent-orchestration/src/main/java/com/gamma/agentkernel/orchestrate/SyncOrchestrator.java`
- `agent-orchestration/src/test/java/.../SyncOrchestratorTest.java`
- `docs/adr/adr-0009-sync-orchestrator.md`

**Kernel — edited:**
- `pom.xml` (add module + dependencyManagement entry)
- `agent-kernel-core/.../agent/CapabilityRegistry.java` (comment only)
- `docs/AGENT_KERNEL_R1_PLAN.md` *(also mirrored in UCC `file-processor-agent/docs/`)* — §4 delivered note

**UCC (`C:\sandbox\ucc-file-processor`) — edited:**
- `file-processor-agent/pom.xml` (add `agent-orchestration` dep)
- `file-processor-agent/.../com/gamma/agent/UccAssistAgent.java` (consume orchestrator; delete `audit(...)`;
  wrap sink)
- `file-processor-agent/.../com/gamma/agent/LoggingAuditSink.java` *(new, tiny)* — logging decorator

**Reused unchanged:** `EscalationPolicy`, `ConfidenceEstimator`/`UccConfidenceEstimator`,
`CapabilityRegistry`, `AgentContext`/`UccAgentContext`, `AgentResult`/`AgentRequest`, `AuditSink`/
`AgentCompleted`, all 7 skills, the wire DTOs + `/assist/*` route.

**Must stay green unmodified (behavior-preservation proof):** `AssistAuditTest`, `AssistEndToEndTest`,
`KpiToSqlEndToEndTest`, `GoldenEvalTest`, `UccConfidenceEstimatorTest`, all skill suites. They drive the
public `agent.assist(...)` and capture `AgentCompleted` through the injected sink — the orchestrator emits
the identical event, so they pass without edits.

## Phases (full reactor green CPU-only at each)
- **R1.1 — Kernel module + orchestrator + tests.** New `agent-orchestration` module, `SyncOrchestrator`,
  `SyncOrchestratorTest` (unknown-capability→UNSUPPORTED + audited; OK≥threshold→returned w/ confidence;
  OK<threshold with `[Abstain]`→UNAVAILABLE; exactly one audit event w/ correct keys — using tiny in-test
  stubs + `AgentContext.builder()`, no model). Kernel reactor green; `mvn install` to local `.m2`.
- **R1.2 — UCC consumes it.** Add the dep; rewire `UccAssistAgent` onto `SyncOrchestrator`; add
  `LoggingAuditSink`; delete dead `audit(...)`. Full UCC reactor green CPU-only **with the named suites
  unmodified** (the behavior-preservation proof).
- **R1.3 — Docs/ADR + memory.** ADR-0009, `CapabilityRegistry` comment, R1-plan §4 note. Update
  `MEMORY.md` + `agent-kernel-u1-progress.md` (R1 first slice landed; what's still deferred). **No release
  cut**; `4.1.0-SNAPSHOT` and kernel `0.1.0-SNAPSHOT` unchanged.

## Verification
- **Per phase, local, CPU-only:** build+install kernel then run the UCC reactor —
  `mvn -f C:\sandbox\agent-kernel\pom.xml install` then
  `mvn -f C:\sandbox\ucc-file-processor\pom.xml test` (JAVA_HOME `C:\.jdks\openjdk-26.0.1`, Maven
  `C:\maven\apache-maven-3.9.16`, `mvn.cmd` from PowerShell). Expect **372 core + 138 agent green**, plus
  the new `SyncOrchestrator` tests in the kernel reactor.
- **Behavior-preservation proof:** the named UCC suites pass **unmodified** — same statuses, same single
  `AgentCompleted` per call, same `[Abstain]` semantics.
- **Boundary law intact:** lean `file-processor` core still has zero `com.gamma.agentkernel` refs (the
  orchestration dep is in `file-processor-agent` only) — UCC CI's existing kernel-free guard still passes.
- **Ring-1 untouched:** `git diff` shows no change under `agent-kernel-core/src` except the
  `CapabilityRegistry` comment; ring-1 zero-dep enforcer still green.
- **CI note (cross-repo):** UCC CI checks out kernel **`main`** and `mvn install`s it from source
  (`.github/workflows/ci.yml:33-41`, no pinned ref). So **CI green requires the kernel module to be on
  `main`** *and* the UCC change on `4.x`. Local verification needs neither push.

## Guardrails / gating
- **No commit/push/tag/release without an explicit ask** — this plan covers *implementation + local green*
  only. Pushing kernel→`main` and UCC→`4.x` (the CI-green prerequisite) is a **separate gated step**.
- Ring-1 stays zero-dep; the new module is ring-2. Dependency direction agent→core only. Lean ETL core
  stays kernel-free. `run-adjustment.bat` never staged. Kernel stays `0.1.0-SNAPSHOT`; no `1.0` freeze.
- This is behavior-preserving and version-neutral; a UCC release cut (and `milestone-verify`) is **not**
  part of this plan and remains gated.
