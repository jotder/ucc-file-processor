# Embedded intelligence (AGT-5)

The deliberative AI layer, distinct from the reflex [[assist-agent]]: a multi-turn agent on the
vendored **eoiagent** platform (`com.eoiagent.*`, built from `jotder/inspect-agent` into the local
`.m2`), discovered via the `IntelligenceAgent` SPI and absent from a minimal/air-gapped build. Lives
in the optional `inspecto-intelligence` module (`file-processor-intelligence`); the lean core holds
only the SPI seam (`com.gamma.intelligence.spi.IntelligenceAgent`) and the `/agent/*` routes
(`AgentRoutes`), every one of which degrades to **503** when the module is absent.

## P0 spine (shipped)

`InspectoIntelligenceAgent` assembles an `InspectoPack` on the eoiagent platform and hosts multi-turn
`AgentSession`s keyed by a server-issued id. Routes: `POST /agent/sessions`, `.../ask`,
`.../ask/stream` (SSE). QA-only, local-models-only (`DeploymentProfile.OFFLINE`, gateway from
`GatewayFactory`; a hosted provider falls back to an offline stub). One-doc RAG corpus (GLOSSARY.md).
Read tool belt = `InspectoTools.tools(service)`.

## P1 investigation tier (shipped — all slices A–E)

The read tools, deliberative memory, RCA reasoning, and autonomous triage that turn a breach into a
ranked root-cause **Case** with a fix draft.

- **Analysis tools (slice A)** — four read-only `FunctionTool`s on the belt (belt is now 9):
  `timeline_build` (signals + job runs + component saves, one ordered window), `diff_batches` (two
  batch-ledger entries compared), `config_versions_diff` (ComponentStore `.history` structural diff),
  `anomaly_scan` (deterministic z-score/threshold SQL — model judges, tools compute). All
  `mutating=false`, `Role.USER`. Belt exposed via `InspectoTools.tools(service, components, browseStores)`.
- **Case Store (slice D)** — `investigation.CaseStore`, an in-memory bounded ring (synchronized
  `ArrayDeque`, evict-oldest; modeled on `DiagnosisStore`), holding `Case` records (incidentRef,
  trigger Signal, timeline snapshot, hypotheses+evidence, outcome, fix-draft refs, timestamps).
  Projected via two additive SPI defaults (`recentCases`/`caseById`, empty-degrading like
  `/assist/diagnoses`) to `GET /agent/cases[/{id}]` (404 on unknown id). Separate from the reflex
  layer's `DiagnosisStore`.
- **Playbooks (slice C)** — `pack.Investigator` runs a playbook as **deterministic tool-orchestration
  + single-shot model synthesis** (not model-driven ReAct): gather evidence by invoking the analysis
  tools in fixed order, then one `gateway.chat` for ranked hypotheses + fix draft (JSON), parsed
  fail-closed into a `Case`. Fix drafts persist as DRAFT `ComponentStore` components (`status:draft`,
  `authoredBy:agent:rca`, L1 — never applied) when a write root exists. Versioned prompts under
  `resources/prompts/` (`root_cause_analysis.v1`, `impact_analysis.v1`).
- **Triage ingress (slice E)** — `investigation.TriageQueue`, a canonical **Signal-bus** subscriber
  (`service.eventLog().addSubscriber`), parallel to `context.SignalIngress`. **Autonomy is opt-in**:
  off unless `-Dintelligence.triage.enabled=true` (`TriageQueue.enabled()`); the agent wires it in
  `start()` only when enabled. On an error/critical Signal it runs `Investigator.investigate` (L1) and
  files a Case. Dedupes by correlationId, excludes `agent.*` (no self-investigation loop). Two new
  canonical Signals emit additively at their eval sites: `alert-rule.fired` (`AlertService.fire`) and
  `expectation.violated` (`ExpectationRoutes.raiseIncident`), alongside the legacy `ALERT_FIRED`/
  `EXPECTATION_FAILED` events.
- **goalKind seam (slice B)** — the eoiagent host reads an optional `"goalKind"` **session attribute**
  (`DefaultAgentSession.coreRun`, default QA; unknown → QA leniently), letting a host pin
  `INVESTIGATION`. Inspecto: `AgentSessionRequest.goalKind` → `AgentRoutes` maps an unknown kind to
  **400** (validated in the intelligence module, which owns the enum) → `openSession` puts the
  validated value in the session attributes.

## P2 authoring tier (partially shipped — 3 of 5 tools)

The L1 "author everything" layer: the agent proposes a config, the tools validate/simulate/profile it
deterministically, and a clean draft is one a human applies unchanged (apply itself is P3). All three
are `FunctionTool`s on the belt (now **12**), `mutating=false` — they persist nothing.

- **`component_draft`** (the validator repair loop) — validates a proposed draft against the *same*
  gates the control plane enforces on write: structural spec (`ConfigLoader.filesystem().validate(
  ConfigSpecs.forType(type), draft)`) + the hard-fail safety gate (`ConfigSafetyValidator.check`,
  applied **always** here, not opt-in as on `/validate`). Returns anchored `Finding`s (as maps
  `{severity,fieldPath,message}`) so the model repairs and re-validates until `clean=true`. Kinds =
  the spec-backed set: `pipeline|enrichment|job|schema|expectation|alert-rule` (`alert-rule`→`alert`
  spec); an unvalidatable kind (e.g. `dashboard`, `query` — no `ConfigSpec`) is an honest `ok=false`.
  `Capability.AUTHOR_PIPELINE`.
- **`pipeline_author`** (parse + simulate) — parses a proposed authored-flow graph via
  `PipelineCodec.fromMap` (malformed shape → `ok=false`), then, given `sampleRows` (post-parse
  records), simulates the `transform→sink` subgraph on a throwaway DuckDB via the editor's own
  `PipelineDryRun.run`, returning per-node relation counts + per-sink row counts. No sampleRows →
  parse-only. `Capability.AUTHOR_PIPELINE`.
- **`suggest_expectations`** (profiling → drafts) — profiles a column of a DB-backed `BrowsableStore`
  (row/null/distinct counts + numeric min/max, deterministic SQL like `anomaly_scan`) and derives
  candidate `expectation` drafts: `non_null` when the column was never null, `range` from observed
  bounds when it is fully numeric. Each suggestion is the exact expectation config shape
  `component_draft` validates (the loop closes: profile → suggest → validate → apply).
  `Capability.READ_METADATA`.

**Deferred (this shift):** `query_author` and `kpi_report_builder`. `query_author` needs trusted
dataset→relationSql resolution (`DatasetRelation.relationSql` wants a `dataRoot` + `ViewStore`, not on
the current tool-belt seam) — the model must **not** supply `relationSql` (it bypasses the SqlGuard
file-read boundary), so this is its own slice. `kpi_report_builder` has no confirmed target component
kind (no KPI/report kind in `ComponentRegistry`; it would compose `MeasureCompiler` measures into a
`dashboard`) — needs the dashboard-tile owner's sign-off before scoping.

## Gotchas / seams

- **`ingestLock` deadlock rule** governs every `EventLog`/Signal-bus subscriber: subscribers run
  synchronously on the emitting thread (which may hold `ingestLock`), so `TriageQueue`/`SignalIngress`
  do only a type-check + bounded-queue offer on that thread, then hand off to their own daemon
  virtual-thread executor. Never run investigation inline. See [[signal-backbone]].
- **eoiagent SNAPSHOT distribution**: Inspecto CI builds eoiagent from `jotder/inspect-agent` source
  (`main`, currently `0.2.0-SNAPSHOT`) into the runner's `.m2` — no package registry. A change eoiagent
  needs (e.g. the goalKind seam) must be **pushed to that remote** before Inspecto master consumes it,
  or CI's rebuilt jar won't have it. Inspecto pins `eoiagent.version` in both `inspecto-agent` and
  `inspecto-intelligence` poms.
- **Deterministic tests**: `StubLlmGateway.builder().defaultReplyText(...)` (+ `.replyToolCalls`/
  `.replyText` FIFO for scripted multi-turn); package-private `InspectoIntelligenceAgent(gateway)` and
  `Investigator(service, components, browseStores, gateway)` test ctors; `toResult(AgentAnswer)` seam.
- **`EventLog.global()` is JVM-wide** — scope Signal-tool test assertions by a unique correlationId.
- **Two parallel bus systems stay separate**: the canonical Signal bus (`EventLog`, `EventType.SIGNAL`)
  vs. the legacy `BatchEventBus` (`FailureReactor`) — no migration, per the standing `ingestLock`
  decision.

## Still open (parent plan `embedded-intelligence-plan.md`, §8)

P2 remainder (`query_author`, `kpi_report_builder` — see the P2 tier above for why deferred) · P3
(approvals inbox + act tools, L2+) · P4/P5 · Case persistence + similarity recall · hosted providers
(Standard+) · the optional S8 signal-backbone slice. See `docs/BACKLOG.md`.
