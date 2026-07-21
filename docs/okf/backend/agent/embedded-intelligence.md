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

## P2 authoring tier (partially shipped — 4 of 5 tools)

The L1 "author everything" layer: the agent proposes a config, the tools validate/simulate/profile it
deterministically, and a clean draft is one a human applies unchanged (apply itself is P3). All four
are `FunctionTool`s on the belt (now **13**), `mutating=false` — they persist nothing.

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
- **`query_author`** (structured tree → trusted SQL) — the model supplies a dataset ref + a structured
  condition tree (`{kind:group, op, items:[{field,operator,value}]}`); it **never writes SQL text**. The
  server resolves the dataset config from the `ComponentStore`, renders the trusted relation via
  `DatasetRelation.relationSql(config, service.dataRoot(), views)`, renders the `WHERE` via
  `ConditionSql.predicate(when)` (`"TRUE"` = no constraint), assembles `SELECT * FROM (<rel>) AS __q
  [WHERE <pred>]`, and guards the whole statement with `SqlGuard.check`. Returns a validated DRAFT
  `query` component (`{type:sql, text, datasetId}`) with anchored `Finding`s — it persists nothing;
  `component_apply` is the L2 gated write. `Capability.AUTHOR_PIPELINE`. The seam the backlog named is
  now threaded: `dataRoot` = new `CollectorService.dataRoot()` accessor (mirrors the BI-5
  `DatasetMeasureProbe` resolution — `-Ddata.dir` over the space root's `dataDir`), `ViewStore` =
  `InspectoTools.defaultViews()` (`-Dassist.write.root/views`); no `tools(...)` signature change.

**Deferred (this shift):** `kpi_report_builder`. It has no confirmed target component kind (no KPI/report
kind in `ComponentRegistry`; it would compose `MeasureCompiler` measures into a `dashboard`, which has no
`ConfigSpec`) — needs the dashboard-tile owner's sign-off before scoping.

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

## P4 — bounded autonomy (L3), complete (+ polish)

Autonomous (un-prompted) action, gated by an operator-set policy. **P4 complete 2026-07-21** (policy
substrate + the `ops_monitor` loop with a first pilot action class + the autonomy dashboard UI). A
second pilot class (alert triage) and a periodic state-watch trigger are deferred polish.

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
- **Autonomy Dashboard (slice 3)** — the operator surface in `inspecto-ui` (`modules/admin/autonomy/`,
  route `/autonomy`, Operations nav leaf). A kill-switch card (engage/disengage, confirm-gated), a
  per-action-class editor (mode `OFF|SHADOW|AUTO` + hourly/daily budget inputs, persisted as a
  full-policy `PUT`), and the action ledger over `GET /agent/actions` (what/why/spend, incl. shadow +
  skipped rows). Pilot classes (`batch_rerun`) are always surfaced so they can be configured before
  first use. All editing Ops-gated (`canOperateRuns`); degrades to a disabled "unavailable" state on a
  policy 503. `AutonomyService` (`policy`/`updatePolicy`/`setKillSwitch`/`actions`) + barrel.
- **Verified**: `AutonomyPolicyEngineTest` (10: mode/kill/budget precedence, daily-vs-hourly windows,
  durable reload, malformed-mode tolerance, concurrent-authorize-never-exceeds-cap), `OpsMonitorTest`
  (9: off/shadow/auto/fail paths, kill-switch override, budget exhaustion, end-to-end dedup over the
  bus, non-trigger/agent-signal ignore), `AgentRoutesTest` +7 (policy 503/GET/PUT/kill-switch/400 +
  actions); intelligence module 100→119. UI: `autonomy.component.spec.ts` (8) + `AutonomyService`;
  `npm run test:ci` 276 files/1533 pass, `npm run build` clean.

## P5 — learning, complete

Turning operator judgement into eval growth + tuning. **P5 complete 2026-07-21** (feedback capture +
case-similarity recall + the learning dashboard UI). With it, the full AGT-5 P0–P5 roadmap is shipped.

- **Case feedback (slice 1)** — `investigation.Feedback` (`{id, caseId, rating HELPFUL|NOT_HELPFUL,
  note, submittedBy, at}`) + durable `FeedbackStore` (JSON-lines at
  `<assist.write.root>/agent/feedback.jsonl`, the `ApprovalStore` ring idiom; in-memory without a write
  root). Feedback is durable and **outlives the ephemeral `CaseStore`** (256-deep, in-memory) — the
  `caseId` is the join key, so a rating survives even after its Case is evicted. `POST
  /agent/cases/{id}/feedback` (`rating` synonyms parsed by `Feedback.parseRating`; unknown case → 404,
  bad rating → 400, missing rating → 400), SPI `recordCaseFeedback` / `recentCaseFeedback`. The Case's
  `GET /agent/cases/{id}` detail view folds in its `feedback[]`; `GET /agent/feedback` lists the corpus.
- **Case-similarity recall (slice 2)** — `CaseStore` is now durable (`<assist.write.root>/agent/cases.jsonl`,
  `ApprovalStore` ring idiom; in-memory without a write root, as through P1–P4) so the corpus survives a
  restart and backs recall. `CaseSimilarity` = **Jaccard token overlap** of two `Case.symptomText()`
  fingerprints (signal type + subject + message + payload keys + hypothesis titles; tokens < 3 chars
  dropped) — deterministic, dependency-free; the "embeddings if warranted" upgrade is a drop-in behind
  `CaseSimilarity.score` and is **not warranted** at this scale. `CaseStore.similar(text, k, excludeId)`
  → top-k positive matches (score desc, newest tie-break), each `Case.toView()` + a `similarity` score.
  SPI `similarCases(id, k)`; `GET /agent/cases/{id}/similar?k=` (registered before the greedy
  `/agent/cases/(.+)` so it wins first-match; 404 unknown case, else neighbours). `Case` gained
  `toRecord`/`fromRecord`/`symptomText`.
- **Verified**: `FeedbackStoreTest` (5), `CaseRecallTest` (4: Jaccard scoring + short-token drop,
  ranking + self-exclude, empty on blank/​k≤0, durable reload + recall over reloaded corpus),
  `InspectoIntelligenceAgentTest` +1 (feedback), `AgentRoutesTest` +3 (feedback 400/404/200, list,
  similar 200/404). Module 119→129.
- **Learning dashboard (slice 3)** — the operator surface in `inspecto-ui` (`modules/admin/learning/`,
  route `/learning`, Operations nav leaf). Helpful-rate KPIs (total / helpful / not-helpful /
  helpful-rate %) over the feedback corpus + the recent-feedback ledger in the shared
  `<inspecto-data-table>`. `LearningService` (`feedback` / `rateCase` / `similarCases`) + barrel.
  Read-only; degrades to an empty state + toast. `learning.component.spec.ts` (4: KPI aggregation,
  empty-corpus rate, failure degrade, a11y); `npm run test:ci` 277 files/1537 pass, `npm run build` clean.
- **Polish — 2nd pilot class + periodic state-watch (2026-07-21)** — `OpsMonitor.attachStateWatch
  (scanner, intervalSeconds)` adds a poll-driven driver (own daemon scheduler; opt-in
  `-Dintelligence.opsmonitor.statewatch.seconds>0`) beside the event path. Each poll asks a
  `StateScanner` for `Finding`s (action class + subject + dedupe key) and funnels them through the same
  `decideAndAct` (`authorize → deny/shadow/execute → AutonomyLog`). The scanner (in
  `InspectoIntelligenceAgent.scanRemediableState`) reads open `ALERT` operational objects in-process
  via `service.objects().query(type=ALERT,status=OPEN)` — each object carries its own id + pipeline, so
  unlike the `alert-rule.fired` Signal (subject=pipeline, no alert id) there is **no id gap** — and
  emits the second pilot class **`alert_triage`**, remediated by the audited `alert_ack` (`OPEN →
  ACKNOWLEDGED`); an acked alert leaves the scan, so status is the natural dedup. `OpsMonitorTest`
  9→**12** (state-watch poll dedup, mode/kill respect, throwing-scanner swallowed); dashboard class
  editor surfaces `alert_triage`. Module 129→**132**.
- **Mid-plan runbook resume (2026-07-21, closes the last P3 deferral)** — a durable `RunbookRunStore`
  (JSON-lines at `<assist.write.root>/agent/runbook-runs.jsonl`, `ApprovalStore` idiom) checkpoints each
  seeded-runbook execution keyed by `(runbook, canonical params)`. `RunbookActions.execute(..., runs)`
  consults `resumeIndex(key)` and starts at the first not-yet-completed step, skipping (not re-running)
  already-succeeded ones — logged as `skipped`; the result carries `resumedFromStep`. A checkpoint is
  written after each successful step and a terminal marker on completion, so a **terminal** run is not
  resumed (a re-invocation runs afresh) while a **halted** one resumes — across a restart when a write
  root is set. The operator re-approves the resumed call through the gate (no bypass). The 3-arg
  `execute` overload (in-memory store) preserves the pre-resume behaviour for tests. `RunbookActionsTest`
  7→**10** (resume-at-failed-step + skip, terminal-runs-afresh, durable resumeIndex reload). Module
  132→**135**.

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

The AGT-5 phased roadmap **P0–P5 is complete**, plus P4 polish (2nd pilot class `alert_triage` +
periodic state-watch, shipped 2026-07-21). Remaining items are deliberate deferrals, not gaps:
`kpi_report_builder` (P2 — target `dashboard` kind
exists but has no `ConfigSpec`, so it would emit unvalidated drafts; needs tile-shape owner sign-off) ·
the embedding-retrieval upgrade (assessed **not warranted** at the 256-cap corpus; drop-in seam preserved
behind `CaseSimilarity.score`) · hosted providers (Standard+) · the optional S8 signal-backbone slice.
One actionable cross-repo gotcha remains: the eoiagent gate has no per-tool `DryRunProvider` seam
through `PlatformBuilder` (a refactor to let the framework populate `ApprovalRequest.preview` instead of
`AgentApprovals`' own previewer — functional parity today, so low priority). See `docs/BACKLOG.md`.
