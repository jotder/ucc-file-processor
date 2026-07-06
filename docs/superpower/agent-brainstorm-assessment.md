# Assessment: eoiagent (`C:/sandbox/agent-brainstorm`) as a replacement for agent-kernel

*2026-07-07 — evaluation of the EOI Agent platform against Inspecto's current agent-kernel 1.1.0 integration.*

> **OUTCOME (same day):** the user decided agent-kernel **is being discontinued** → replacement
> executed immediately via the staged path (§5.2-style): kernel reasoning layer vendored in-tree,
> eoiagent adopted as the model transport. See `agent-kernel-replacement-plan.md` for what shipped.

## 1. What eoiagent is

**EOI Agent** — an 18-module plain-Java "embeddable agent OS" (groupId `com.eoiagent`, version
**0.1.0-SNAPSHOT**, JDK 25). Hexagonal ports-and-adapters: `eoiagent-core` holds 11 port interfaces
with zero third-party agent deps; adapters (LangChain4j 1.16.3, LangGraph4j, ONNX embeddings,
pgvector, OpenTelemetry) are quarantined in dedicated modules. Offline-first, no Spring, ServiceLoader
/builder wiring, 85 offline test files, CI green, golden-set eval harness. Phases 0–3 complete;
**Phase 3.5 (platform wiring of the advanced orchestrators) still in progress; Phase 4 hardening not
started; no released version**.

It is a **greenfield design** — zero references to agent-kernel anywhere in its code or docs. It was
not written as a kernel successor.

## 2. What Inspecto actually uses from agent-kernel (1.1.0)

Coupling is well-contained to the two optional modules `inspecto-agent` + `inspecto-agent-hosted`
(ServiceLoader-loaded; the lean-core fat JAR never sees kernel types). Zero kernel leakage into
`inspecto/`, connectors, or the control API. Anti-corruption boundary already exists:
`UccAssistAgent.toWire()` maps kernel `AgentResult` → Inspecto's own `com.gamma.assist.AssistResult`,
and the `AssistAgent` / `HostedProviderPlugin` SPIs are Inspecto types, not kernel types.

Used surface (~20 main classes code directly against it):

| Kernel artifact | Types Inspecto uses |
|---|---|
| `agent-kernel-core` | `ModelProvider/ModelRouter/ModelRequest/ModelTier` · `AgentContext/AgentRequest/AgentResult` · `Capability/CapabilitySpec/CapabilityRegistry` (7 skills) · `Tool/ToolRegistry/ToolSpec/Evidence/CredibilityTier/GroundingGuard` · `ConfidenceEstimator/EscalationPolicy/EscalationRung(Abstain)/RepairLoop` · `AuditSink/AgentEvent` · `DocRetriever/ContextBudget` |
| `agent-provider-ollama` | `OllamaModelProvider`, `ModelProfile` |
| `agent-orchestration` | `SyncOrchestrator.run/resume` |
| `agent-eval` (test) | `EvalCase/Evals/FakeModelProvider` (GoldenEvalTest) |

Not used: `agent-kernel-spring`, `agent-store-postgres`, `agent-provider-langchain4j` (Inspecto
hand-rolled `LangC4jChatProvider` in `inspecto-agent-hosted` instead), `HumanHandoff/resume` (the
1.1.0 headline feature — Inspecto only registers `EscalationRung.Abstain`).

## 3. Seam-by-seam compatibility map

| Concern | agent-kernel (used today) | eoiagent equivalent | Fit |
|---|---|---|---|
| Model access | `ModelProvider`/`ModelRouter`/`ModelTier` | `LlmGateway`/`RoutingLlmGateway`/`ModelRole` + `ModelProfile` | ✅ close; eoiagent ships Ollama + OpenAI-compatible + Anthropic + Gemini adapters — would **replace** Inspecto's hand-rolled LangChain4j hosted provider |
| Tools | `Tool/ToolRegistry/ToolResult` | `Tool/ToolRegistry/ToolSpec/ToolResult` (+ mutating flag, RBAC, approval routing) | ✅ close, eoiagent richer |
| Audit | `AuditSink/AgentEvent` | `AuditSink/AuditEvent` (+ JDBC/file sinks, OTel) | ✅ close |
| Retrieval | in-memory lexical `DocRetriever` | `DocumentIngestor/Retriever/VectorStore` (ONNX embeddings, pgvector) — `PlatformBuilder.retriever()` override exists | ✅ richer; lexical path needs a small custom `Retriever` impl |
| Eval | `agent-eval` golden tests | `eoiagent-eval` YAML golden-set harness | ✅ equivalent |
| Agent loop | `CapabilityRegistry` (7 skills) + `SyncOrchestrator` — single-shot capability dispatch | `Orchestrator.run(Goal, ctx)` ReAct/LangGraph loop + `AgentSession.ask()` sessions + `GoalKind` routing | ⚠️ **different mental model** — skills → Goal kinds + tools + `PromptProfile`; full rewrite of the 7 skill classes |
| Confidence / abstain | `ConfidenceEstimator/EscalationPolicy/RepairLoop/Evidence/CredibilityTier/GroundingGuard` (`UccConfidenceEstimator`, `NumericGroundingGuard`) | **No equivalent.** Safety model is `ApprovalGate` (human-in-loop) + `PolicyEngine` (RBAC) + `Guardrail` (I/O validation) | ❌ the kernel's evidence-grounded confidence + abstain machinery has no counterpart; would need redesign onto Guardrail or reimplementation |
| Service handles | `AgentContext.handle(Class<T>)` → `MetadataGraphService` etc. | `AgentContext` is a plain record; services injected via `ApplicationPack.ToolProvider` | ⚠️ mechanical rework |
| Host integration | Inspecto-owned SPIs (`AssistAgent`, `HostedProviderPlugin`) | `ApplicationPack` SPI + `PlatformBuilder` → Inspecto becomes a "product pack" | ✅ conceptually a very good fit with the edition model |
| Config | `assist-settings.properties` → `ProviderSettings` per `ModelTier` | `ConfigProvider` + `DeploymentProfile` (OFFLINE/ON_PREM_HOSTED/CLOUD) + `eoiagent.model.*` keys | ✅ mappable; OFFLINE profile matches Personal edition |

Platform compatibility: JDK 25 bytecode runs fine on Inspecto's Java 26; both framework-free +
ServiceLoader-idiomatic; both offline-first — philosophically aligned with `java-backend` rules.

## 4. Risks of replacing now

1. **Pre-release dependency.** `0.1.0-SNAPSHOT`, no released artifact, Phase 3.5 platform wiring
   incomplete, Phase 4 hardening pending. agent-kernel is a *released* 1.1.0. Pinning Inspecto to a
   SNAPSHOT violates our own release discipline.
2. **Lost capability.** The confidence/escalation/grounding stack (`UccConfidenceEstimator`,
   `NumericGroundingGuard`, abstain-on-low-confidence) is load-bearing in every skill and has no
   eoiagent counterpart — it would have to be rebuilt on the `Guardrail` port.
3. **Rewrite, not port.** All ~20 main classes in `inspecto-agent` (7 skills, 2 tools, model
   plumbing, context) are coded directly against kernel interfaces. The moves are mechanical for
   tools/audit/model but architectural for the skill/orchestrator layer.
4. **Dependency weight.** eoiagent adapters pull LangChain4j BOM, LangGraph4j, ONNX embedding
   runtime — the offline bundle grows unless we depend only on core+model+tool+runtime and skip
   `eoiagent-knowledge`.
5. **Two other kernel consumers** (CxO, CVVE per kernel README) — Inspecto migrating alone forks the
   ecosystem the kernel was extracted for.

## 5. Recommendation

**Compatible in principle, do not replace wholesale yet.** eoiagent is the architecturally stronger
platform (sessions, streaming, RAG, approval gates, packs) and its port design fits Inspecto's
framework-free/offline/edition model well — it is a credible *future* home for the assist agent. But
today it is pre-release with the platform layer still landing, and the swap costs a full
`inspecto-agent` rewrite plus rebuilding the confidence/abstain machinery.

Suggested path if/when adoption is wanted:

1. **Wait for a released eoiagent version** (post-Phase-4, non-SNAPSHOT) before any production dependency.
2. **Incremental first step — hosted providers:** bridge eoiagent's `LlmGateway` adapters
   (Anthropic/Gemini/OpenAI-compatible) behind Inspecto's existing `HostedProviderPlugin` SPI via a
   small `LlmGateway → ModelProvider` adapter. Deletes our hand-rolled LangChain4j provider without
   touching the agent loop; both sides stay behind Inspecto-owned SPIs.
3. **Full migration** (later): implement an Inspecto `ApplicationPack`; recast the 7 skills as
   Goal-kind prompt profiles + tools; port `UccConfidenceEstimator`/grounding onto the `Guardrail`
   port; keep the `AssistResult` wire boundary unchanged so the control API and UI see no difference.
4. Alternatively, **upstream the gap**: propose a confidence/abstain port into eoiagent — it is the
   one kernel feature eoiagent lacks and the main blocker.

*Sources: exploration of `C:/sandbox/agent-brainstorm` (README/ADRs/module poms/graphify wiki) and a
full grep-map of `com.gamma.agentkernel` usage in this repo, 2026-07-07.*
