# agent-kernel → eoiagent replacement plan

*2026-07-07. Trigger: **agent-kernel is being discontinued** (user decision after
`agent-brainstorm-assessment.md`). Scope: remove every `com.gamma.agentkernel` Maven dependency from
the reactor; adopt eoiagent (`C:/sandbox/agent-brainstorm`, `com.eoiagent:*:0.1.0-SNAPSHOT`) as the
model-access substrate. The `AssistResult` wire boundary, control-plane routes, and UI are untouched.*

## Strategy (decided)

Two moves, executed together:

1. **Vendor the kernel's reasoning layer in-repo.** eoiagent has no equivalent of the
   capability/confidence/abstain/grounding machinery every skill uses, and the kernel is dying — so
   Inspecto takes ownership of that code. `agent-kernel-core` (40 files, framework-free, JDK-only)
   + `SyncOrchestrator` are copied into `inspecto-agent/src/main/java/com/gamma/agent/kernel/**`
   (package rename `com.gamma.agentkernel` → `com.gamma.agent.kernel`); the `agent-eval` harness
   (6 files) goes to the test tree (`com.gamma.agent.kernel.eval`). Vendored from kernel **1.1.0**
   sources at `C:/sandbox/agent-kernel` (same org, discontinued — fork-and-own).
   `ModelProfile` (from `agent-provider-ollama`) moves to `com.gamma.agent.model`, **keeping its
   `agentkernel.*` system-property names** so existing deployments keep working.

2. **Replace the model bottom layer with eoiagent gateways.** `agent-provider-ollama` and the
   hosted module's hand-rolled lc4j request/response mapping are replaced by eoiagent's
   `LlmGateway`/`Lc4jChatGateway`. One new adapter,
   `com.gamma.agent.model.EoiGatewayModelProvider implements ModelProvider`, bridges the vendored
   seam onto `LlmGateway` and is used by **both** the local Ollama path and the hosted plugin —
   deleting two copies of lc4j mapping code. lc4j `ChatModel`s are still **built Inspecto-side**
   (not via eoiagent's stock adapters) to preserve two behaviors eoiagent's adapters don't carry:
   - **native `ResponseFormat.JSON`** for Ollama (small-model shape reliability; hosted providers
     keep the system-prompt JSON constraint, wording unchanged);
   - **per-request client timeouts** from `ProviderSettings.timeoutSeconds` (plus the existing
     `TimeoutModelProvider` hard deadline, which stays).

Full eoiagent-native adoption (AgentSession/Goal/ApplicationPack, skills as goal kinds) remains a
deliberate **later phase** — not now, while eoiagent is pre-release and its platform wiring
(Phase 3.5) is still landing. This step makes that future move incremental: the transport objects
Inspecto constructs are already `LlmGateway` instances.

## Mechanical map

| Today | After |
|---|---|
| `com.gamma.agentkernel:agent-kernel-core:1.1.0` | vendored `com.gamma.agent.kernel.*` (main tree) |
| `…:agent-orchestration:1.1.0` (`SyncOrchestrator`) | vendored `com.gamma.agent.kernel.orchestrate` |
| `…:agent-provider-ollama:1.1.0` | `EoiGatewayModelProvider` + `OllamaModelProviders` factory (lc4j `OllamaChatModel` TEXT/JSON, wrapped in eoiagent `Lc4jChatGateway`) |
| `…:agent-eval:1.1.0` (jar + test-jar, test scope) | vendored `com.gamma.agent.kernel.eval` in `src/test/java` |
| hosted `LangChain4jChatProvider` (mapping + building) | building only (`HostedChatModels` switch, timeouts kept) → `Lc4jChatGateway` → shared `EoiGatewayModelProvider` |
| langchain4j 1.15.1 (two pins) | 1.16.3 (aligned with eoiagent-model's compile target) |
| — | new deps: `com.eoiagent:eoiagent-core`, `com.eoiagent:eoiagent-model` (0.1.0-SNAPSHOT) |

Behavior notes (accepted, small):
- Token accounting rides eoiagent `Usage` (0 = absent) → mapped back to the kernel convention
  (`-1` = unknown) when `totalTokens == 0`.
- lc4j 1.15.1 → 1.16.3 bump rides along (eoiagent BOM pins it; fixes a pgvector CVE upstream).

## Bytecode / runtime constraints (verified)

- eoiagent jars are class-file **v69 (Java 25)** — same as agent-kernel 1.1.0 today. javac 26 with
  `--release 24` compiles fine against them; they need a **JDK 25+ runtime**, which the agent module
  already requires (root-pom note) and the bundled JDK 26 satisfies. Vendoring the kernel at
  `release=24` actually *shrinks* the v69 surface to the eoiagent jars only.
- eoiagent was `mvn clean install`'d to the local `.m2` from `C:/sandbox/agent-brainstorm`
  (all 19 artifacts, built on the same JDK 26).

## ⚠️ Open items for the team

1. **CI resolution:** CI resolved agent-kernel from GitHub Packages (`ci.yml` settings.xml note in
   `inspecto-agent/pom.xml`). eoiagent 0.1.0-SNAPSHOT is **local-only** — CI needs eoiagent published
   to GitHub Packages (or built in a CI step) before this ships through CI.
2. **SNAPSHOT pin:** eoiagent is pre-release; re-`mvn install` of agent-brainstorm can change
   behavior under us. Recommend asking the eoiagent side to cut a `0.1.0` release tag soon.
3. agent-kernel's other consumers (CxO, CVVE) are unaffected — this fork is Inspecto-local.

## Verify

GAUNTLET backend leg: `mvn -o clean test` full reactor (agent tests incl. `GoldenEvalTest`,
`ModelSeamTest`, `EgressGuardTest` reworked to the new factory seams) — 0 failures, then packaged
bundle smoke.
