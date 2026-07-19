# AGT-5 P1 — Investigation tier (design of record)

*2026-07-19. Executes `embedded-intelligence-plan.md` §8 P1. Substrate mapped against live code
this date; S1/S5 signal-backbone work already delivered the signal tools, ContextBroker frame,
and agent telemetry ledger. This plan covers what §8 P1 lists as still open.*

**Exit (unchanged from the parent plan):** a seeded Incident (broken batch + config change) yields
a correct ranked RCA with evidence and a fix draft, deterministically under the fake provider.

---

## 0. Substrate facts this design stands on (verified 2026-07-19)

- Tool belt = static `InspectoTools.tools(service)` (`inspecto-intelligence/.../pack/InspectoTools.java:40-43`);
  each tool is a `ToolSpec` + `FunctionTool(spec, body)` record, `ok=false` for expected failures,
  never throws. `signal_timeline` (~35 lines) is the shape template.
- `ContextBroker.frame(role, page)` composes the situation frame once per session into
  `SessionRequest.attributes["situation"]`, budget-capped 3000 chars.
- `GoalKind.QA` is hardcoded in eoiagent-host `DefaultAgentSession.coreRun:102`
  (`orchestrator.run(new Goal(text, GoalKind.QA), ctx, onToken)`). `GoalKind.INVESTIGATION` and
  `Orchestrator.run(Goal, AgentContext)` exist on the classpath but are not reachable from
  Inspecto. **The parent plan pre-authorized either an upstream seam or a direct-run path.**
- `DiagnosisStore` = synchronized in-memory ring (256), written by `FailureReactor` (subscribes
  `BatchEventBus`, FAILED only, virtual-thread drain), read via `GET /assist/diagnoses`.
- `BatchEventBus` and the canonical Signal ledger are **deliberately parallel** (signal-backbone.md:
  full bus migration declined over the `ingestLock` deadlock risk). Signal types today: `job.*`,
  `pipeline.batch.committed|failed`, `pipeline.commit`, `decision-rule.applied`, `agent.*`.
  **No Signal for Expectation violation or Alert Rule fired.**
- `ComponentStore` keeps `.history/<id>.v<N>.toon` version chains (`versions()`,
  `versionContent()`, MET-5). Other stores (PipelineStore etc.) unverified for parity — slice A
  confirms and scopes `config_versions_diff` to what actually has history.
- Route house style (`AgentRoutes`): parse → 400 missing-field → `agentOr503` → act →
  `IllegalArgumentException`→404.
- Deterministic tests: `StubLlmGateway.builder().defaultReplyText(...)`, package-private
  `InspectoIntelligenceAgent(gateway)` test constructor, `toResult(AgentAnswer)` seam for answer
  kinds not producible live. Tool bodies unit-tested directly in `InspectoToolsTest`.

## 1. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | **Upstream seam in eoiagent-host**: `SessionRequest`/ask carries an optional `goalKind` attribute; `DefaultAgentSession.coreRun` reads it, defaulting to `QA`. Bump nothing public otherwise; rebuild 0.1.0-SNAPSHOT into local `.m2`. | Smallest change that unlocks `INVESTIGATION`; parent plan named this path first; we own the repo. Direct `Orchestrator.run` would need `AgentPlatform` to leak an internal bean — a bigger API change. |
| D2 | **Case Store is in-memory first** (bounded ring, richer record), living in `inspecto-intelligence`, not `inspecto-agent`. `DiagnosisStore` stays untouched (reflex layer regression-free). | Parent plan §7 says in-memory first. The reflex `DiagnosisStore` keeps working as-is; the Case Store is the deliberative layer's memory. |
| D3 | **Event ingress rides the Signal bus** (S1 unified in-process bus), not `BatchEventBus`. A `TriageQueue` subscribes to error/critical Signals, dedupes by correlationId, and (when the deliberative layer is on) runs an investigation, writing a Case. | Signal ledger is the queryable, persisted, canonical channel; `BatchEventBus` stays untouched per the standing `ingestLock` decision. |
| D4 | **Add the two missing Signal emissions** — `expectation.violated` and `alert-rule.fired` — at their evaluation sites, additive, through the existing enforced type catalog. | RCA is blind to quality events without them; cheap, additive, same pattern as `pipeline.batch.*`. |
| D5 | `timeline_build` **supersets** `signal_timeline`: merges Signals (all, not just one causation chain), job runs, and ComponentStore version saves touching the focus window into one ordered timeline. `signal_timeline` stays (cheap causation-chain case). | The RCA playbook needs "everything that happened around T", not one chain. |
| D6 | `anomaly_scan` = deterministic math only (threshold + z-score over a numeric column via read-only DuckDB query), no model involvement. Model judges, tools compute. | Parent plan §3 doctrine. |
| D7 | Fix drafts land as **DRAFT Components via the existing `ComponentStore`** write path with `actor=agent:*` audit — no new write machinery; P1 stays L1 (draft, never apply). | §6 ladder; S6 already proved the audit pattern. |

## 2. Slices (each independently shippable, GAUNTLET-gated)

> **Progress (2026-07-20):** Slice **A SHIPPED** (`feat(agent)` 7f34711 — the four tools, belt now
> 9). Slice **D SHIPPED** (Case Store + `GET /agent/cases[/{id}]`, 60469fc). Slice **C SHIPPED**
> (RCA/impact playbooks — see D-note below). Remaining: **E** (triage ingress + missing Signals),
> **B** (eoiagent goalKind seam — cross-repo, deferred to last per operator). Chosen sequence:
> D→C→E in-repo, then B.
>
> **Slice C as-built (deviation from the ReAct sketch):** RCA runs as a **deterministic
> tool-orchestration + single-shot model synthesis** (`Investigator`), not model-driven ReAct — the
> eoiagent session path is hardwired to `GoalKind.QA` until slice B, and a fixed recipe is fully
> deterministic under the stub gateway. The playbook gathers evidence by invoking the analysis tools
> in fixed order (timeline_build → config_versions_diff → diff_batches → anomaly_scan, each
> best-effort), then asks the model ONCE for ranked hypotheses + a fix draft (JSON), files a `Case`,
> and persists the fix draft as a DRAFT `ComponentStore` component (`status:draft`,
> `authoredBy:agent:rca`) when a write root exists. Playbooks are versioned resources under
> `resources/prompts/`. `impact_analysis` ships as a wired playbook over a timeline-only evidence
> bundle — reuse-graph blast-radius depth (`dependents`) is a deferral (needs catalog traversal).
> When B lands, the synthesis step widens to model-driven tool selection without changing the
> `Investigator` inputs or the `Case` shape.

**A — Analysis tools (no upstream dep).** `timeline_build`, `diff_batches` (two batch ledger
entries → row-count/duration/status/schema-delta comparison), `config_versions_diff`
(ComponentStore history → unified structural diff, scoped to stores that actually keep history —
verify PipelineStore parity first), `anomaly_scan` (D6). All `mutating=false`, `READ_METADATA`,
`FunctionTool` pattern, unit tests per tool in `InspectoToolsTest` style.
*Verify: `mvn -o test -pl inspecto-intelligence` green.*

**B — Upstream goalKind seam (eoiagent repo).** D1 change + eoiagent's own tests + `mvn install`
to local `.m2`; then Inspecto side: `AgentAskRequest` gains optional `goalKind`, `AgentRoutes`
passes it through, unknown value → 400.
*Verify: eoiagent suite green; Inspecto reactor green; QA default unchanged (regression test).*

**C — Playbooks. ✅ SHIPPED** (see the as-built note above for the deterministic-synthesis deviation).
`root_cause_analysis` + `impact_analysis` prompt playbooks under
`inspecto-intelligence/src/main/resources/prompts/` (versioned), wired per §5 layering: RCA =
scope → change scan (`config_versions_diff`) → hypothesis set → per-hypothesis evidence
(`diff_batches`/`sql_query`-class tools) → ranked causes + confidence + fix draft; impact =
reuse-graph blast radius. Golden deterministic test: seeded Incident under `StubLlmGateway`.
*Verify: the P1 exit test passes.*

**D — Case Store + route. ✅ SHIPPED.** `CaseStore` (in-memory ring, `Case` record: incidentRef,
trigger Signal, timeline snapshot, hypotheses+evidence, outcome, fix-draft refs, timestamps) in
`inspecto-intelligence/.../investigation/`, projected via two additive `IntelligenceAgent` SPI
defaults (`recentCases`/`caseById`, empty-degrading like `/assist/diagnoses`) to `GET /agent/cases`
(+ `GET /agent/cases/{id}`) — 503 when module absent, 404 on unknown id. RCA runs will write a Case
(slice C wires the write). Real-HTTP `AgentRoutesTest` (4 new cases) + `CaseStoreTest` green.

**E — Event ingress + missing Signals.** D4 emissions; `TriageQueue` (D3) with bounded queue +
dedupe + kill-switch config default-off (`intelligence.triage.enabled=false` — autonomy is opt-in);
on trigger runs `root_cause_analysis` at L1 (Case + draft only, no apply).
*Verify: seeded FAILED batch Signal → Case appears; disabled by default proven in test.*

## 3. Non-goals (P1)

Approvals inbox / act tools (P3) · `component_draft` authoring breadth (P2) · Case persistence +
similarity recall (§7, later) · hosted providers (Standard+) · any `BatchEventBus` migration ·
server-side tree assembly for `/signals` (unrelated) · UI surfaces beyond what exists (cases are
API-first in P1).

## 4. Risks

- **eoiagent SNAPSHOT drift** (§9 parent): the seam change is additive-only; pin the rebuilt jar;
  press the 0.1.0-tag point again at review.
- **Triage loop feedback** (agent investigating its own `agent.*` signals): TriageQueue filters
  `agent.*` types out at subscription — hard rule, tested.
- **Windows file locks on `.m2` install while reactor runs** — serialize slice B's install with
  no concurrent Maven build (shared-sandbox etiquette).
