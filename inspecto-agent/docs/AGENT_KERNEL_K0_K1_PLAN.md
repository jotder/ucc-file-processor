# agent-kernel — K0 + K1 Implementation Plan

**Status:** **Decisions locked (2026-06-04)** · no repo created yet · **UCC is not modified by K0/K1** (it keeps its own copies until the later U-phases).
**Date:** 2026-06-04
**Locked decisions:** (1) coordinates `com.gamma.agentkernel:agent-kernel-core`; (2) **Java 25** bytecode floor — **UCC moves 24→25** to consume, CVVE/CxO start at 25; (3) **GitHub Packages + GitHub Actions**; (4) `CredibilityTier` = enum + String escape hatch, revisit at `1.0`; (5) **sync orchestrator deferred to R1** — K1 is pure ring-1 types/interfaces + ring-2 provider + eval harness only.
**Companion to:** `AGENT_ARCHITECTURE.md` (§6 abstractions, §10 rings, §12 phases, §13 governance).
**Covers:** **K0** (bootstrap the repo) and **K1** (build ring-1 core + the two ring-2 modules UCC needs). Defers CVVE/CxO companions and the `1.0` freeze to the reuse phase (R1).

---

## 0. Scope & sequencing rules

- K0/K1 happen entirely in a **new `agent-kernel` repo**. They are **purely additive** — nothing in `ucc-file-processor` changes. UCC's `file-processor-agent` retains its current classes; it only switches to depending on the kernel at **U1** (a later phase, separate plan).
- This avoids any broken intermediate state: the kernel reaches a green, self-contained `0.x` *before* UCC touches it.
- Build only what UCC needs to consume at U1: **ring-1 core**, **`agent-provider-ollama`**, **`agent-eval`**. The Spring/Postgres/HITL/orchestration companions are R1 work, driven by the first CVVE/CxO build.

---

## 1. Baseline decisions (locked)

| Item | Decision | Rationale |
|---|---|---|
| Repo name | `agent-kernel` | matches the doc |
| groupId | `com.gamma.agentkernel` | distinct from `com.gamma.inspector` (UCC) |
| Parent artifact | `agent-kernel-parent` (pom) | reactor aggregator |
| Ring-1 artifact | `agent-kernel-core` | the pure core |
| Initial version | `0.1.0-SNAPSHOT` | unstable until 2nd consumer (rule of three) |
| **Java target** | **25** | The bytecode floor; every consumer must run JVM ≥25. CVVE/CxO are greenfield at 25; **UCC moves 24→25** as part of consuming the kernel (a one-time bump, recorded as a U-phase prerequisite). No down-level review needed — ported UCC (24) code compiles cleanly at `release=25`. |
| **Ring-1 runtime deps** | **none** ✅ | `ModelProvider` is our own interface — `langchain4j` lives only in ring-2 providers. Ring-1 = pure JDK. Maximally embeddable. |
| Ring-1 test deps | `junit-jupiter` 5.10.2 | matches UCC |
| `agent-provider-ollama` deps | `langchain4j-ollama` 1.15.1 | ported from UCC |
| `agent-eval` deps | `agent-kernel-core`, `jackson-databind` 2.18.3, junit | fixtures-as-JSON; jackson OK here (not ring-1) |
| Build | Maven + wrapper (`mvnw`) | reproducible |
| CI | **GitHub Actions** | tag-driven release |
| Registry | **GitHub Packages** | `maven.pkg.github.com/<org>/agent-kernel`; CI publishes on tag with the built-in `GITHUB_TOKEN` |

✅ = refinement vs. the architecture doc ("langchain4j-core only" → "zero deps" for ring-1).

> **Java-25-floor consequence (load-bearing):** UCC currently targets Java 24. Consuming the kernel therefore requires UCC to move to **Java 25 first** — this becomes the first task of **U0** (the UCC-migration plan), gated *before* U1 wires UCC to the kernel. K0/K1 themselves are unaffected (the kernel repo is born at 25).

---

## 2. K0 — Bootstrap the repo

### 2.1 Repository layout

```
agent-kernel/
├── pom.xml                         # parent (packaging=pom), <modules>
├── mvnw, mvnw.cmd, .mvn/           # maven wrapper
├── .gitignore
├── README.md                       # what it is, how to depend on it, build/release
├── CHANGELOG.md                    # Keep-a-Changelog; 0.1.0 entry
├── CODEOWNERS                      # kernel maintainers
├── LICENSE                         # internal/proprietary header
├── .github/workflows/ci.yml        # build+test on PR; deploy on tag
├── docs/
│   ├── ARCHITECTURE.md             # moved/copied from UCC AGENT_ARCHITECTURE.md
│   └── adr/                        # founding ADR set (0001–0008) — already written, see docs/adr/README.md
├── agent-kernel-core/              # RING 1 — zero runtime deps
│   ├── pom.xml
│   └── src/{main,test}/java/com/gamma/agentkernel/...
├── agent-provider-ollama/          # RING 2
│   ├── pom.xml
│   └── src/{main,test}/java/com/gamma/agentkernel/provider/ollama/...
└── agent-eval/                     # RING 2 (test-jar)
    ├── pom.xml
    └── src/{main,test}/java/com/gamma/agentkernel/eval/...
```

### 2.2 Parent `pom.xml` (key contents)

- `packaging=pom`; `<modules>`: `agent-kernel-core`, `agent-provider-ollama`, `agent-eval`.
- `<properties>`: `maven.compiler.release=25`, UTF-8, dependency versions (langchain4j `1.15.1`, junit `5.10.2`, jackson `2.18.3`).
- `<dependencyManagement>` pins those versions; modules declare deps without versions.
- `<distributionManagement>` → **GitHub Packages**:

```xml
<distributionManagement>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/<org>/agent-kernel</url>
  </repository>
</distributionManagement>
```

GitHub Packages serves both snapshots and releases from the same repository id. Consumers add the same `<url>` as a `<repository>` (plus a `~/.m2/settings.xml` server entry with a PAT carrying `read:packages`).

### 2.3 Module poms

- **`agent-kernel-core`** — **no `<dependencies>` except** `junit-jupiter` (test). This is the enforced "zero runtime deps" property; a CI check (§2.5) fails the build if a compile-scope dep is added.
- **`agent-provider-ollama`** — depends on `agent-kernel-core` + `langchain4j-ollama`.
- **`agent-eval`** — depends on `agent-kernel-core` + `jackson-databind` + junit; produces a normal jar **and** a `test-jar` (so consumers reuse the runner from their test scope).

### 2.4 CI / publish (`.github/workflows/ci.yml`)

```yaml
on: { push: { branches: [main], tags: ['v*'] }, pull_request: {} }
permissions: { contents: read, packages: write }   # packages:write needed for the publish job
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '25', cache: maven }
      - run: ./mvnw -B verify          # unit tests + eval harness, ZERO apps present
  publish:
    needs: build
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '25', cache: maven, server-id: github }
      - run: ./mvnw -B -DskipTests deploy
        env: { GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }} }   # built-in; no extra secret to manage
```

`setup-java` with `server-id: github` writes the `~/.m2/settings.xml` server entry that maps the `github` distributionManagement id to the `GITHUB_TOKEN`. Temurin 25 may need `actions/setup-java` ≥ a version that lists it; if absent, pin the exact Temurin 25 release.

Release flow: merge to `main` → `0.x-SNAPSHOT` available (optional snapshot deploy job); tag `v0.1.0` → release deploy to GitHub Packages. Apps **pin** concrete versions.

### 2.5 Repo hygiene & guards

- `README.md`: one-paragraph "what", a "depend on it" snippet, build/test/release commands.
- A tiny **"no runtime deps in core" CI guard**: `mvn -pl agent-kernel-core dependency:tree` parsed to assert nothing at `compile`/`runtime` scope. Keeps ring-1 pure forever.
- `CODEOWNERS` → kernel maintainers; apps file issues here.

### 2.6 K0 acceptance criteria

- [ ] `./mvnw -B verify` is green on a clean checkout with **no app present**.
- [ ] Three modules build; `agent-kernel-core` has **zero** compile/runtime dependencies (guard passes).
- [ ] Tag → publish path works (a `0.1.0` artifact lands in the registry).
- [ ] `docs/ARCHITECTURE.md` present; ADR folder exists.
- [ ] Package skeleton (§3) compiles (interfaces may be empty/stubbed).

---

## 3. K1 — Ring-1 core (the substance)

### 3.1 Work units: port vs. new

| Unit | Package | Source | Notes |
|---|---|---|---|
| Model layer | `…model` | **port** from UCC `com.gamma.agent.model` | de-UCC names; `generate` returns `ModelResponse` (adds token usage); `AssistProfile`→`ModelProfile`; add `ModelRouter.next(tier)` |
| Repair / deadline | `…reason` | **port** `RepairLoop` + **new** `Deadline` | generalize RepairLoop off any skill |
| Retrieval | `…retrieve` | **port** `DocRetriever` (lexical, no deps) + **new** `Retriever`, `ContextBudget` | embedding retrievers are ring-2 |
| Tool SDK | `…tool` | **new** | `Tool`, `ToolSpec`, `ToolResult`, `Evidence`, `CredibilityTier`, `ToolRegistry`, `GroundingGuard` |
| Agent SDK | `…agent` | **port+generalize** `Skill`/`SkillRegistry`/`AssistContext` → **new** `Capability`/`CapabilityRegistry`/`AgentContext` + **new** `AgentRequest`/`AgentResult` | neutral request/result types |
| Reasoning | `…reason` | **new** | `ConfidenceEstimator`, `EscalationPolicy` + sealed `EscalationRung` |
| Errors | `…error` | **new** | sealed `AgentError` taxonomy |
| Observability | `…observe` | **port+extend** `AuditEvent` → **new** sealed `AgentEvent`, `AuditSink` | consolidate (see 3.2.6) |

### 3.2 Fleshed-out ring-1 interfaces

Package root: `com.gamma.agentkernel`. Illustrative; finalize in code.

#### 3.2.1 `…model`
```java
public enum ModelTier { SMALL, MEDIUM, LARGE }

public record ModelRequest(ModelTier tier, String system, String prompt, boolean jsonFormat) {
    public static ModelRequest text(ModelTier t, String sys, String p) { return new ModelRequest(t, sys, p, false); }
    public static ModelRequest json(ModelTier t, String sys, String p) { return new ModelRequest(t, sys, p, true); }
}

public record ModelResponse(String text, int promptTokens, int completionTokens) {
    public static ModelResponse of(String text) { return new ModelResponse(text, -1, -1); } // usage unknown
}

public interface ModelProvider {
    String name();
    boolean available();
    ModelResponse generate(ModelRequest request);
    static ModelProvider unavailable(String name) { /* available()=false; generate→ModelError */ }
}

public interface ModelRouter {
    ModelProvider providerFor(ModelTier tier);
    java.util.Optional<ModelTier> next(ModelTier tier);   // SMALL→MEDIUM→LARGE→empty  (escalation)
    static ModelRouter of(java.util.Map<ModelTier, ModelProvider> map) { ... }
    static ModelRouter fromProfile(ModelProfile profile) { ... }
}
```

#### 3.2.2 `…tool`
```java
public enum CredibilityTier { AUTHORITATIVE, OFFICIAL, INDICATIVE, DERIVED, USER_PROVIDED, ASSUMPTION }
//   LOCKED for 0.x: enum + a String escape hatch on Evidence (sourceTierLabel) for app-specific tiers
//   that don't fit the enum. Revisit whether to promote it to an app-extensible interface at 1.0,
//   once a 2nd consumer (CVVE/CxO) has exercised real tier vocabularies.

public record Evidence(Object value, CredibilityTier tier, String tierLabel, String sourceRef,
                       double confidence, java.time.Instant observedAt) {
    // tierLabel = the String escape hatch: an app-specific tier name when the enum is too coarse
    // (null/blank ⇒ use `tier`). Lets CVVE/CxO carry richer vocabularies without forking the enum at 0.x.
    public static Evidence of(Object value, CredibilityTier tier, String sourceRef) {
        return new Evidence(value, tier, null, sourceRef, 1.0, null);
    }
}

public record ToolResult(Object value, java.util.List<Evidence> evidence, boolean hasData) {
    public static ToolResult of(Object v, java.util.List<Evidence> e) { return new ToolResult(v, e, true); }
    public static ToolResult noData() { return new ToolResult(null, java.util.List.of(), false); }
}

public record ToolSpec(String id, int version, String description, java.time.Duration maxExecutionTime) {}

public interface Tool {                       // deterministic, transport-free (MCP-ready later, ADR-0004)
    ToolSpec spec();
    ToolResult invoke(java.util.Map<String,Object> args, AgentContext ctx) throws AgentError;
}

public interface ToolRegistry {
    java.util.Optional<Tool> get(String id);
    java.util.Set<String> ids();
}

public interface GroundingGuard {             // generalizes UCC NarrativeGuard + CxO ADR-0001 response check
    record Verdict(boolean grounded, java.util.List<String> ungrounded) {}
    Verdict check(String narration, java.util.List<Evidence> allowed);
}
```

#### 3.2.3 `…agent`
```java
public record AgentRequest(String capabilityId, java.util.Map<String,Object> screenContext,
                           java.util.Map<String,Object> partialInput, String userText) {
    public Object context(String key) { return screenContext.get(key); }
}

public record AgentResult(String capabilityId, int version, Status status, String answer,
        java.util.List<Evidence> evidence, java.util.List<String> links, String rationale,
        double confidence, boolean validated, ModelTier servedBy, String applyVia,
        AgentError.Category error, String message, java.util.Map<String,Object> data) {
    public enum Status { OK, UNSUPPORTED, UNAVAILABLE }
    public static AgentResult unsupported(String id) { ... }
    public static AgentResult unavailable(String id, String msg) { ... }
    // ok(...) / draft(...) factories
}

public record CapabilitySpec(String id, int version, String description, ModelTier defaultTier,
        double confidenceThreshold, java.time.Duration maxExecutionTime,
        java.util.Set<String> requiredContext, java.util.Set<String> allowedTools) {}

public interface Capability {
    CapabilitySpec spec();
    AgentResult run(AgentRequest request, AgentContext ctx) throws AgentError;
}

public interface CapabilityRegistry {
    java.util.Optional<Capability> get(String id);
    java.util.Set<String> ids();
    AgentResult dispatch(AgentRequest req, AgentContext ctx);   // UNSUPPORTED if id absent
}

public interface AgentContext {                 // read-only handle bag; least-privilege
    <T> java.util.Optional<T> handle(Class<T> type);
    ToolRegistry tools();
    ModelRouter models();
    Retriever retriever();
    AuditSink audit();
    java.util.Optional<String> tenantId();      // opaque to the kernel (CVVE sets it; UCC doesn't)
    ModelTier effectiveTier(ModelTier defaultTier);  // ← lets EscalationPolicy raise the tier per attempt
}
```

> **Design point to resolve in K1 (escalation ↔ tier):** capabilities must read their tier via `ctx.effectiveTier(spec.defaultTier())` rather than hard-coding `spec.defaultTier()`, so `EscalationPolicy` can re-run an attempt at a higher tier. This is the one contract authors must follow; document it on `Capability`.

#### 3.2.4 `…reason`
```java
public interface ConfidenceEstimator {
    double estimate(AgentRequest req, AgentResult candidate, AgentContext ctx);
    // composes deterministic signals: validator pass/fail, grounding coverage, oracle agreement,
    // schema conformance, evidence credibility, model self-report.
}

public sealed interface EscalationRung permits EscalationRung.BumpModelTier,
        EscalationRung.HumanHandoff, EscalationRung.Abstain {
    record BumpModelTier() implements EscalationRung {}
    record HumanHandoff(String queue) implements EscalationRung {}     // CVVE wires a real queue
    record Abstain() implements EscalationRung {}                      // UCC/CxO default
}

public final class EscalationPolicy {
    public EscalationPolicy(java.util.List<EscalationRung> rungs) { ... }
    public AgentResult run(Capability cap, AgentRequest req, AgentContext ctx, ConfidenceEstimator est) {
        // attempt @ effectiveTier → estimate → if ≥ threshold return;
        // else walk rungs: BumpModelTier (next tier, re-attempt) / HumanHandoff (park) / Abstain (UNAVAILABLE)
    }
}

public final class Deadline {                  // enforces CapabilitySpec.maxExecutionTime
    public static <T> T call(java.time.Duration limit, java.util.concurrent.Callable<T> task) throws AgentError;
}

public final class RepairLoop { /* generate → validate → repair, N rounds; ported & generalized */ }
```

#### 3.2.5 `…error`
```java
public sealed interface AgentError permits ValidationError, AuthorizationError,
        ToolExecutionError, ModelError, SystemError {
    enum Category { VALIDATION, AUTHORIZATION, TOOL_EXECUTION, MODEL, SYSTEM }
    Category category();
}
public final class ValidationError extends RuntimeException implements AgentError { ... } // etc. for each
```
Mapping to wire status (applied by the orchestrator/registry): `VALIDATION/MODEL/TOOL_EXECUTION/SYSTEM → UNAVAILABLE`; unknown capability → `UNSUPPORTED`; `AUTHORIZATION` → app-defined (UCC: UNAVAILABLE).

#### 3.2.6 `…observe`
```java
public sealed interface AgentEvent permits AgentStarted, AgentCompleted, AgentFailed,
        ModelCalled, ToolCalled, ToolCompleted {
    String capabilityId(); long epochMillis();
}
// AgentCompleted carries the rich summary (consolidating the old AuditEvent):
public record AgentCompleted(String capabilityId, long epochMillis, AgentResult.Status status,
        int evidenceCount, long durationMs, java.util.Set<String> contextKeys, ModelTier servedBy,
        boolean modelInvoked, int repairRounds, double confidence,
        int promptTokens, int completionTokens) implements AgentEvent {}

public interface AuditSink { void emit(AgentEvent e); }
public final class RingBufferAuditSink implements AuditSink {   // default; UCC's behavior
    public RingBufferAuditSink(int capacity) { ... }
    public java.util.List<AgentEvent> recent(int n) { ... }
}
```
> **Consolidation note:** the doc listed both `AgentEvent` *and* a separate `AuditEvent` record. K1 merges them — the summary fields live on `AgentCompleted`. CVVE's durable ledger is just another `AuditSink` (ring-2 `agent-store-postgres`); UCC keeps `RingBufferAuditSink`. **Keys/summaries/provenance only — never data-plane values.**

#### 3.2.7 `…retrieve`
```java
public record ContextBudget(int requestTokens, int retrievedTokens, int instructionTokens) {
    public static ContextBudget standard(int total) {   // ~10 / 70 / 20
        return new ContextBudget(total/10, total*7/10, total/5);
    }
}
public interface Retriever {
    java.util.List<Evidence> retrieve(String query, ContextBudget budget);  // qualitative grounding only
    Retriever NONE = (q, b) -> java.util.List.of();
}
public final class DocRetriever implements Retriever { /* ported lexical retriever; no deps */ }
```

### 3.3 Eval harness shape (`agent-eval`)

```java
public record EvalCase(String name, String capabilityId, AgentRequest input, Expect expect) {
    public record Expect(AgentResult.Status status, java.util.Set<String> requiredDataKeys,
        java.util.Set<String> requiredEvidenceRefs, Boolean mustValidate,
        Double minConfidence, Boolean mustAbstainWhenNoData) {}
}

public final class FakeModelProvider implements ModelProvider {   // deterministic; no Ollama in CI
    public static Builder builder() { ... }                       // script: prompt-predicate → ModelResponse
    public boolean available() { return true; }
}

public record EvalReport(int total, int passed, java.util.List<Failure> failures) {
    public record Failure(String caseName, String reason) {}
    public boolean allPassed() { return failures.isEmpty(); }
}

public final class EvalRunner {
    public EvalReport run(CapabilityRegistry reg, AgentContext ctx, java.util.List<EvalCase> cases) { ... }
}

public final class EvalCaseLoader {               // fixtures as classpath JSON (jackson allowed here)
    public static java.util.List<EvalCase> fromClasspath(String dir) { ... }
}
// JUnit glue: Evals.asTests(report) → Stream<DynamicTest>  (consumers use @TestFactory)
```

- **Fixture format:** JSON files on the classpath (`/eval/<capability>/*.json`), one `Expect` per case. Neutral (no UCC `.toon` dependency in the kernel); UCC/CVVE/CxO each ship their own fixtures.
- **Self-test:** ship a couple of trivial fixtures + a dummy `Capability` in `agent-eval`'s own tests so the harness is exercised in the kernel's CI with zero apps.

### 3.4 Internal boundary discipline

- No package cycles. Optional: an **ArchUnit** test (test scope only) asserting `…tool`/`…model`/`…error`/`…observe`/`…retrieve` don't depend on `…agent`/`…reason` (inner-to-outer direction). Lightweight; recommended but not blocking.
- Do **not** add `module-info.java` at K1 (keeps classpath consumers like UCC friction-free); revisit JPMS at `1.0` if desired.

### 3.5 Testing & coverage targets

- Per-type unit tests; `FakeModelProvider`-driven `EscalationPolicy`/`RepairLoop`/`Deadline` tests (deterministic, fast).
- `EscalationPolicy` tests must cover each rung: tier-bump succeeds, human-handoff parks, abstain returns `UNAVAILABLE`.
- `GroundingGuard` tests: a figure absent from `evidence` is flagged ungrounded.
- Target ≥ the UCC agent module's current line coverage on ported code; new code ≥ 80%.

### 3.6 K1 acceptance criteria

- [ ] All ring-1 packages implemented; `agent-kernel-core` still **zero runtime deps**.
- [ ] `agent-provider-ollama` ports `OllamaModelProvider` + `ModelProfile`; implements `ModelProvider`.
- [ ] `agent-eval` runner + `FakeModelProvider` + self-test fixtures green.
- [ ] `./mvnw -B verify` green **with no app on the classpath**.
- [ ] Escalation ↔ tier contract (`effectiveTier`) documented on `Capability`.
- [ ] Tag `v0.1.0`; artifacts published; CHANGELOG updated.

---

## 4. Sequenced task list

**K0** (small, mostly scaffolding):
1. Create repo + wrapper + `.gitignore` + LICENSE/README/CHANGELOG/CODEOWNERS.
2. Parent pom (modules, properties, depMgmt, distributionManagement) + 3 module poms.
3. CI workflow + the "no-runtime-deps-in-core" guard.
4. Empty package skeleton that compiles; move `ARCHITECTURE.md`.
5. Verify green build + tag→publish dry run. → **K0 done.**

**K1** (ordered to keep the build green at each step):
6. `…model` (port) + `agent-provider-ollama` (port). ← most directly portable; gives a working provider early.
7. `…tool` (Evidence/ToolResult/Tool/Registry/GroundingGuard) — new, no deps.
8. `…error` taxonomy — new.
9. `…agent` (AgentRequest/Result/CapabilitySpec/Capability/Registry/AgentContext) — generalize from Skill.
10. `…reason` (ConfidenceEstimator, EscalationPolicy+rungs, Deadline, RepairLoop port) — wire the escalation↔tier contract.
11. `…observe` (AgentEvent/AuditSink/RingBufferAuditSink) — consolidate AuditEvent.
12. `…retrieve` (Retriever/ContextBudget/DocRetriever port).
13. `agent-eval` (EvalCase/Expect/FakeModelProvider/EvalRunner/Loader + self-tests).
14. Coverage pass; ArchUnit boundary test (optional); docs; tag `v0.1.0`. → **K1 done.**

---

## 5. Explicitly NOT in K0/K1 (later phases)

- `agent-kernel-spring`, `agent-store-postgres`, `agent-hitl`, `agent-orchestration`, `agent-provider-langchain4j` → **R1** (detailed in [`AGENT_KERNEL_R1_PLAN.md`](AGENT_KERNEL_R1_PLAN.md)), driven by the first CVVE/CxO build.
- **The sync orchestrator** (run-capability → estimate-confidence → escalate → ground → audit pipeline) → **deferred to R1** (locked). K1 ships the *ingredients* — `EscalationPolicy`, `ConfidenceEstimator`, `GroundingGuard`, `Deadline`, `RepairLoop`, `AuditSink` — and `CapabilityRegistry.dispatch()` for plain dispatch, but **not** the assembled pipeline. UCC keeps running its own in-module orchestrator until R1 lands the shared one (then U-phase swaps it in). Rationale: the orchestrator is exactly the part most likely to be reshaped by the 2nd consumer (async/state-machine for CVVE, streaming for CxO), so freezing it on UCC alone is premature.
- Any change to `ucc-file-processor` → **U0/U1** (separate plan: [`AGENT_KERNEL_U0_U1_PLAN.md`](AGENT_KERNEL_U0_U1_PLAN.md)). **U0 includes the Java 24→25 bump** (kernel-floor prerequisite).
- Kernel `1.0` API freeze → after a 2nd consumer reshapes the API.

---

## 6. Risks & mitigations

| Risk | Mitigation |
|---|---|
| **Premature abstraction** (kernel shaped by UCC alone) | Keep `0.x`/SNAPSHOT; don't freeze; let the 2nd consumer reshape before `1.0`. The orchestrator — the most consumer-specific piece — is explicitly held out of K1 for this reason. |
| **UCC blocked until it moves to Java 25** | The 24→25 bump is the first task of U0, sequenced *before* any kernel wiring. Independent of K0/K1, which are born at 25. Low risk: 25 is the current LTS and 24→25 is a minor source bump. |
| Ring-1 dep creep | CI guard fails the build on any compile/runtime dep in core. |
| Escalation/tier coupling leaks into every capability | Single documented contract (`effectiveTier`); covered by tests. |
| Cross-repo dev friction (UCC needs unreleased kernel) | SNAPSHOT publishing + `mvn install` for local loops; UCC pins versions. |
| Registry/access not ready | K0 can run fully (build+test) without a registry; only the publish job needs it. |

---

## 7. Decisions — locked (2026-06-04)

| # | Decision | Resolution |
|---|---|---|
| 1 | groupId / coordinates | ✅ `com.gamma.agentkernel:agent-kernel-core` |
| 2 | Java floor | ✅ **25** — UCC bumps 24→25 (U0 prerequisite); CVVE/CxO greenfield at 25 |
| 3 | Registry | ✅ **GitHub Packages** (`maven.pkg.github.com/<org>/agent-kernel`) |
| 4 | Git host / CI | ✅ **GitHub Actions**, tag-driven release with built-in `GITHUB_TOKEN` |
| 5 | `CredibilityTier` | ✅ enum + `Evidence.tierLabel` String escape hatch; revisit promotion to interface at `1.0` |
| 6 | Sync orchestrator | ✅ **deferred to R1** — K1 ships ingredients + `dispatch()`, not the assembled pipeline |

**Remaining input needed before K0 can start:** only the GitHub **`<org>`** (owner of the `agent-kernel` repo) to fill into `distributionManagement` and the consumer repository URLs. Everything else is pinned.
```
