# Agent Architecture & Design — Consolidated

**Status:** Draft for review (branch `4.x`)
**Date:** 2026-06-04
**Scope:** Defines a **reusable, framework-agnostic agent core** (a library — working name `agent-kernel`) and the thin **application binding** each agent supplies on top of it. The core is validated against **three** concrete agent applications, not one, so the abstractions are proven by reuse rather than guessed.

**Reference applications driving this design:**
1. **UCC File Processor assist** — embedded ETL co-pilot (this repo, `file-processor-agent`). Synchronous, ServiceLoader, local Ollama, draft-only, abstain-safe, no persistence.
2. **CVVE — Verification & Validation-as-a-Service** (`C:\sandbox\agentic-doc-validation`). Multi-tenant SaaS; async state-machine; schema-driven validation + async OCR; **human-in-the-loop (HITL)** review; immutable audit ledger; Spring Boot + Quarkus agents + Postgres.
3. **Real-Estate CxO Decision-Support agent** (`C:\sandbox\competitive-analysis`). Single LangChain4j agent over a fixed, audited tool set + RAG; **credibility-tiered provenance + reconciliation**; streaming chat; Spring Modulith + Postgres/pgvector + Gemini. Governed by four ADRs (0001–0004).

**Design source:** the "Enterprise Tiny Agent Framework" series (`docs/*.pdf`, Vols 1–7) — notably its **Agent SDK (Vol 3)** and **Tool SDK (Vol 4)** split — reconciled against the CVVE PRD and the CxO ADRs. We adopt **principles**, not anyone's **infrastructure**.

**Standing guardrails.** The *core* takes **no new runtime dependencies** beyond `langchain4j-core` and is **framework-agnostic** — no Spring, no Postgres, no Redis, no Kafka, **no MCP**, no persistence engine. (CVVE and CxO legitimately use Spring/Postgres — but only in *their* binding layer or in optional ring-2 companion modules; see §3.3, §10.) Draft/read-only/abstain-safe posture is the UCC default, not a core mandate. Do not commit/push/tag without explicit ask.

**Decisions locked (2026-06-04).** (1) The kernel lives in **its own repository**, published as a versioned artifact with an **independent SemVer line** — UCC/CVVE/CxO are consumers (§13). (2) We ship a **three-ring structure**: pure core + optional companion modules + per-app bindings (§10). (3) The UCC assist agent is **not yet in production**, so UCC **4.x** is free to **reshape the `com.gamma.assist` SPI** (real numeric confidence, richer status) — no frozen-adapter constraint — while the ETL core keeps **zero AI dependencies**.

---

## 1. Purpose

Build a **solid, reusable framework core** that three (and future) agents share, with a clean seam where each app embeds its specifics. Two deliverables:

1. **Consolidate** the agent design against the guidelines — one architecture, not emergent structure.
2. **Carve out `agent-kernel`** — the parts that are *framework*, not *application* — as a standalone library with zero coupling to any host (ETL, SaaS, or chat). Each app reuses the kernel and supplies only its capabilities, tools, context, and orchestration shell.

The test of the design is **§8 — the kernel-vs-app responsibility matrix across all three apps.** If a concept isn't shared by at least two of them, it does not belong in the kernel.

---

## 2. The shared spine (what all three agents have in common)

Stripped of infrastructure, the three agents are the same machine:

| Universal principle | UCC | CVVE | CxO | → Kernel primitive |
|---|---|---|---|---|
| **LLM orchestrates & narrates; deterministic code computes & validates** | `SqlOracle` validates SQL; model never invents results | rule engine + OCR confidence; agents compute, never guess | **ADR-0001**: LLM never calculates; every number from a unit-tested tool | `Capability` (orchestrates) + `Tool` (computes) — §6.1 |
| **Structured, provenance-tagged outputs with confidence** | `AssistResult` + `Citation` | per-field confidence; system vs human value | `{value, tier, source_ref, computed_at}` per figure | `ToolResult` + `Evidence` — §6.2 |
| **Confidence threshold → escalate, then hand off** | abstain (`UNAVAILABLE`) below confidence | route to **human reviewer** below `minConfidenceScore` | "no data → say no data"; gap-fill by user | `ConfidenceEstimator` + `EscalationPolicy` (pluggable rungs) — §6.4 |
| **Never let the model state an unsourced figure** | `NarrativeGuard` | extracted-field gating | response check: every number maps to a tool output | `GroundingGuard` — §6.5 |
| **Capabilities/tools are declarative, versioned, swappable behind contracts** | `Skill` (flat) | `schema@version`; processing-agent SPI; validator SPI | `DataSource` SPI; thin `@Tool` adapters | `CapabilitySpec` + `Tool` SPI + registries — §6.1 |
| **Provider-swappable model layer** | `ModelRouter` → Ollama | multi-provider OCR abstraction | Gemini behind LangChain4j (provider-swappable) | `ModelProvider` SPI — §6.3 |
| **Immutable audit — keys/summaries/provenance, never raw data** | `AuditEvent.contextKeys` | immutable ledger; field-level PII encryption | every figure traces to its observation(s) | `AuditSink` SPI + `AgentEvent` — §6.6 |
| **RAG supplies qualitative context only — never figures** | `DocRetriever` | doc store (grounding) | pgvector RAG; "RAG never supplies numbers" | `Retriever` SPI — §6.7 |
| **Defer MCP/ADK; design tools so it's a later shim** | no MCP | no MCP | **ADR-0004**: defer MCP/ADK; tools = thin adapters | `Tool` SPI kept transport-free — §6.1 |
| **Trust machinery proven by deterministic fixtures** | (gap — none today) | schema dry-run sandbox | encode the 3BHK worked example as a test fixture | golden-eval harness — §6.8 |

This table is the kernel's charter. Every row is a contract the core owns; every app fills it in.

---

## 3. Where the three agents diverge (what must stay app-specific)

The kernel must **not** force one orchestration model or any infrastructure.

### 3.1 Orchestration shape (pluggable — kernel offers primitives, not a loop)

- **UCC** — synchronous single-shot: request → capability → result. No state.
- **CVVE** — async **state machine**: intake → queue → OCR agent → validate → (auto-pass | HITL) → terminal state (`PASSED/FAILED/OVERRIDDEN/EXPIRED`); idempotency keys, retries, dead-letter, webhooks.
- **CxO** — **streaming** conversational tool-calling (SSE, first token < 5 s) + scenario (what-if) recompute.

→ The kernel provides `Capability`, `Tool`, model, confidence/escalation, audit as **building blocks**. Each app composes them into its own shell (sync handler / state machine / streaming chat). A shared synchronous orchestrator is **deferred to R1** (not shipped at K1): it is the most consumer-specific piece, so it stays out of the core until the 2nd consumer reshapes it — until then UCC composes the building blocks in its own module. CVVE and CxO always bring their own (state machine / streaming).

### 3.2 Infrastructure (entirely app-owned; kernel is agnostic)

| Concern | UCC | CVVE | CxO |
|---|---|---|---|
| Host framework | ServiceLoader (no DI) | Spring Boot + Quarkus agents | Spring Boot + Spring Modulith |
| Persistence | none (reads DuckDB catalog) | Postgres + RLS, object store | Postgres + pgvector, object store |
| Multi-tenancy | none | hard tenant isolation, quotas | single/multi-tenant TBD |
| Eventing | in-process `BatchEvent` | message queue + signed webhooks | chat request / SSE |
| Compliance | none | SOC2/HIPAA/GDPR, field-level PII enc | data-residency for LLM calls |

**Consequence — the strongest argument for a Spring-free core:** because CVVE and CxO are Spring apps and UCC is not, the kernel can depend on **none** of them. Plain Java + `langchain4j-core` drops into a `@Component`/`@Tool` Spring bean *or* a ServiceLoader provider with equal ease. "No Spring in the core" is what *enables* reuse, not a limitation.

### 3.3 App-specific domain machinery (built on kernel primitives, lives in the app)

- UCC: the 7 skills, `SqlOracle`, `FailureReactor` (`BatchEvent`), `OperationalTables`.
- CVVE: schema registry + meta-schema validation, OCR/fuzzy-match/malware processing agents, HITL review queue + `/override`, state machine, tenant isolation.
- CxO: reconciliation engine (normalize → anchor-by-credibility → spread), gap-fill/what-if scenarios, analytics tools (amenity premium, floor-rise, BHK gap), RERA `DataSource` SPI.

The **reconciliation/observation model** (CxO ADR-0002) is general enough that the kernel ships the *data model* for it (`Evidence` + credibility tiers, §6.2); the *reconciliation algorithm* stays in the CxO app.

---

## 4. Today's UCC module — and its structural problem

Two-module reactor, `com.gamma.inspector:file-processor-parent` `3.12.0-SNAPSHOT`, Java 24. The agent module mixes generic runtime with UCC bindings:

| Generic runtime (belongs in kernel) | UCC binding (stays in app) |
|---|---|
| `Skill`/`SkillRegistry`, `ModelProvider/Router/Tier/Request`, `RepairLoop`, `DocRetriever`, `AuditEvent`, `AssistProfile` | 7 skills; `AssistContext`→`MetadataGraphService`/`ReportService`/`StatusStore`/`ConfigSource`; `FailureReactor`→`BatchEvent`; `SqlOracle`, `OperationalTables`, `NarrativeGuard`, `AiDescriptionProvider`; `UccAssistAgent` |

The core assist SPI (`com.gamma.assist`: `AssistAgent`, `AssistRequest`, `AssistResult`, `Diagnosis`) lives in the **ETL core**. Because the agent is **pre-production**, UCC 4.x is free to **reshape** this contract to match the kernel (e.g. `AssistResult.confidence` `String`→`double`) rather than freeze it behind a compatibility adapter. The kernel keeps its own neutral `AgentRequest/AgentResult`; the binding does a thin **reshape** at the HTTP boundary. The ETL core must still **not** depend on `agent-kernel` (no langchain4j in the lean ETL core) — only `file-processor-agent` does.

### 4.1 Confirmed UCC gaps vs. the guidelines (verified in code)

- **Confidence is a label, not a score.** `AssistResult.confidence` is a `String` always `"local"`; nothing reads it; no threshold.
- **No escalation ladder.** `ModelRouter` is a static tier→provider map; `escalat` appears once, in a comment; `RepairLoop` repairs within one tier, never bumps the model or hands off.
- **No declarative capability descriptor.** `Skill` = `id()+tier()+run()`; registry is a hardcoded `List.of(...)`.
- **Thin observability.** `AuditEvent{intent,status,citationCount,durationMs,contextKeys}` can't say which tier ran, whether a model was invoked, repair rounds, or tokens.
- **No per-capability time bound** (only the global 120 s Ollama socket timeout).
- **No golden/eval test tier.**
- **Coarse status taxonomy** (`OK/UNSUPPORTED/UNAVAILABLE` only).

### 4.2 Already aligned — keep, don't gold-plate

Audit keys-not-values ✓ · structured output + citations ✓ · agent-proposes-never-executes (`applyVia` null) ✓ · SQL validated against the real engine ✓ · stateless per request ✓ · ServiceLoader not DI ✓ · one concern per skill (the only `…And…`, `DiagnoseAndAlertSkill`, already splits its event half into `FailureReactor`) ✓.

---

## 5. Principles adopted (merged: framework PDFs + CVVE PRD + CxO ADRs)

1. **Agent decides / Tool executes** (Vol 3/4; ADR-0001; UCC `SqlOracle`). The model picks and phrases; deterministic, unit-tested tools compute. The model may not invent a figure no tool returned. Response generation never re-runs tools.
2. **Single-responsibility capability.** One capability = one job; "if the description needs *and*, split it."
3. **Structured I/O with provenance.** Typed records; every figure carries `{value, credibilityTier, sourceRef, confidence}`. Never free text as the primary contract (narrative is a guarded exception, §6.5).
4. **Reconcile, never silently collapse** (ADR-0002). Keep all source values; normalize → anchor by credibility → show spread. The kernel owns the *data model*; apps own the algorithm.
5. **Independent capability/tool versioning** (`kpi-to-sql@v1`; `schema@version`).
6. **Confidence + escalation with a human terminal rung.** Estimate a numeric score; below threshold escalate (bigger model → **human handoff** → abstain/"no data"). The destination is pluggable per app.
7. **Grounding guarantee.** A guard ensures the narrated answer contains no figure absent from tool results.
8. **Small, budgeted context** (~10% request / 70% retrieved / 20% instructions); retrieval bounded; RAG qualitative only.
9. **Standard observability.** Event taxonomy (`AGENT_STARTED/COMPLETED/FAILED`, `TOOL_*`, `MODEL_CALLED`) + per-capability metrics (requests, failures, exec time, tool calls, tokens, confidence).
10. **Time bounds** per capability.
11. **Declarative definitions** as code-as-data records (no YAML, no DI container required).
12. **Least privilege.** Capabilities receive only the read handles their spec declares; no write credentials by default.
13. **Error taxonomy** (`ValidationError/AuthorizationError/ToolExecutionError/ModelError/SystemError`) mapped to status.
14. **Three test tiers incl. golden-dataset evaluation**, run continuously.
15. **Immutable audit of summaries/keys/provenance — never raw data.**
16. **Defer MCP/ADK; keep tools transport-free** so exposure is a later shim, not a rewrite (ADR-0004).

---

## 6. Core abstractions (the `agent-kernel` library)

Plain Java, no annotations from any framework. Two cohesive halves mirroring the framework's split: **Tool SDK** (deterministic) and **Agent SDK** (orchestration). Illustrative shapes; final signatures refined in implementation.

### 6.1 Capability → Tool (the two-level model)

```java
// ---- Agent SDK: a Capability orchestrates tools + model narration under a policy ----
public interface Capability {
    CapabilitySpec spec();
    AgentResult run(AgentRequest request, AgentContext ctx) throws AgentError;
}

public record CapabilitySpec(
        String id, int version,            // "kpi-to-sql", 1  → "kpi-to-sql@v1"
        String description,                // one line; needs "and" ⇒ split it
        ModelTier defaultTier,
        double confidenceThreshold,        // below ⇒ escalate per policy
        Duration maxExecutionTime,
        Set<String> requiredContext,       // least-privilege handles
        Set<String> allowedTools) {}       // which tools this capability may call

// ---- Tool SDK: a Tool is deterministic, audited, transport-free, returns provenance ----
public interface Tool {
    ToolSpec spec();
    ToolResult invoke(Map<String,Object> args, AgentContext ctx) throws AgentError;
}
```

Replaces today's flat `Skill`. A `Capability` is what the host calls; it lets the model choose among its `allowedTools`, each a deterministic service. This is the literal Agent-SDK/Tool-SDK seam — and the ADR-0004 "thin adapter" boundary that makes MCP a future shim.

### 6.2 Provenance: `ToolResult` + `Evidence` (generalizes UCC `Citation`)

```java
public record ToolResult(Object value, List<Evidence> evidence, boolean hasData) {
    public static ToolResult noData() { return new ToolResult(null, List.of(), false); } // "no data", never an estimate
}

public record Evidence(
        Object value,
        CredibilityTier tier,     // AUTHORITATIVE | OFFICIAL | INDICATIVE | USER_PROVIDED | ASSUMPTION | DERIVED
        String sourceRef,         // citation / observation id / URL / "MahaRERA"
        double confidence,        // 0..1
        Instant observedAt) {}
```

`CredibilityTier` ships with sensible defaults and is open for app extension. This single model serves UCC citations, CxO's credibility tiers + reconciliation inputs, and CVVE's per-field/ system-vs-human confidence. `AgentResult` carries the consolidated evidence so the answer can always cite originals.

### 6.3 Model layer (provider-swappable, with token usage)

```java
public interface ModelProvider { String name(); boolean available(); ModelResponse generate(ModelRequest req); }
public record ModelResponse(String text, int promptTokens, int completionTokens) {}
public enum ModelTier { SMALL, MEDIUM, LARGE }
```

`ModelRouter` keeps the static tier→provider map **and** exposes `next(tier)` for escalation. Providers live in side modules (`agent-provider-ollama`; a future `agent-provider-langchain4j` wraps Gemini/Claude for CVVE/CxO).

### 6.4 Confidence + escalation with pluggable terminal rung (the headline change)

```java
public interface ConfidenceEstimator {
    double estimate(AgentRequest req, AgentResult candidate, AgentContext ctx);
    // composes deterministic signals: validator pass/fail, grounding coverage,
    // oracle agreement (UCC SQL), schema conformance, OCR field confidence, model self-report.
}

public sealed interface EscalationRung permits BumpModelTier, HumanHandoff, Abstain {}
public final class EscalationPolicy {
    // generate @ defaultTier → estimate → if ≥ threshold return;
    // else walk the configured rungs in order:
    //   BumpModelTier (SMALL→MEDIUM→LARGE)   — UCC
    //   HumanHandoff  (enqueue for review)    — CVVE
    //   Abstain       (UNAVAILABLE / "no data") — UCC/CxO default
}
```

The tier machinery (`ModelTier`) and the `confidence` field already exist in UCC; this is the first thing that *uses* them — and the `HumanHandoff` rung is exactly CVVE's confidence-gated HITL. Because it reshapes the result contract, this is the change that earns the **4.0** bump.

### 6.5 GroundingGuard + reliability (repair, deadline, errors)

```java
public interface GroundingGuard {            // generalizes UCC NarrativeGuard + ADR-0001 response check
    Verdict check(String narration, List<Evidence> allowed);  // every figure must trace to allowed evidence
}
```

Plus `RepairLoop` (generate→validate→repair, composable with `EscalationPolicy`), `Deadline` (enforces `maxExecutionTime` → clean `UNAVAILABLE`, never a hang), and a sealed `AgentError` taxonomy (`ValidationError/AuthorizationError/ToolExecutionError/ModelError/SystemError`) mapped to status.

### 6.6 Observability (`AuditSink` SPI)

```java
public sealed interface AgentEvent permits AgentStarted, AgentCompleted, AgentFailed, ModelCalled, ToolCalled, ToolCompleted {}
public interface AuditSink { void emit(AgentEvent e); }
```

Default impl = today's in-memory ring (UCC). CVVE plugs in a **Postgres immutable ledger** with field-level PII encryption; CxO plugs in an observation-trace sink. `AuditEvent` extends today's record with `servedBy` (tier), `modelInvoked`, `repairRounds`, `confidence`, tokens — **keys/summaries/provenance only, never data-plane values** (preserves the UCC guarantee; satisfies CVVE compliance).

### 6.7 Context, budget, retrieval (read-only, least-privilege)

```java
public interface AgentContext {
    <T> Optional<T> handle(Class<T> type);   // capability gets only what its spec declares
    Retriever retriever();                    // RAG: qualitative context only
    ModelRouter models();
    AuditSink audit();
    Optional<String> tenantId();              // opaque to the kernel; CVVE sets it, UCC doesn't
}
public interface Retriever { List<Evidence> retrieve(String query, ContextBudget budget); }
```

`tenantId()` is carried opaquely so the kernel never knows about tenancy while CVVE can scope everything by it. UCC's `UccAgentContext` exposes `MetadataGraphService`/`StatusStore`/etc.; CxO exposes its catalog/analytics services; CVVE exposes its schema registry + tenant config.

### 6.8 Golden-dataset evaluation harness (shipped as a test-jar)

Fixtures `{capabilityId, input, expected:{requiredEvidenceKeys, mustValidate, minConfidence, mustAbstainWhenNoData}}` + a parametrized runner against a `FakeModelProvider` (deterministic, no live model in CI). The CxO **3BHK reconciliation worked example** and UCC's KPI→SQL cases become fixtures. This is the safety net for the confidence/escalation work — built first.

---

## 7. Request flow (UCC synchronous example; CVVE/CxO compose the same primitives differently)

```
host call (HTTP / queue msg / chat turn)
  → adapter maps host request → AgentRequest
  → CapabilityRegistry.dispatch (resolve id@version)
        └─ Capability.run under Deadline(maxExecutionTime)
              → Retriever.retrieve (budgeted, qualitative)            [grounding]
              → model picks Tool(s) from allowedTools  → ToolCalled
              → Tool.invoke → ToolResult{value, evidence}  (deterministic; "no data" if none)
              → model narrates  → ModelCalled
              → GroundingGuard.check(narration, evidence)             [reject unsourced figures]
              → ConfidenceEstimator.estimate
              → EscalationPolicy: ≥threshold? return : walk rungs (bump tier → human handoff → abstain)
              → AgentResult{confidence, servedBy, evidence, error?}
  → adapter maps AgentResult → host response;  AuditSink.emit(...)     [keys/provenance only]
```

UCC's adapter is `UccAssistAgent` over the (4.x-reshaped) `AssistAgent` SPI; CVVE's is a state-machine step that may park the request in the HITL queue on a `HumanHandoff`; CxO's streams tokens and exposes tools as LangChain4j `@Tool` shims.

---

## 8. Kernel vs. application responsibility matrix (the core deliverable)

| Concern | **`agent-kernel` (reuse)** | UCC binding | CVVE binding | CxO binding |
|---|---|---|---|---|
| Capability/Tool SPI + specs + registries | ✓ | 7 skills as capabilities | validation-request capability; OCR/fuzzy/malware tools | comparison-Q capability; analytics/reconciliation tools |
| Provenance model (`Evidence`, tiers) | ✓ | maps to `Citation` | per-field + system/human confidence | AUTH/OFFICIAL/INDICATIVE/USER/ASSUMPTION tiers |
| Confidence + escalation policy | ✓ (pluggable rungs) | rung = abstain | rung = **HITL handoff** | rung = "no data"/gap-fill |
| GroundingGuard | ✓ | `NarrativeGuard` config | field-gating config | "numbers ↔ tool output" check |
| Model layer (`ModelProvider`) | ✓ SPI | Ollama (local, tiers) | multi-provider OCR + LLM | Gemini via LangChain4j |
| Audit (`AuditSink`) | ✓ SPI + event taxonomy | in-memory ring | Postgres immutable ledger + PII enc | observation-trace sink |
| Retrieval (`Retriever`) | ✓ SPI | `DocRetriever` | doc store | pgvector RAG |
| Eval harness | ✓ test-jar | KPI→SQL fixtures | schema dry-runs | 3BHK reconciliation fixture |
| Error taxonomy + deadlines | ✓ | — | — | — |
| **Orchestration shell** | primitives only | sync single-shot | async state machine + queue + webhooks | streaming SSE + scenarios |
| **Persistence** | none (SPI) | DuckDB (read-only) | Postgres + RLS + object store | Postgres + pgvector |
| **Multi-tenancy** | opaque `tenantId()` only | n/a | RLS, quotas, per-tenant config | TBD |
| **Host framework** | none (plain Java) | ServiceLoader | Spring Boot / Quarkus | Spring Modulith |
| **Domain logic** | none | `SqlOracle`, `FailureReactor` | schema registry, HITL queue, state machine | reconciliation, what-if, analytics |
| **Compliance** | audit-shape only | none | SOC2/HIPAA/GDPR, field PII enc | LLM data-residency |

Rule of admission: **a concept enters the kernel only if ≥2 apps share it.** Everything in the right three columns is app code that *uses* the left column.

---

## 9. How each app binds (recipes)

**UCC (this repo):** keep `UccAssistAgent` as the adapter over core's `AssistAgent` SPI; turn the 7 skills into `Capability` impls; `UccAgentContext` implements `AgentContext`; Ollama provider; abstain rung; `NarrativeGuard`→`GroundingGuard`. No persistence, no tenancy.

**CVVE:** Spring beans wrap kernel types. Each validation request type is a `Capability`; OCR/fuzzy-match/malware are `Tool`s (Quarkus agents behind the `Tool` SPI). The **state machine** drives capabilities; `EscalationPolicy` uses the **`HumanHandoff` rung** to enqueue HITL when `confidence < minConfidenceScore`. `AuditSink` = immutable Postgres ledger; `AgentContext.tenantId()` scopes everything; provider module wraps the OCR/LLM vendors.

**CxO:** Spring Modulith app. The conversational agent is a `Capability`; analytics/reconciliation/catalog services are `Tool`s exposed to LangChain4j as thin `@Tool` shims (ADR-0004). `Evidence` carries credibility tiers; the reconciliation engine consumes `Evidence` and writes `ConsolidatedParameter`. `GroundingGuard` enforces ADR-0001 (no number without a tool source). `Retriever` = pgvector (qualitative only). Escalation rung = "no data" / prompt gap-fill.

A **new** agent: depend on `agent-kernel` (+ a provider module), write N capabilities + their tools + an `AgentContext`, pick a provider, choose escalation rungs, drop in golden fixtures, and wrap it in whatever shell (sync/async/stream) the host needs. Everything in §6 comes for free.

---

## 10. Module & repository structure (three concentric rings)

`agent-kernel` lives in **its own repository**, published as a versioned artifact (§13). UCC, CVVE, and CxO are separate repos that depend on it. A concept enters an inner ring only if it's shared (§8 admission rule).

**Ring 1 — pure core** · always present · Spring-free · `langchain4j-core` only · repo `agent-kernel`
```
  …kernel.tool      Tool, ToolSpec, ToolResult, Evidence, CredibilityTier, ToolRegistry, GroundingGuard
  …kernel.agent     Capability, CapabilitySpec, CapabilityRegistry, AgentRequest, AgentResult, AgentContext
  …kernel.model     ModelProvider, ModelRouter, ModelTier, ModelRequest, ModelResponse
  …kernel.reason    ConfidenceEstimator, EscalationPolicy (+rungs), RepairLoop, Deadline
  …kernel.error     AgentError taxonomy
  …kernel.observe   AgentEvent, AuditSink, AuditEvent
  …kernel.retrieve  Retriever, ContextBudget, DocRetriever (default)
```

**Ring 2 — optional companion modules** · pick what you need · same repo, separate artifacts · each depends on ring-1 only
```
  agent-provider-ollama        ModelProvider over langchain4j-ollama                 [UCC]
  agent-provider-langchain4j   ModelProvider over Gemini/Claude                      [CVVE/CxO]
  agent-kernel-spring          Spring Boot starter: auto-config + bean/@Tool adapters[CVVE/CxO]
  agent-store-postgres         AuditSink + Evidence/observation store on Postgres    [CVVE/CxO]
  agent-hitl                   review-queue + HumanHandoff rung implementation       [CVVE]
  agent-orchestration          async state-machine helper (idempotency/retry/DLQ)    [CVVE]
  agent-eval                   golden-dataset harness (published as a test-jar)      [all]
```

**Ring 3 — application bindings** · always app-specific · live in each app's own repo · never travel
```
  UCC  → file-processor-agent (stays in ucc-file-processor): UccAssistAgent, 7 capabilities,
         SqlOracle, FailureReactor, UccAgentContext; consumes ring-1 + agent-provider-ollama.
  CVVE → its repo: schema registry, OCR/rule tools, HITL queue, state machine, tenant isolation;
         consumes ring-1 + spring + store-postgres + hitl + orchestration + provider-langchain4j.
  CxO  → its repo: reconciliation engine, analytics tools, what-if scenarios;
         consumes ring-1 + spring + store-postgres + provider-langchain4j.
```

**Dependency direction is strictly inward:** ring-1 depends on nothing app-aware; ring-2 depends only on ring-1; bindings depend on whatever they need. Lean-core is preserved on both sides: UCC's ETL core (`file-processor`) keeps **zero AI deps**; only `file-processor-agent` (a ring-3 binding) depends on `agent-kernel`.

---

## 11. Gap closure (UCC A–H) — and how the 3-app view extended it

| UCC gap | Closed by | Extended by multi-app view |
|---|---|---|
| A — real confidence + escalation | §6.4 | + `HumanHandoff` rung (CVVE HITL) |
| B — declarative descriptor | §6.1 `CapabilitySpec` | + `allowedTools` (Tool SDK split) |
| C — richer observability | §6.6 | + `AuditSink` SPI (durable ledger for CVVE) |
| D — per-capability timeout | §6.5 `Deadline` | — |
| E — golden-eval tier | §6.8 | + 3BHK reconciliation fixture (CxO) |
| F — single-responsibility | keep `DiagnoseAndAlert` | reinforced by Capability→Tool split |
| G — structured output + versioning | §6.1/§6.2 | + `Evidence`/credibility tiers (CxO/CVVE) |
| H — error taxonomy | §6.5 | — |
| *(new)* grounding guarantee | §6.5 `GroundingGuard` | ADR-0001 response check + UCC `NarrativeGuard` |
| *(new)* provenance/reconciliation model | §6.2 `Evidence` | ADR-0002 |

---

## 12. Migration plan

**Context locked:** the kernel ships from a **separate repo** as a published artifact; the UCC agent is **pre-production**, so UCC **4.x** may reshape the `com.gamma.assist` SPI; the ETL core keeps **zero AI deps**. Phases split across the two repos. No commit/push/tag without explicit ask.

**`agent-kernel` repo**
- **K0 — Bootstrap.** New repo; groupId + SemVer starting `0.1.0-SNAPSHOT`; CI; ring-1 package skeleton; `agent-eval` harness skeleton. Publish `0.1.0-SNAPSHOT` to the registry.
- **K1 — Ring-1 core.** Port the generic runtime out of `file-processor-agent`, de-UCC'd: model layer, `RepairLoop`, `DocRetriever`, `AuditEvent`, `AssistProfile`. Add `Tool`/`ToolResult`/`Evidence`, `Capability`/`CapabilitySpec`, `GroundingGuard`, `ConfidenceEstimator`, `EscalationPolicy` (abstain rung), `Deadline`, `AgentError`, `AuditSink`. Kernel's own tests + eval harness green **with zero apps present**. Add `agent-provider-ollama`.

**`ucc-file-processor` repo (branch `4.x`)** — detailed in [`AGENT_KERNEL_U0_U1_PLAN.md`](AGENT_KERNEL_U0_U1_PLAN.md)
- **U0 — Eval net.** Golden fixtures for the 7 capabilities (KPI→SQL etc.) against a `FakeModelProvider`; establishes the regression baseline. *(Gap E)*
- **U1 — Consume the kernel.** `file-processor-agent` depends on the published kernel (`0.x-SNAPSHOT`). The 7 skills become `Capability` impls; `UccAgentContext` implements `AgentContext`; `NarrativeGuard`→`GroundingGuard`; `SqlOracle`/etc. become `Tool`s. **Reshape** the core assist SPI (`AssistResult.confidence` `String`→`double`, richer status); `ControlApi` maps at the HTTP boundary. Wire real confidence + the **abstain** escalation rung. Update the suite for the reshaped API (target: full suite green). *(Gaps A, B, C, D, G, H)* → cut **UCC 4.0**.

**Reuse phase (driven by the 2nd consumer — rule of three)** — detailed in [`AGENT_KERNEL_R1_PLAN.md`](AGENT_KERNEL_R1_PLAN.md)
- **R1 — Companion modules + 1.0.** When CVVE or CxO first builds on the kernel, add the ring-2 modules it needs (`agent-kernel-spring`, `agent-store-postgres`, `agent-hitl`, `agent-orchestration`, `agent-provider-langchain4j`) and let that real usage reshape the API before freezing kernel **`1.0`**. UCC then upgrades to `1.0` at its own pace.

The ETL *engine* (data path) is untouched throughout; only the assist SPI contract is reshaped.

---

## 13. Repository, versioning & governance

**Decoupling is at the library level.** `agent-kernel` is a jar **embedded** in each app; there is **no shared running service**, and apps may run **different kernel versions**. Only the kernel travels — every ring-3 binding stays in its app's repo.

- **Versioning.** Kernel has its **own SemVer line**, independent of UCC's `3.x`/`4.x`. Unstable `0.x` until a second app consumes it; freeze `1.0` once CVVE/CxO has exercised the API. Breaking SPI change = major. SPI evolution is **additive-by-default** (new `default` methods, never changed signatures) so a CVVE-driven change can't silently break UCC.
- **Cross-repo development.** While the kernel API churns and UCC consumes it concurrently: publish `0.x-SNAPSHOT`s to **GitHub Packages**, or `mvn install` to local `.m2` for tight loops. UCC **pins** a concrete version; bumps are deliberate PRs.
- **Build & deploy.** Kernel: `mvn deploy` to the registry on tag — it is **published, never deployed**. Apps: build their own fat-jar/container against a **pinned** kernel version and deploy on their own cadence. Zero runtime coupling.
- **CI.** The kernel's CI runs its unit tests + the golden-eval harness **with no app on the classpath** — it must be releasable independently. Each app's CI tests against its pinned kernel version.
- **Enhancement & admission.** A concept enters **ring-1** only if **≥2 apps share it** (§8). App-specific need → ring-3 binding. Shared *infra* need (Postgres audit, HITL, state machine) → a **ring-2 companion module**. Deprecate-then-remove across one minor protects laggards.
- **Ownership.** Small kernel maintainer set + `CODEOWNERS`; apps file issues against the kernel repo.

---

## 14. Decisions

The load-bearing decisions below are each captured as an immutable ADR in [`adr/`](adr/README.md) (founding set 0001–0008), mirroring the CxO ADR style.

**Locked (2026-06-04):**
- Kernel in its **own repo**, published artifact, **independent SemVer** (`0.x` → `1.0` after the 2nd consumer).
- **Three-ring** structure: pure core + companion modules + app bindings (§10).
- **Separate** provider/companion modules (not optional deps bolted onto the core).
- UCC agent is **pre-production** → **reshape** the assist SPI in UCC 4.x (no frozen-adapter constraint); ETL core keeps **zero AI deps**.
- **Coordinates:** `com.gamma.agentkernel:agent-kernel-core` (parent `agent-kernel-parent`).
- **Java 25** is the kernel bytecode floor — every consumer runs JVM ≥25; **UCC moves 24→25** (a U0 prerequisite), CVVE/CxO start at 25. Ring-1 has **zero runtime deps**.
- **Artifact registry:** GitHub Packages; **CI:** GitHub Actions, tag-driven release.
- **`CredibilityTier`:** enum + `Evidence.tierLabel` `String` escape hatch for `0.x`; revisit promotion to an app-extensible interface at `1.0`.
- **Sync orchestrator:** **deferred to R1**, not shipped in ring-1 at K1. The kernel ships the *ingredients* (escalation, confidence, grounding, deadline, repair, audit) + `CapabilityRegistry.dispatch()`; UCC keeps its own orchestrator until R1's shared one is proven by a 2nd consumer. (Supersedes the earlier "ship a minimal sync orchestrator for UCC-style hosts" lean — the orchestrator is the most consumer-specific piece, so it stays out until reshaped by CVVE/CxO.)

**Still open (minor, non-blocking):**
- GitHub **`<org>`** that owns the `agent-kernel` repo (the only value still needed to fill `distributionManagement`).

---

## 15. Non-goals

- **Ring-1** takes no Spring / Postgres / Redis / Kafka / MCP dependency and embeds no persistence or tenancy engine. (Ring-2 companions and ring-3 bindings may.)
- No MCP/ADK now — tools kept transport-free so it's a later shim (ADR-0004).
- No autonomous writes from the kernel; write-capability is an app concern (UCC stays draft-only `applyVia=null`; CVVE writes only via its audited state machine; CxO never writes facts, only scenarios).
- No persistent agent memory / multi-turn session state in ring-1 (apps add it if needed).
- The ETL **engine** (data path) is not restructured; only the assist SPI contract is reshaped in UCC 4.x, and the ETL core keeps zero AI dependencies.
```
