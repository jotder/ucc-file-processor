# Embedded Intelligence — execution plan

*2026-07-07. Vision: the model lives INSIDE Inspecto and drives it — it knows the metadata network,
business Sources, operational and situational context; it explains Incidents, performs root-cause
analysis, authors and executes rules and queries, builds Pipelines, KPIs, reports and dashboards,
assists analysis, and acts as an operator/support agent — with autonomy that is earned, gated, and
auditable. Successor track to the assist agent (7 reflex skills) and the agent-kernel replacement
(archived: `docs/archived-documents/plans-archive/agent-kernel-replacement-plan.md`); this is the "full eoiagent-native port" phase, expanded into a
product capability.*

---

## 0. Architecture doctrine (what makes the model "powerful")

The model is a commodity. Power comes from four surfaces we control, in this order:

1. **Grounding** — what the agent can *know*: typed, fresh, token-budgeted context. An agent that
   sees the metadata graph, live ops state, and the docs corpus outperforms a bigger model that
   sees a prompt.
2. **Actuation** — what the agent can *do*: tools over the **governed v1 control plane** (WriteGates,
   ETag/If-Match, ConfigSafetyValidator, Idempotency-Key, audit). The agent gets **no private
   backdoor** — it calls the same fail-closed business contracts as the UI and API users. Safety
   stays a packaging/gate fact, never a prompt fact.
3. **Judgment scaffolding** — skills as playbooks (RCA method, authoring loops with validators),
   confidence estimation + abstain, evidence-or-silence numerics (the vendored
   `ConfidenceEstimator`/`GroundingGuard` machinery we now own).
4. **Governance** — the autonomy ladder in code: `ApprovalGate`, `PolicyEngine`, budgets, undo,
   kill switch. Autonomy is granted per action class by policy, never assumed.

**Two-layer brain.** Keep the existing 7 skills + abstain orchestration as the deterministic
**reflex layer** (single-shot, cheap, already green). Add a **deliberative layer** on the eoiagent
platform (`Orchestrator.run(Goal, ctx)`, ReAct/LangGraph, sessions, checkpoints) for multi-step
work: investigation, authoring loops, operations. Reflexes stay the fallback when the deliberative
layer is off (edition/packaging), preserving graceful degrade.

**Everything the agent makes is a Component.** Drafts land in the `ComponentStore` (`kind`, config,
parts, wiring, content-hash) in `DRAFT` state — same metamodel, same reuse graph, same audit as
human-authored artifacts. "Agent output" is product data, reviewable and diffable, not chat text.

**Canonical vocabulary is binding** in every prompt, tool name, and output: Pipeline, Dataset,
Incident, Expectation / Alert Rule / Decision Rule, Measure, Source, Widget (`docs/GLOSSARY.md`
is itself a knowledge feed — see §2).

---

## 1. Runtime shape

- **New optional module `inspecto-intelligence/`** (`file-processor-intelligence`), ServiceLoader-
  discovered like the assist agent — editions stay build flavors:
  - Personal: local Ollama tiers only, deliberative layer optional.
  - Standard/Enterprise: + hosted providers, + autonomy L2/L3 (needs W6 auth for real approvers).
  - Air-gap invariant unchanged (`EgressGuardTest` pattern extends to this module).
- **Inside it: the `InspectoPack` — an eoiagent `ApplicationPack`** providing the eight seams:
  `PackMetadata`, `ModelProfile` (bridged from `ProviderSettings`/`AssistModelSettings` so the
  existing settings screen keeps working), `KnowledgeSource[]` (§2), `ToolProvider` (§3),
  `NavigationCatalog` (route map from `app.routes.ts` — powers `NavigationIntent` answers),
  `PromptProfile` (§5), `PolicyProfile` (§6), `PackConfig` (TOON-backed, `ConfigSafetyValidator`d).
  `PlatformBuilder.pack(inspectoPack).start()` → `AgentService`.
- **Control-plane surface** (new ControlApi routes, endpoint-skill house style, all fail-closed):
  - `POST /agent/sessions` → session; `POST /agent/sessions/{id}/ask` (+ SSE stream variant —
    `LlmGateway.chatStream` exists; JDK HttpServer does SSE fine).
  - `GET/POST /agent/approvals` — the approvals inbox (§6, L2).
  - `GET /agent/policy` / `PUT /agent/policy` — autonomy policy (scope `agent.admin`).
  - `GET /agent/cases` — investigation case store (§4 RCA).
  - Existing `/assist/*` routes stay (reflex layer) — zero regression.
- **Ingress channels**: (a) UI — assist panel per Lens + command palette + page context header;
  (b) events — `BatchEventBus` subscription (the `FailureReactor` pattern, generalized) + scheduler
  ticks for proactive monitoring; (c) API — automation can open sessions too.
- **Agent identity**: the agent runs as its own `Subject` ("inspecto-agent") with explicit W6
  capabilities. Its writes carry `actor=agent:<session>` into the audit/event store. It can never
  exceed the capabilities of the human it acts for (intersection, not union).

## 2. Knowledge feed (the context fabric)

Three freshness classes, two delivery mechanisms — **RAG for knowledge, typed fetch for state**
(never embed live state):

| Class | Feed | Source of truth | Delivery |
|---|---|---|---|
| Static | Product docs, runbooks, ADRs, the **consolidated OKF bundle** (~100 structured files — built for this), **GLOSSARY** (canonical terms + banned synonyms), example catalog | `docs/`, `docs/okf/` (frontend + backend + agentic sections), `inspecto/examples/` | `KnowledgeSource[]` → `DocumentIngestor` at pack bootstrap; citations mandatory |
| Slow-changing | Metadata network: catalog entities + descriptions (`MetadataGraphService`), Component reuse graph (P3 `inspecto/graph`), schemas + Expectations, Source/connection profiles (secrets NEVER — refs only), Measures, Datasets, Pipeline configs, schedules | ComponentStore, catalog, `ConnectionRegistry` | typed **ContextProviders** + a `graph_neighbors` tool; re-ingest summaries on change events |
| Live | `StatusStore`, batch/job runs (W5), alerts + Incidents, quarantine, acquisition ledger, Prometheus metrics, scheduler state, `DiagnosisStore` | ops stores | **tools only** (§3), fetched at question time |

**ContextBroker (new, the heart of grounding).** Assembles a bounded **situation frame** per
request under a `ContextBudget` (vendored type):

1. *Identity*: user, Lens (Business/Builder/Ops), space, edition, capabilities.
2. *Focus*: current page (`PageContext` → `NavigationCatalog`) → focus Component → 1–2 hop
   neighborhood from the reuse/lineage graph (Pipeline → Dataset → Query → Widget → Dashboard;
   Source → Pipeline; Alert Rule → Dataset).
3. *Live overlay*: last N events/runs/Incidents touching the focus neighborhood.
4. *Knowledge*: retrieval hits (docs/OKF/glossary/examples) for the utterance.

Budgeted, deterministic order, evictable by priority — this frame is what makes every skill
"context-aware" without per-skill plumbing.

## 3. Tool belt (actuation surface)

Every tool: JSON-schema spec, `mutating` flag, required capability, evidence-producing
(`Evidence` + `CredibilityTier`), dry-run where mutating. Registered via the pack `ToolProvider`;
dispatched through eoiagent `ToolRegistry` (RBAC + approval routing built in).

**Read/ground (L0):** `catalog_search`, `component_get`, `graph_neighbors`, `schema_describe`,
`sql_query` (sandboxed read-only — grow from `SqlOracleTool`), `dataset_preview`, `status_get`,
`events_window`, `runs_list`, `incident_get`, `metrics_query`, `quarantine_inspect`,
`config_versions_diff`, `docs_search`, `glossary_lookup`, `navigate_suggest`.

**Analyze (L0, deterministic math — tools compute, model judges):** `profile_dataset`
(nulls/cardinality/distribution), `diff_batches`, `expectation_evaluate`, `anomaly_scan`
(threshold/z-score), `timeline_build` (events → ordered timeline for RCA).

**Author (L1 — DRAFT Components only):** `component_draft` (one tool, `kind`-typed: Pipeline /
Dataset / Query / Widget / Dashboard / Alert Rule / Expectation / Decision Rule / schedule / report
— W3 widened `WRITABLE_TYPES` is the seam), `parser_config_test` (the 9-format parser harness),
`pipeline_simulate` (dry-run through the simulator), `query_validate` ($-parameters + Result Set
contract), `report_compose`, `kpi_define` (Measure + Widget + placement). Authoring runs a
**validator repair loop** (vendored `RepairLoop`): draft → `ConfigSafetyValidator`/schema check →
errors fed back → bounded retries → abstain with partial draft if still failing.

**Act (L2/L3 — gated):** `component_apply` (DRAFT→ACTIVE via ETag/If-Match + WriteGates +
ApprovalGate), `job_run` (W5 async + Idempotency-Key), `schedule_apply`, `alert_ack`,
`incident_update`, `pipeline_rerun`, `component_rollback` (version restore — every act is
undoable), `runbook_execute` (a named, pre-approved plan of gated tools).

## 4. Skill catalog (Goal kinds → playbooks)

Reflex layer (existing 7) stays. Deliberative skills, mapped to eoiagent `GoalKind`:

| GoalKind | Skill | Playbook essence |
|---|---|---|
| INVESTIGATION | `incident_explain` | situation frame → timeline_build → evidence probes → narrative + citations (upgrades `DiagnoseAndAlertSkill`) |
| INVESTIGATION | `root_cause_analysis` | symptoms → blast radius (graph) → change scan (`config_versions_diff`, recent applies) → hypothesis set → per-hypothesis evidence via `sql_query`/`diff_batches` → ranked causes + confidence + suggested fix (as DRAFT Components) → case record |
| INVESTIGATION | `impact_analysis` | "what breaks if X changes/fails" via reuse graph + schedule topology |
| SQL_GEN | `query_author` | NL → Dataset selection → SQL draft → `query_validate` repair loop → DRAFT Query |
| ANALYSIS | `analysis_assist` | Explore-pane copilot: Measure suggestions, drill paths, narrative of what the user is seeing |
| PIPELINE_AUTHOR | `pipeline_author` | NL + Source schema → TOON Pipeline draft → `parser_config_test` → `pipeline_simulate` → DRAFT + Expectations suggested from `profile_dataset` |
| PIPELINE_AUTHOR | `connection_troubleshoot` | connection workbench probes + circuit-breaker/ledger state → diagnosis + fix draft |
| QA | `kpi_report_builder` | goal → Measures → Widgets → Dashboard/report composition → schedule draft (closes C6 scheduled-reports backlog) |
| OPERATIONAL_ACTION | `ops_monitor` | event-driven triage loop: classify → correlate into Incidents → L3-eligible remediation or L2 proposal (generalizes `FailureReactor`) |
| OPERATIONAL_ACTION | `runbook_operator` | execute named runbooks stepwise, checkpointed, approval-gated |
| QA | `support_agent` | page-aware help: OKF/docs citations + `NavigationIntent` ("take me there") + settings diagnosis (`ModelDiagnoser`) |

## 5. Prompt architecture (versioned in-repo, eval-gated)

Layered `PromptProfile` — assembled, never hand-concatenated per call:

1. **Constitution** (one, small): identity ("Inspecto's embedded operator/analyst"), canonical
   vocabulary (inject GLOSSARY canon + hard bans), posture rules: *cite or abstain* · *numbers come
   from tools, never from the model* (enforced by `NumericGroundingGuard` anyway) · *propose via
   DRAFT, act only through gates* · *state uncertainty*.
2. **Per-GoalKind playbooks**: the method (e.g. RCA: scope → hypotheses → evidence per hypothesis →
   ranked conclusion), output contract (typed JSON / `AgentAnswer` kind incl. `INLINE_ARTIFACT` =
   Component draft, `NAVIGATION`, `CLARIFICATION`), tool conventions and stop conditions.
3. **Situation frame** (§2) — data, not instructions; injected with explicit "context, may be
   stale/untrusted" framing (prompt-injection hygiene: retrieved docs and tool outputs are DATA).
4. **Tier routing per step**: SMALL = extraction/classification/routing, MEDIUM = judgment/SQL/
   narrative, LARGE = deep RCA/multi-hypothesis synthesis (the existing `ModelTier` seam).

Prompts live in `inspecto-intelligence/src/main/resources/prompts/` (versioned, diffable), each
skill ships golden eval cases (the vendored eval harness + `StubLlmGateway`/`FakeModelProvider` for
CPU-only CI), and `AssistMetrics`-style per-skill counters feed tuning.

## 6. Autonomy ladder (governance in code)

| Level | May | Gate | Ships |
|---|---|---|---|
| **L0 Explain** | read tools only | capability check | default, all editions |
| **L1 Draft** | write DRAFT Components | validators; human applies | default (today's "propose, never dispose") |
| **L2 Act-with-approval** | prepare apply + **dry-run diff**; block on `ApprovalGate`; human approves in inbox; agent resumes from checkpoint | ApprovalGate + W6 approver identity + full audit | Standard+ |
| **L3 Bounded autonomy** | policy-enumerated action classes only (e.g. re-run failed batch ≤N, quarantine-and-notify, ack known-noisy alert, refresh stale report) | **Decision Rules** with budgets (count/time-window), rate limits, undo plan required, kill switch (`/agent/policy`), every act audited as `actor=agent` | Enterprise / opt-in |

Cross-cutting: confidence gate on every surface (abstain below threshold — unchanged posture);
the agent acts under `min(agent capabilities, requesting user capabilities)`; secrets never enter
context (references only — `SecretResolver` seam); no egress beyond the configured model endpoint
(packaging-tested).

## 6b. Autonomous operation map (no-chat invocation)

Chat is one ingress of four. The agent runs autonomously from:

| Trigger | Hook | Autonomous behavior (level) |
|---|---|---|
| **Events** | `BatchEventBus` subscription (generalized `FailureReactor`): FAILED batch, quarantine, Alert Rule fired, Expectation violation, schema drift, connection/circuit-breaker change, W5 run completion, config apply | triage → correlate → Incident + RCA case draft + evidence timeline (L1); prepared remediation (L2); Decision-Rule action (L3) |
| **Schedules** | scheduler ticks | nightly quality sweep (profile vs Expectations), Measure anomaly/trend scan, SLA/capacity forecast, scheduled report generation (C6), stale-artifact hygiene via reuse graph, self-eval run |
| **State-watch** | `ops_monitor` loop (StatusStore/metrics) | late-batch prediction, backlog/error-rate drift → advisory or L3 class |
| **Lifecycle hooks** | control-plane actions | new Source → profile + draft Pipeline scaffold + descriptions; draft save → validate + suggest Expectations; apply → impact annotation; Incident resolve → case memory write |

Autonomous outputs are **product artifacts, never chat**: DRAFT Components, Incident annotations,
approval-inbox items, case records — reviewable, diffable, undoable. (Precedent: the existing
`AiDescriptionProvider` already works exactly this way.)

## 6c. Data protection model (business data vs model)

Actuation is safe-by-construction (§6); the residual risks are **data egress** and **injection**:

1. **Context privacy classes** — every piece of context is classed: **P0** metadata (schema, names,
   counts) · **P1** aggregates · **P2** masked/sampled rows · **P3** raw rows. Policy binds
   *skill × provider locality × class*: hosted (non-local) models get P0–P1 by default; P2/P3
   require a local-tier model or explicit per-space opt-in; catalog PII tags drive column masking in
   the ContextBroker *before* frame assembly. Air-gap editions keep the packaging guarantee (no
   hosted SDK on classpath).
2. **Context manifest audit** — every model call logs which privacy classes/entities entered the
   prompt (keys/counts only, never values), making exposure reviewable per call.
3. **Injection hygiene** — retrieved docs/tool output framed as data; mutating tool arguments
   schema-validated, never free-text-derived; approval diffs carry evidence **provenance**;
   instruction-like content in retrieved chunks flagged.
4. **Shadow mode before L3** — each action class runs decide-and-log (no act) until its shadow
   record is reviewed; data-destructive operations are **never** L3-eligible.
5. **Model supply chain** — local models pinned + checksummed; hosted providers require
   zero-retention endpoints/ToS review before enablement.

## 7. Memory & learning loop

- **Session memory**: eoiagent `MemoryStore` (conversation), scratchpad for long artifacts.
- **Case memory**: generalize `DiagnosisStore` → **Case Store** (Incident → investigation →
  evidence → outcome), retrievable ("similar past Incident: …") via `LongTermMemory`/vector store
  once eoiagent-knowledge is adopted (in-memory first; embeddings optional per edition).
- **Feedback capture**: accept/edit/reject on every draft + approval decisions → labeled examples →
  golden eval growth (the flywheel: every human correction becomes a regression test).
- **Self-eval**: nightly scheduled eval run over the golden set + synthetic Incidents (seeded
  examples), reported as a Dashboard.

## 8. Phased execution (each phase independently shippable + GAUNTLET-gated)

**P0 — Platform + grounding (the spine). SHIPPED 2026-07-07 (product-owner sign-off given);
HARDENED 2026-07-08 (3 of the original 5 scope cuts closed).** Delivered: new
`inspecto-intelligence` module (`file-processor-intelligence`); core seam `com.gamma.intelligence.spi.
IntelligenceAgent` (mirrors `AssistAgent`'s SourceService/ServiceLoader lifecycle) +
`AgentSessionRequest/Result`, `AgentAskRequest/Result` wire records; `POST /agent/sessions` +
`POST /agent/sessions/{id}/ask` **+ `POST /agent/sessions/{id}/ask/stream` (SSE)** control-plane
routes (`AgentRoutes`, 503 when the module is absent); `InspectoPack` (the 8 `ApplicationPack`
seams) assembled via `PlatformBuilder` behind `InspectoIntelligenceAgent`; `InspectoModelProfile`
bridges `AssistModelSettings`/`ProviderSettings` so one settings screen configures both agent
modules; read tool belt v1 = `glossary_lookup` + `docs_search` (both over `docs/`) + `status_get`
(live `SourceService.pipelines()`); **a one-document RAG corpus** (`docs/GLOSSARY.md`, in-JVM ONNX
embeddings — `InspectoKnowledgeSources`) proven to ingest and retrieve for real in CI; navigation
catalog **derived automatically** from `inspecto-ui/src/app/app.routes.ts` (`RoutesCatalogLoader`,
`NavigationTool` auto-registers). Tests: `InspectoPackTest`, `InspectoIntelligenceAgentTest`
(deterministic `StubLlmGateway`, no live-model dependency), `AgentRoutesTest` (real-HTTP, mirrors
`ControlApiTest`'s pattern, incl. SSE coverage). Reactor green (1008/155/4/36/12).

*Scope cuts still open (each a deliberate, documented deferral, not an oversight):*
1. **QA only, no `incident_explain` yet** — the eoiagent host layer (`DefaultAgentSession.coreRun`)
   hardcodes `GoalKind.QA` for every `ask()` today; there is no host-level seam yet to select
   `INVESTIGATION`. `support_agent`-style Q&A + navigation is what P0 actually exercises;
   `incident_explain` waits on that upstream seam (or a P1 direct-`Orchestrator.run()` path).
2. **Local models only** — always `DeploymentProfile.OFFLINE`; a hosted-provider companion module
   (mirroring `inspecto-agent-hosted`) is deferred, consistent with the plan's own edition table
   (hosted providers are a Standard+ concern, not P0's).

*Scope cuts closed in the 2026-07-08 hardening pass:*
- **RAG corpus** — `InspectoKnowledgeSources` ships one `KnowledgeSource` (`docs/GLOSSARY.md`);
  `onnxruntime` + `langchain4j-embeddings-all-minilm-l6-v2` are both cached offline and bundle
  their model weights in-jar, so ingestion needs no network. Verified in CI: a real `RETRIEVAL`
  audit event fires (`retrieved 4 chunks ... sourceIds=[inspecto-glossary]`) during
  `InspectoIntelligenceAgentTest`. Widening to the full OKF/docs corpus is still a later phase.
- **SSE streaming** — `IntelligenceAgent.askStream` (additive default: single-shot `onComplete`
  fallback for any future provider that doesn't implement real streaming) + `AgentAnswerSink` port;
  `InspectoIntelligenceAgent` overrides it to forward real per-token streaming from
  `AgentSession.askStream`; `AgentRoutes` writes it out as `text/event-stream` (chunked transfer,
  `event: complete`/`event: error` terminal frames). An unknown session surfaces as an `error` SSE
  frame rather than a 404, since headers commit before the session lookup completes.
- **Navigation catalog auto-derivation** — `RoutesCatalogLoader` parses top-level `loadChildren`
  entries out of `app.routes.ts` (`RepoPaths` locates the repo root regardless of which module's
  directory Maven/production launches from); route params still aren't derivable from that file, so
  every page gets an empty param list — a known limitation until page components declare their own.

*Exit (revised for what actually shipped): page-aware Q&A + navigation answers with real corpus
citations, live off a running SourceService, deterministic under `StubLlmGateway`, real under a
configured local Ollama, streamable over SSE; reflex layer untouched; CPU-only CI green.
`incident_explain` and hosted-provider support move to P1/Standard+ alongside the items below.*

**P1 — Investigation.** `timeline_build`/`diff_batches`/`config_versions_diff`/`anomaly_scan`
tools; `root_cause_analysis` + `impact_analysis` playbooks; Case Store + `/agent/cases`; event
ingress (generalized FailureReactor → triage queue feeding the deliberative layer).
*Exit: a seeded Incident (broken batch + config change) yields a correct ranked RCA with evidence
and a fix draft, deterministically under the fake provider.*

**P2 — Author everything (L1).** `component_draft` across kinds + validator repair loop;
`pipeline_author` (parser test + simulate), `query_author`, `kpi_report_builder`,
Expectation/Alert-Rule suggestion from profiling.
*Exit: NL → valid DRAFT Pipeline/Query/Dashboard that a human applies unchanged in ≥ the golden-set
pass rate; every draft carries validation evidence.*

**P3 — Gated action (L2).** ApprovalGate wiring + approvals inbox (UI + routes) + checkpoint/
resume; act tools (`component_apply`, `job_run`, `schedule_apply`, `alert_ack`, `pipeline_rerun`,
`component_rollback`); `runbook_operator` with 2–3 seeded runbooks.
*Exit: end-to-end "agent proposes → dry-run diff shown → human approves → agent applies + verifies →
undo works", fully audited.*

**P4 — Bounded autonomy (L3).** Decision-Rule policy engine + budgets + kill switch; `ops_monitor`
loop; pilot action classes (batch re-run, alert triage); autonomy Dashboard (what the agent did,
why, spend).
*Exit: policy-bounded autonomous remediation on seeded failures with budget enforcement + undo,
and a hard-off switch proven live.*

**P5 — Learning.** Feedback capture → eval growth; case-similarity recall; per-skill tuning
dashboards; embedding retrieval upgrade if warranted.

## 9. Risks & mitigations

- **eoiagent SNAPSHOT / Phase-3.5 churn** → pin exact jars in `.m2`; adopt platform seams only
  (ports are stable per its ADRs); keep reflex layer as fallback; press for a 0.1.0 tag.
- **Latency/hardware (local tiers)** → tier routing per step, streaming UX, reflex-layer fast paths,
  aggressive abstain.
- **Prompt injection via ingested docs/data** → context framed as data; mutating tools never take
  free-text-derived targets without schema validation; gates are code.
- **Autonomy trust** → the ladder is opt-in per level, every L3 class needs an undo plan, audit is
  keys-only, kill switch is one call.
- **Token/context bloat** → ContextBudget everywhere; graph-scoped focus instead of dumps; OKF docs
  are chunk-friendly by design.

*Related: `agent-kernel-replacement-plan.md` (substrate), `agent-brainstorm-assessment.md` (both archived under `docs/archived-documents/plans-archive/`)
(platform evaluation), `component-model.md` (Component metamodel), `api-contract-design.md`
(governed control plane W1–W6), `assist-agent-improvement-plan.md` (reflex layer history).*
