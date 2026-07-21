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

## P3 gated-action tier (partially shipped — slices 1–5 of the plan)

The L2 "act" layer: mutating tools gated behind an approval, so the agent can apply what P2 only
drafted, drive the running system's operational verbs, run seeded multi-step remediations, and be
governed from an operator inbox UI. Five slices shipped (`a30049a`, `b5069c1`, `89bb1a5`,
`runbook_operator`, + the approvals-inbox UI), plus durable checkpoint/resume across restarts
(slice 6, `eoiagent-safety` `DecisionStore` + durable `ApprovalStore`). **P3 complete.**

- **Approval spine (slice 1)** — `action.Approval`/`ApprovalStore` (bounded ring, sibling of
  `CaseStore`; once-only guarded `PENDING→APPROVED/DENIED/TIMED_OUT`), `AgentApprovals` (an eoiagent
  `com.eoiagent.safety.ApprovalHandler` bridging the framework's **synchronous**
  `DefaultToolRegistry.dispatchMutating` blocking gate to an **async** inbox by parking the gate thread
  on a `CompletableFuture` until the operator decides). SPI adds `recentApprovals`/`approvalById`/
  `decideApproval` (default-degrading like `recentCases`); routes `GET /agent/approvals`,
  `GET /agent/approvals/{id}`, `POST /agent/approvals/{id}/decision`. Opt-in
  `-Dintelligence.act.enabled` (`AgentApprovals.ENABLED_FLAG`) gates both `InspectoPackConfig`
  `MUTATING_ACTIONS` and whether `start()` supplies the handler — lockstep, so without the flag the
  mutating belt stays hidden/fail-closed (default L0/L1 unchanged).
- **Component act tools (slice 2)** — `component_apply`/`component_rollback`
  (`action.ControlPlaneClient`, loopback HTTP with `X-Agent-Session`; apply = validate→GET→`If-Match
  PUT` (existing) / `POST` create; rollback = `POST /versions/{v}/restore`; plus a read-only `preview`
  diff). Registered in `InspectoTools` (`mutating=true`, `Capability.EDIT_CONFIG`), always on the belt
  but hidden/fail-closed unless the feature flag is on. `component_apply` hard-refuses anything
  `ConfigSafetyValidator` rejects. `ControlApi` publishes its loopback base URL
  (`LOCAL_BASE_URL_PROP`, cleared on close) so the in-JVM act tools reach the same audited
  control-plane contract a UI caller does — no backdoor. The previewer is wired into `AgentApprovals`
  in `InspectoIntelligenceAgent.start()` (read-only `ComponentActions.preview` over
  `assist.write.root/registry`).
- **Operational act tools (slice 3)** — four mutating tools in `action.OperationalActions`, each riding
  the **same** governed control-plane route a UI caller hits (no backdoor), attributed via
  `X-Agent-Session`: `job_run` (`POST /jobs/{name}/trigger`, `Capability.TRIGGER_JOB`),
  `pipeline_rerun` (replay a committed batch — the RCA remediation verb — `POST /runs/{pipeline}/reprocess`
  with `{batchId}`, `RUN_PIPELINE`), `alert_ack` (acknowledge an Alert-Center object,
  `POST /objects/{id}/ack`, `WRITE_DATASTORE`), `schedule_apply` (change a job's cron,
  `POST /jobs/{name}/reschedule` with `{cron}`, write-root-gated server-side, `EDIT_CONFIG`). Belt is
  now **18** (14 read/draft + 4 component/operational act tools were 2, now the two families total 6
  mutating). The approval previewer in `start()` now dispatches by tool family — operational tools get a
  read-only `OperationalActions.preview` (action summary + cheap live `CollectorService` state: does the
  job exist, is the pipeline paused) alongside the component diff. All fail closed unless
  `-Dintelligence.act.enabled` is set.
- **Route naming reality (verified)** — the plan's tool names don't map 1:1 to route names: there is no
  `/alerts/{id}/ack` (alerts are acked as operational **objects**, `POST /objects/{id}/ack`); no
  `/schedules` resource (a schedule is a job's `cron`, changed via `/jobs/{name}/reschedule`); no
  `/pipelines/.../rerun` (the replay verb is `POST /runs/{name}/reprocess`, batch-scoped). The tools use
  the real routes; the tool names stay plan-aligned.
- **Compound runbook operator (slice 4)** — `runbook_operator` (`action.RunbookActions`) executes a
  **named, seeded** runbook — a fixed, code-defined ordered sequence of the existing act tools — as
  **one** approval-gated unit. The model picks a runbook name + params (it cannot author the steps); the
  operator approves the full resolved plan (the preview lists every step + args), and each step then runs
  post-approval by dispatching to the same `ComponentActions`/`OperationalActions` executors over the
  audited control plane (`X-Agent-Session` per step). Execution is stepwise with a per-step log and
  **halt-on-first-failure** (`success`/`completed`/`haltedAtStep`); a pre-flight error (unknown runbook,
  missing params) mutates nothing. `mutating=true`, `EDIT_CONFIG`; belt now **19**. Seeded runbooks:
  `triage_and_replay` (alert_ack → pipeline_rerun), `rollback_and_rerun` (component_rollback → job_run),
  `reschedule_and_trigger` (schedule_apply → job_run). *One approval for the whole plan* (not per step) is
  the deliberate first cut — runbooks are code-defined so the operator sees exactly what will run, and it
  sidesteps the framework's per-call parked-thread gate (nesting gated calls would deadlock).
- **Approvals-inbox UI (slice 5)** — the operator surface in `inspecto-ui`
  (`modules/admin/approvals/`, route `/approvals`, Operations nav). An `ApprovalsService`
  (`inspecto/api/approvals.service.ts`) wraps `GET /agent/approvals[/{id}]` and
  `POST /agent/approvals/{id}/decision`; the standalone `ApprovalsComponent` lists requests in the
  shared `<inspecto-data-table>` (tool, actor, status badge, summary), and — Ops-gated on
  `LensService.canOperateRuns` — offers Approve/Decline row actions on PENDING rows. Deciding opens the
  shared confirm dialog carrying the request's dry-run `preview` + arguments (pretty-printed), then
  reflects the terminal status in place. Reads degrade to an empty inbox + toast (module absent / act
  tier off). Vitest specs cover the service wire contract and the component (gating, PENDING-only
  actions, approve/decline, failure degrade, a11y).
- **Checkpoint/resume across restarts (slice 6)** — a cross-repo eoiagent change added a host-supplied
  `com.eoiagent.safety.DecisionStore` seam: `CallbackApprovalGate` consults it *before* prompting the
  `ApprovalHandler` and records the outcome; `PlatformBuilder.approvalDecisionStore(...)` wires it and
  the gate's timeout is now config-driven (`eoiagent.approval.timeout`/`.onTimeout`,
  `eoiagent-safety` 0.2.0-SNAPSHOT, commit `d6fabb3`). Inspecto side: `ApprovalStore` is durable
  (JSON-lines at `<assist.write.root>/agent/approvals.jsonl`) so pending approvals + undelivered
  operator decisions survive a restart; `AgentApprovals` *implements* `DecisionStore` — a decision made
  while no run is parked is held as a **one-shot resume token** keyed by tool + arguments (TTL 1h,
  consumed atomically under the store lock), admitting the re-issued identical call without
  re-prompting. A live-delivered decision is `markConsumed` so it can never double as a token; the
  approval window is widened to `PT30M` via `InspectoPackConfig`. Without a write root the store is
  in-memory (dev/tests), behaving as before. (Mid-plan runbook resume — re-entering a halted plan at
  the failed step rather than the start — is deferred to P4.)
- **Gotchas**: the eoiagent gate has no per-tool `DryRunProvider` seam through `PlatformBuilder` (it
  only wires the `ApprovalHandler`), so the operator-facing diff is computed by `AgentApprovals`' own
  previewer, not the framework's `approvalGate.dryRun` — `ApprovalRequest.preview` is empty by design,
  ours rides the inbox `Approval.preview`. Two `ApprovalHandler` types exist — use
  `com.eoiagent.safety.ApprovalHandler` (what `PlatformBuilder.approvalHandler(...)` and
  `CallbackApprovalGate` take), not `com.eoiagent.host.*`. `X-Agent-Session` = the eoiagent `RunId`
  (per-investigation), so the audit actor is `agent:<run>`; `requireCapability` passes in the
  auth-free core (no `Subject`), same as the S6 agent-invoke path — a secured edition gates it at the
  `ApiContext`/`WriteGates` seam.

## P4 — bounded autonomy (L3), in progress

Autonomous (un-prompted) action, gated by an operator-set policy. **Slices 1–2 shipped 2026-07-21**
(policy substrate + the `ops_monitor` loop with a first pilot action class); the autonomy dashboard UI
and a second pilot class (alert triage) are the remaining slices.

- **Policy substrate (slice 1)** — package `com.gamma.intelligence.policy`. `AutonomyPolicy` is an
  immutable doc: a global `killSwitch` + a map of action-class → `ClassPolicy {mode, maxPerHour,
  maxPerDay}` where `mode ∈ {OFF, SHADOW, AUTO}` (an *unconfigured* class = `OFF`, so nothing acts
  until opted in). `AutonomyPolicyStore` persists it as a single JSON doc at
  `<assist.write.root>/agent/policy.json` (durable kill-switch/budget state across restart; in-memory
  without a write root). `ActionBudget` is an in-memory rolling-window counter (trailing hour + day,
  clock-injectable; a restart re-opens the window — conservative). `AutonomyPolicyEngine.authorize
  (actionClass)` is the single decision point, folding **killSwitch → mode → budget** in priority:
  `DENY` (killed / OFF / exhausted), `SHADOW` (evaluate + log, never execute, no budget spent), or
  `ALLOW` (executes — one budget unit already consumed atomically, so concurrent callers can't exceed
  the cap). The engine is **inert until a driver calls `authorize`** — building it wires no autonomous
  behaviour by itself.
- **Routes/SPI** — `GET /agent/policy`, `PUT /agent/policy` (replace), `POST /agent/policy/kill-switch`
  `{engaged}` (the one-call hard-off). New `IntelligenceAgent` SPI methods `autonomyPolicy` /
  `updateAutonomyPolicy` / `setAutonomyKillSwitch` (empty ⇒ 503, a genuine "no L3 tier", unlike the
  read-degrading approvals/cases). Writes attribute the calling actor and are audited by
  `ControlApi.dispatch`; a secured edition prepends the `agent.admin` capability gate. The engine is
  wired into `InspectoIntelligenceAgent` (durable store when a write root is set), always present when
  the module is loaded so operators can configure policy before any driver exists.
- **ops_monitor loop (slice 2)** — `OpsMonitor` is the L3 driver: a live `EventLog` subscriber
  (same `ingestLock`-safe type-check→bounded-offer→daemon-vthread-handoff as `TriageQueue`; opt-in
  `-Dintelligence.opsmonitor.enabled`) that watches for a `pipeline.batch.failed` Signal (pilot action
  class `batch_rerun`), extracts pipeline (subject) + batchId (correlationId), dedupes per batch, and
  calls `AutonomyPolicyEngine.authorize("batch_rerun")`: **DENY** → record SKIPPED + do nothing;
  **SHADOW** → record SHADOWED ("would rerun …"), never execute; **ALLOW** → `Remediator` replays the
  batch via the same audited `pipeline_rerun` path an operator's approval would (actor
  `agent:ops-monitor`), recording SUCCEEDED/FAILED. Never chases `agent.*` self-telemetry. Where
  `TriageQueue` (L1) turns a failure into an *investigation*, `OpsMonitor` (L3) turns it into a
  bounded *action*. Every decision (incl. denials/shadows) lands in the in-memory `AutonomyLog`
  (`ActionRecord`s), surfaced via `GET /agent/actions[/{id}]` (SPI `recentAutonomousActions` /
  `autonomousActionById`, read-degrading) — the dashboard's "what/why/spend" feed. The control plane's
  append-only `AuditTrail` remains the durable system of record; the ledger is a live view.
- **Verified**: `AutonomyPolicyEngineTest` (10: mode/kill/budget precedence, daily-vs-hourly windows,
  durable reload, malformed-mode tolerance, concurrent-authorize-never-exceeds-cap), `OpsMonitorTest`
  (9: off/shadow/auto/fail paths, kill-switch override, budget exhaustion, end-to-end dedup over the
  bus, non-trigger/agent-signal ignore), `AgentRoutesTest` +7 (policy 503/GET/PUT/kill-switch/400 +
  actions). Module 100→119.

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

P2 remainder (`query_author`, `kpi_report_builder` — see the P2 tier above for why deferred) · P3 is
complete (mid-plan runbook resume deferred to P4) · P4/P5 ·
Case persistence + similarity recall · hosted providers (Standard+) · the optional S8 signal-backbone
slice. See `docs/BACKLOG.md`.
