# Signal Backbone — Combined Plan (envelope · templating · chaining · agent context · AG-UI · A2UI)

- **Status:** PLAN v2 / combined — design-only, no code shipped. **D1(a) full Signal-primary flip
  decided (2026-07-18); D2 "no version bump" decided (2026-07-19); D3 "AG-UI-shaped, domain-named"
  decided (2026-07-19, §8).** S0 starts on explicit operator approval (D4).
- **Date:** 2026-07-19 (v1 audit 2026-07-18; every claim re-verified against HEAD `0960bd3` on
  2026-07-19 — deltas in §3.6).
- **What this combines.** This is the single execution plan for three formerly separate docs:
  1. **event-signal-backbone-plan v1** — the audit + Signal-primary flip (kept as §3–§4 here).
  2. **`a2ui-agent-rendered-ui-spike.md`** — ABSORBED: its P0/P1 → **S4**, P2 → **S6**, P3/P4 →
     **S7**. The spike doc remains the detailed design reference for the render host, kind
     allowlist, and security spine (§4/§6 there).
  3. **`embedded-intelligence-plan.md`** — remains the parent vision (autonomy ladder, skills,
     prompts); its §2 ContextBroker live-overlay and P1 investigation ingress are implemented here
     as **S5**, its L2 gated-action slice aligns with **S6**.
- **Why now:** the platform is pivoting to heavy **agentic control**. Events, Signals, job outputs
  and statuses must be *one* well-structured, AG-UI-shaped substrate, because five consumers read
  it: notification-channel **templating**, agent **diagnostic context**, `$`-parameter **chaining**,
  **AG-UI** streaming, and **A2UI** component rendering. Today the substrate is three incompatible
  envelopes plus a disconnected fourth.
- **Related:** `docs/superpower/living-operational-system.md` (north-star),
  `docs/archived-documents/plans-archive/signal-network-plan.md` (R4, UI shipped),
  `docs/archived-documents/plans-archive/decision-network-plan.md` (R5, Consequence spine),
  `docs/api/openapi-v1.json` (the already-specified `Signal`/`Ref` target), `docs/GLOSSARY.md` §8.

---

## 1. TL;DR

The backend has **three disconnected event models with incompatible envelopes** (`Event`, `Signal`,
`BatchEvent`) plus a fourth island (`AgentEvent`), while the binding docs, the shipped R4 UI, and
`openapi-v1.json` already specify the one they should all be. **Implement the spec'd `Signal` as the
canonical envelope (Signal-primary, per GLOSSARY §8), shape it so it projects 1:1 onto AG-UI
concepts while keeping domain-canonical names, fold all producers into one ledger + one type
catalog + one addressing grammar, then pay that off consumer by consumer:** authorable notification
templates (S2), the AG-UI `/signals/stream` (S3), A2UI inline artifacts + the first agent chat
surface (S4), the agent's situation-frame context (S5), and the gated agentic write path (S6).

Do **not** import an external AG-UI/A2UI SDK — project into both protocols via thin adapters (the
A2UI spike settled this; A2UI v0.9/v1.0-RC churn and the unpublished Angular renderer confirm it).

---

## 2. The five consumers (requirements)

| # | Consumer (operator's pillars) | What it needs from the substrate | Current blocker |
|---|---|---|---|
| **R-CHAIN** | Chain Pipeline/job activities; map facts as `$`-parameters | one bus; correlation **and** causation; typed `subject`; structured payload for `$signal.<path>` binds | two buses; `correlationId` overloaded, no `causationId`; payload JSON-stuffed flat |
| **R-TMPL** | Templating for notification channels / Alert Rules | one addressable context object per fact; one severity ladder; authorable rules + channels wired to dispatch | three grammars + a duplicated `$signal` evaluator; `{{time}}` not exposed; rules hardcoded; persisted channels deliver (3c143ad) but have **no template field** |
| **R-CTX** | Context for the agent — compose situation frames to *diagnose* | ledger the agent can query/window/timeline by `subject`/`correlationId`/severity; agent's own telemetry on the same ledger | agent tool belt has **no** signal/event tool (`InspectoTools.java:36-38`: only `glossary_lookup`, `docs_search`, `status_get`); `AgentEvent` island |
| **R-UIGEN** | Communicate to AG-UI; build UI components with A2UI | a fact stream projectable to AG-UI events + an artifact channel rendering through trusted components | no AG-UI projection; only SSE push is the notification projection; `onArtifact` no-op'd; `INLINE_ARTIFACT` never emitted; no UI consumes `/agent/sessions` |
| **R-ACT** | Agentic control — decisions acting back on the platform | a gated consequence path (propose → confirm → apply through existing gates) | platform consequences inert on the live path; `render-widget`/`generate-report`/`invoke-api` stubs (and `create-alert` emits a signal only) |

---

## 3. Audit (grounded; re-verified 2026-07-19)

### 3.1 Four models, two buses, one ledger, one island

| Model | Envelope | Bus / store | Used for |
|---|---|---|---|
| **`Event`** (`event/Event.java:35`, `@PublicApi(since=4.2.0)`) | `eventId, ts, level(5), type, source(String), pipeline, correlationId, message, attributes:Map<String,String>` | `EventLog` (`event/EventLog.java:142` emit; per-space `:75-94`; SecretScrubber `:146`) → append-only `EventStore` | operational ledger, audit, notifications |
| **`Signal`** (`signal/Signal.java:22`) | `signalId, type(dotted), at, source(String), correlationId, severity(3), payload:Map<String,Object>` | **none** — `toEvent()` (`:35`) JSON-stuffs payload into one attribute; `fromEvent()` (`:56`) re-parses | job triggers, decision consequences |
| **`BatchEvent`** (`etl/BatchEvent.java:41`) | pipeline/batch/status/rows/duration… — **no id, time, severity, correlationId** | `BatchEventBus` (`service/BatchEventBus.java:21`, publish `:38`) — separate sync bus | pipeline→job/enrichment/alert chaining |
| **`AgentEvent`** (`inspecto-agent/.../observe/AgentEvent.java:12`, sealed: `AgentStarted…HumanDecided`) | only `capabilityId + epochMillis` common | `AuditSink`; default is `LoggingAuditSink` (`UccAssistAgent.java:120`) — `RingBufferAuditSink` is **never even instantiated**; zero bridge to `EventLog` | agent run/tool telemetry (≈ the AG-UI lifecycle/tool stream, discarded to logs) |

Delivery is synchronous fan-out on the publishing thread; the **`ingestLock` seam** stands: any
subscriber starting new work must hand off to a virtual-thread pool (`JobService.submitRun`,
`NotificationService.onEvent` → `:86` executor, `FailureReactor` all do).

### 3.2 Envelope gaps (unchanged from v1, all re-confirmed)

1. Lossy Signal↔Event round-trip (JSON-in-a-string attribute).
2. Three severity ladders: `EventLevel` 5 (`event/EventLevel.java:21`) vs `Severity` 3
   (`signal/Severity.java:11`) vs the spec's 6.
3. `correlationId` overloaded; **no `causationId`** (AG-UI needs parent lineage first-class).
4. `source` is a bare String; spec + GLOSSARY §8 + `examples/signal.json` require a typed **`Ref`**.
5. No first-class `space` (routing is thread-MDC only — invisible to a cross-space reader).
6. No `subject` distinct from `source` (`Event.pipeline` is the closest, string, pipeline-only).

### 3.3 Type namespaces — three, plus dead weight, no registry

- **(A)** `EventType` ~45 SCREAMING_SNAKE constants; **still dead:** `FILE_RECEIVED`, `JOB_STARTED`,
  `JOB_SUCCEEDED`, `ENRICHMENT_RUN`, `CONFIG_VALIDATED` (zero non-declaration references).
- **(B)** dotted Signal types (open strings): `job.run.started|completed|failed|rejected`,
  `job.chain.cut`, `job.pack.*`, `job.dataset.produced`, `pipeline.commit`, `maintenance.*`,
  `decision-rule.*`, and **new (2026-07-18): `recon.run.completed`** (`job/ReconRunJob.java:89`,
  Break counts, WARNING when `breaks>0`) — notably, the `recon.run` job type **correctly declares**
  `emits=["recon.run.completed"]` (`job/JobService.java:256-260`) while `report` still declares
  `emits=[]` yet fires `REPORT_READY` (`JobService.java:214` vs `ReportJob.java:134`). The
  discipline exists; nothing enforces it.
- **(C)** the sealed `AgentEvent` family, disconnected.

### 3.4 The three grammars (the templating pain, unchanged)

- **`{{var}}`** (`notify/NotificationTemplate.java:20`, `\{\{\s*([\w.]+)\s*}}` dotted-map walk) over
  `NotificationRule.context(Event)` (`notify/NotificationRule.java:42-55`) — exposes
  `eventId,type,level,source,pipeline,correlationId,message,attributes.*,recipient`; **no
  `ts`/`{{time}}`**. Rules still a hardcoded Java table (`NotificationRules.java:29`).
- **job `$`-grammar** (`job/ParameterResolver.java:56`): `$signal.<field>` (`:102-105`, flat),
  `$upstream(job).artifact(name).{ref,rows,bytes,watermark,time_range}` (`:112-126`),
  `$today/$now/$run.*/$day(n)`.
- **`WhenGuard`** (`job/WhenGuard.java:46`) — a second, independent `$signal.` evaluator; its own
  javadoc (`:14-15`) flags the consolidation. SQL `$`-params (`query/Parameters.java`) are a third
  world. No `$context` token exists.

### 3.5 Transport & the agent surface

- Push to clients: **only** `/notifications/stream` (`control/NotificationRoutes.java:181`,
  registered `:37`) — the notification *projection*, not signals. Everything else polls (15s).
- `GET /signals` (`control/SignalRoutes.java:24`) now filters `type, since, until, severity,
  correlationId, limit` (`:28-36`) — but is backed by the Event ledger filtered to `type=SIGNAL`
  and `Signal.fromEvent()` re-parse (`signal/Signals.java:27-43`). **No `/signals/stream`** —
  spec-named, still unbuilt.
- Agent SSE (`control/AgentRoutes.java:27/33/44`): unnamed `data:` token frames; terminal
  `event: complete` (`:73`) / `event: error` (`:78,98`). `AgentAskResult` has **no artifact field**;
  `INLINE_ARTIFACT` reserved, never emitted (`AgentAskResult.java:18`); `AgentAnswerSink` has no
  `onArtifact`; `InspectoIntelligenceAgent.java:108` drops eoiagent's artifact callback
  (`/* P0 answers never carry one */`).
- **No frontend consumes `/agent/sessions*`.** The only `EventSource` in the UI is
  `layout/common/notifications/notifications.component.ts:204` — the SSE pattern to copy.
  `inspecto-ui/src/app/inspecto/a2ui/` does not exist.
- Decision consequences (`control/DecisionRoutes.java:143-194`): `emit-signal`/`start-job`/
  `trigger-pipeline` execute for real on explicit `/apply`; `render-widget`/`generate-report`/
  `invoke-api` are stubs (`:180-184`); `create-alert` (`:154-158`) now opens a deduped INCIDENT for
  critical/error consequences (`06b2155`, 2026-07-19) but still does no real Alert authoring.
  Record-level consequences (`etl/DecisionRuleApplier.java:177+`) are live-wired into ingest.

### 3.6 Deltas since v1 (2026-07-19 verification)

1. `/signals` gained `until` + `severity` filters (shipped `1f572fe`) — v1's query-surface gap is
   closed for those two.
2. **`/notifications/channels*` CRUD shipped** (`0960bd3`; `NotificationRoutes.java:47-53`,
   `notify/ChannelConfig.java:17`: `id,kind,target,description,enabled,createdAt`), and **delivery
   to persisted channels shipped the next shift** (`3c143ad`, 2026-07-19):
   `NotificationService.dispatch` now also delivers to enabled `ChannelConfig` destinations via
   `NotificationChannel.deliver(n, target)`. **Remaining S2 seam: no per-channel `template` field**
   (message content is still rule-global).
3. **Auto-promotion to Incidents shipped** (`06b2155`, 2026-07-19): critical/error Alert breaches,
   recon `breaks>0`, and critical/error `create-alert` consequences each open a deduped INCIDENT —
   the ALERT→INCIDENT hook v1 flagged missing now exists (hardwired in three producers; S6 may
   later generalize it into a Decision Rule, no longer a gap).
4. New signal type `recon.run.completed` + the first *correct* `emits` declaration (§3.3).
5. `Source→Collector` rename (`f462ece`, 2026-07-14) — agent tools now ground in
   `CollectorService.pipelines()`; vocabulary in this plan follows GLOSSARY.
6. Line-shifts only: `JobService.onSignalEvent` now `:399`, `mirrorPipelineCommit` `:423`,
   `emitSignal` `:436`; notifications SSE `:181`; notifications `EventSource` `:204`.

### 3.7 The central defect — direction inversion (unchanged)

GLOSSARY §8 (binding) and the shipped R4 UI say **Signal is primary; Event is a view**.
`openapi-v1.json` specifies `Signal`/`Ref` and names `/signals/stream`. The Java backend implements
the opposite. **D1(a) — the full flip — is decided.**

---

## 4. Target design — one canonical Signal, AG-UI-shaped, domain-named

### 4.1 The canonical envelope

Implement `openapi-v1.json` `Signal` + the six gap fields. Every field is deliberately **AG-UI-
alignable** so the S3 projection is a rename, not a transformation — but names stay domain-canonical
(D3):

```jsonc
Signal {
  signalId:      string,     // framework-stamped (was eventId/signalId)
  type:          string,     // ONE dotted-canonical catalog (§4.3)
  at:            long,       // epoch millis
  severity:      "trace|debug|info|warn|error|critical",   // ONE ladder (spec's 6)
  source:        Ref,        // {kind,id,rel:'emits',via?} — WHO emitted; joins the metadata graph
  subject:       Ref | null, // WHAT it's about (pipeline/dataset/job/incident); was bare Event.pipeline
  correlationId: string | null,  // run/batch/thread chain
  causationId:   string | null,  // NEW — parent signal id
  space:         string,     // NEW — first-class tenant (was MDC-only)
  actor:         Ref | null, // NEW — who acted (human/agent); audit + agentic provenance
  message:       string,     // human summary
  payload:       object,     // STRUCTURED bag — native Map<String,Object>, no JSON-in-a-string
  schemaVersion: int         // NEW — per-type payload contract version
}
```

**Field ↔ AG-UI mapping (fixed by design, applied at the S3 adapter):**

| Signal | AG-UI concept |
|---|---|
| `correlationId` | `threadId` / `runId` |
| `causationId` | `parentMessageId` / parent event |
| `type` (domain dotted, via catalog) | `EventType` (e.g. `agent.run.started` → `RUN_STARTED`) |
| `message` / streamed tokens | `TEXT_MESSAGE_*` deltas |
| `payload` (+ RFC 6902 patches for state) | event body / `STATE_DELTA` |
| `severity`, `source`, `subject`, `actor`, `space` | carried in the custom/passthrough envelope (AG-UI has no equivalent — domain wins) |

- **`Event`, `Alert`, `Notification`, `Metric` become projections** (GLOSSARY §8). `/events` keeps
  its exact `toMap()` bytes by projecting `Signal→Event` (severity→`level` edge-map,
  `payload`→flattened `attributes` **for the legacy view only**, `subject.id`→`pipeline`). No
  client breaks.
- **Physical store stays `EventStore`** (append-only), gaining a structured `payload` column —
  ending the lossy flatten that `Signals.query()` re-parses today.

### 4.2 One bus · everything emits · reconnect the agent island

- Fold **`BatchEvent`** into the ledger: `BatchAuditWriter.flush` (`etl/BatchAuditWriter.java:98`)
  emits `pipeline.batch.committed|failed` Signals; keep `BatchEventBus` as a **typed compatibility
  view** (subscription reconstructing `BatchEvent`), then migrate subscribers off it.
- **Bridge agent telemetry**: a new `EventLogAuditSink` implementing `AuditSink` publishes
  `agent.run.started|completed|failed`, `agent.model.called`, `agent.tool.called|completed`,
  `agent.human.decided` as canonical Signals with `correlationId` = agent session/run and
  `actor=Ref(agent)`. (Today's default `LoggingAuditSink` discards this to logs; the ring buffer is
  dead code.) This one class turns the island into ledger facts **and** the AG-UI
  `RUN_*`/`TOOL_CALL_*` substrate.
- Retire the five dead `EventType` constants; lifecycle becomes uniformly dotted.

### 4.3 One type catalog (typed, discoverable, versioned)

Promote `JobTypeDescriptor.emits` into a real registry: dotted type →
`{ payloadSchema, defaultSeverity, category, since, schemaVersion }`, spanning **all** producers
(engine, jobs, decisions, agent). Categories adopt **AG-UI's five groups** — lifecycle · text ·
tool · state · custom — so the S3 mapping is data, not code. `recon.run` is the exemplar
declaration; `report`'s `emits=[]` is the first bug it catches; `MaintenanceJob.schedulerAudit`
flags any producer emitting an uncatalogued type. This catalog is the single source read by the
template editor (R-TMPL), `$signal` autocomplete (R-CHAIN), the agent's tool descriptions (R-CTX),
and the AG-UI type map (R-UIGEN).

### 4.4 One addressing grammar

One dotted-path resolver over the canonical Signal, used everywhere a fact is addressed:

- `{{type}} {{severity}} {{time}} {{subject.id}} {{source.id}} {{actor.id}} {{payload.rowsOut}}` —
  notification/Alert templating (fixes the missing `{{time}}`).
- `$signal.payload.<path>`, `$signal.subject.id` — job parameter binds **and** `when:` guards
  (collapse `WhenGuard` + `ParameterResolver.$signal.` into the one evaluator the javadoc already
  asks for).
- The same paths select fields in A2UI action `params` and in agent context selectors — write the
  path walk once, five consumers reuse it.

### 4.5 The projection layer (one substrate, five payoffs)

1. **Templating (R-TMPL).** Notification rules become authorable (TOON section, out of the Java
   table); **persisted `ChannelConfig` channels get a per-channel `template` field** (delivery
   wiring already shipped, §3.6.2). One severity ladder ends the 5-vs-3 mismatch in rule
   conditions.
2. **Chaining (R-CHAIN).** `on_signal:` globs, `when:` guards and `$signal` binds all read the
   structured payload natively; `causationId` gives chains a real parent pointer
   (`chainDepth` stays as the cutoff counter).
3. **Agent context (R-CTX).** New read tools over the ledger — `signals_query` (filter by
   type/subject/correlation/severity/window), `signal_timeline` (causation-ordered thread for one
   `correlationId` — the `timeline_build` primitive from the intelligence plan's P1) — feed the
   ContextBroker's *live overlay*: focus Component → its recent Signals → diagnosis with citations.
   The agent's own `agent.*` telemetry is on the same ledger, so it can also explain *itself*.
4. **AG-UI (R-UIGEN).** `AgUiProjection` maps Signal→AG-UI event via the catalog; ship the
   spec-named `GET /signals/stream` SSE (filter params mirror `GET /signals`), reusing the
   notifications SSE plumbing. State deltas as RFC 6902 JSON Patch over a snapshot (the one
   representation that also feeds A2UI's RFC 6901 pointers).
5. **A2UI (R-UIGEN).** Un-no-op `onArtifact`; carry the in-tree `Component` descriptor on
   `INLINE_ARTIFACT` + an `event: artifact` SSE frame; `<inspecto-a2ui-render>` host + kind
   allowlist per the spike (§4.1–4.5 there, unchanged and still authoritative); decision
   `render-widget`/`generate-report` consequences emit A2UI artifacts **as signals** on the same
   stream.
6. **Agentic write-out (R-ACT).** Signals→`Consequence[]` (rules today, AI proposals next); every
   mutating consequence flows through the existing gated endpoints (`WriteGates` → capability →
   `ConfigSafetyValidator` where applicable) with confirm-then-apply. No new write path, no
   side-door (the spike's security spine, verbatim).

### 4.6 The families the catalog must span (job · status · signal · output)

One envelope, distinguished by `type` + `subject` + `payload` — never four shapes:

- **Job-emitted:** `job.run.started|completed|failed|rejected`, `job.chain.cut`, `job.pack.*`,
  **`recon.run.completed`** — `source=Ref(job)`, `correlationId=runId`, `causationId=` firing signal.
- **Status:** `pipeline.batch.committed|failed` (folding `BatchEvent.status`) + `job.run.*`, with
  `payload.status` — the Alert ledger math (`alert/AlertService.java:251`) reads these one shape.
- **Signal:** the ledger itself — `/signals` + S3's `/signals/stream` over one canonical store.
- **Output/artifacts:** `job.dataset.produced` (`job/SqlTemplateJob.java:128`), Run Artifacts
  (`RunArtifactStore`, already `$upstream`-addressable and now downloadable per `ec2eb83`),
  `REPORT_READY` (`ReportJob.java:134`) — `subject=Ref(dataset|artifact)` + structured payload, so
  a downstream job binds `$signal.payload.rows`, a template renders "dataset X produced (15,184
  rows)", and an A2UI artifact can chart it — all from the same fact.

---

## 5. Phased plan (each slice independently shippable; DoD verifiable via GAUNTLET)

> Dependencies: **S0 → S1 → {S2, S3, S5} · S3 → S4 · {S4, S5} → S6 · S7 last.**
> S0–S2 are backend-internal + backward-compatible; S3–S7 are additive surfaces.

- **S0 — Canonical Signal envelope (keystone).** Implement the spec'd `Signal` + six gap fields
  (§4.1); unify `Severity` to 6 (edge-map `EventLevel`); structured `payload` in the store;
  `Event`/`Alert`/`Notification` become projections.
  **DoD:** reactor green; `/events` & `/signals` external shapes byte-unchanged; a nested payload
  round-trips with zero JSON-in-attribute; `/signals` returns a `Ref` source.

- **S1 — One bus · type catalog · reconnect agent telemetry.** Fold `BatchEvent`→signals (compat
  view); `EventLogAuditSink` bridge (`agent.*` signals with correlation + `actor`); retire dead
  constants; promote `emits`→catalog (§4.3) + `schedulerAudit` enforcement (catches `report`).
  **DoD:** alerts/jobs/enrichment consume one seam; agent run/tool telemetry on the ledger;
  audit flags uncatalogued producers.

- **S2 — One grammar · authorable templating · channel templates.** Collapse `WhenGuard` +
  `ParameterResolver.$signal` into one path evaluator; templates and `$signal` binds address the
  same structured payload; fix `{{time}}`; authorable `notifications` TOON rules; **add a
  per-channel `ChannelConfig.template`** (delivery wiring already shipped, §3.6.2).
  **DoD:** a channel template and a job bind both resolve `payload.rowsOut` from the same fact; one
  `$signal` evaluator remains; a persisted channel delivers its own template.

- **S3 — AG-UI projection + `/signals/stream`.** `AgUiProjection` (catalog-driven, §4.1 map);
  spec-named SSE with `GET /signals`-mirror filters; RFC 6902 state deltas; named frames on the
  agent-session SSE aligned to the same taxonomy.
  **DoD:** a client renders AG-UI lifecycle/tool events off `/signals/stream`; `agent.*` signals
  surface as `RUN_*`/`TOOL_CALL_*`; real-HTTP stream test asserts frame shape; offline-clean.

- **S4 — A2UI channel + render host + first chat surface.** *(absorbs spike P0+P1)* Backend:
  `AgentAskResult.artifact` + `INLINE_ARTIFACT` emission; `AgentAnswerSink.onArtifact` (default
  no-op); `event: artifact` frame; server-side kind allowlist; un-no-op
  `InspectoIntelligenceAgent:108`. Frontend: `<inspecto-a2ui-render>` + `A2UI_COMPONENT_BY_KIND`
  (allowlist: `text`, `kpi`, `chart`, `data-table`); the first streaming chat surface consuming
  `/agent/sessions` (+ `navigate` actions, catalog-validated).
  **DoD:** a seeded question renders a real chart/table inline from the stream; unknown kind →
  placeholder; navigate button routes in-app; UI lint/test/build green; no external assets.

- **S5 — Agent context fabric.** *(implements intelligence-plan §2 live overlay + P1 ingress
  slice)* `signals_query` + `signal_timeline` tools over the canonical ledger; ContextBroker
  situation frame = identity + focus + **live Signal overlay** + retrieval; event ingress
  (generalized `FailureReactor` → triage feeding the deliberative layer).
  **DoD:** "why did pipeline X fail?" yields a causation-ordered timeline with signal citations,
  deterministic under `StubLlmGateway`; the agent can narrate its own last run from `agent.*` facts.

- **S6 — Gated agentic write.** *(absorbs spike P2 + v1's S5 + intelligence L2-lite)* A2UI `invoke`
  actions through the normal gated endpoints with confirm-then-apply; platform consequences
  event-triggerable behind a policy gate (today `/apply`-only); wire `create-alert` to real Alert
  authoring; optionally generalize the shipped hardwired ALERT→INCIDENT promotions (§3.6.3) into a
  Decision Rule.
  **DoD:** agent/decision proposes → dry-run diff → human confirms → gates fire (real-HTTP gate
  test per `endpoint` skill) → applied + audited (`actor=agent:*`); declining mutates nothing.

- **S7 — Unify the reflex layer + resolver (optional payoff).** *(absorbs spike P3+P4)* Replace
  `assist-panel`'s hardcoded switch with `<inspecto-a2ui-render>`; build the
  `authoring.editorKey`/`exec.runnerKey` token-map resolver on the same host, retiring the
  hardcoded editor route table.

*(Optional S8 — connector-direct emission + cross-space controller, unchanged from v1.)*

---

## 6. Traceability (slice → consumer)

| Slice | R-CHAIN | R-TMPL | R-CTX | R-UIGEN | R-ACT | Defects closed |
|---|:--:|:--:|:--:|:--:|:--:|---|
| **S0** envelope | ● | ● | ● | ● | ● | 3 envelopes; inversion; lossy payload; 3 severities; no causation/space/subject/actor |
| **S1** bus+catalog+island | ● | ○ | ● | ● | | two buses; agent island; dead constants; unenforced `emits` |
| **S2** grammar+channels | ○ | ● | | | | 3 grammars; dup `$signal`; `{{time}}`; hardcoded rules; no channel templates |
| **S3** AG-UI stream | | | ○ | ● | | no live push; no AG-UI projection |
| **S4** A2UI+chat | | | ○ | ● | ○ | `onArtifact` no-op; `INLINE_ARTIFACT` unemitted; no agent UI |
| **S5** context fabric | | | ● | | ○ | no signal tools; no situation frame; no event ingress |
| **S6** gated write | ○ | | | ○ | ● | inert consequences; `create-alert` lacks real Alert authoring |

● primary · ○ secondary.

---

## 7. Non-goals

- Importing an external AG-UI/A2UI SDK or renderer (framework-free/offline ethos; substrate exists;
  A2UI v1.0 is still an RC and its Angular renderer unpublished — adopting now = chasing churn).
- A general server-driven-UI framework for the whole app (scope: signal stream + agent answer
  surface; authored UI keeps its path until S7).
- Cross-agent (A2A) negotiation; per-record data-lineage rework; rewriting `EventStore`'s physical
  format beyond the structured `payload` column.
- The full autonomy ladder L3 (bounded autonomy) — stays in `embedded-intelligence-plan.md` P4,
  after S6 proves the gated path.

## 8. Decisions

1. **D1 — Signal-primary flip: DECIDED (a), 2026-07-18.** Full flip; `Event` becomes a projection.
2. **D2 — Versioning: DECIDED (2026-07-19).** **No version bump** — 4.x has not been released, so
   the Signal-primary flip lands in the current 4.x line (no `5.0`, no gated-minor ceremony). The
   `Event`/`Alert`/`Notification` projections are kept anyway — they keep the UI, tests and any
   internal consumers stable — but byte-for-byte legacy compatibility is an engineering convenience,
   not a released-API contract.
3. **D3 — AG-UI alignment: DECIDED (2026-07-19).** *AG-UI-shaped, domain-named*: the envelope is
   field-mappable 1:1 (§4.1 table) and the catalog carries AG-UI categories, but internal type
   names stay domain-canonical dotted; `AgUiProjection` maps names at the edge. Rationale: GLOSSARY
   vocabulary discipline + protocol churn isolation, while satisfying "define our facts like AG-UI".
4. **D4 — Start: S0 begins on explicit operator approval** of this combined plan.
5. **D5 — Stream unification (new, decide by S3):** does the agent-session SSE eventually ride
   `/signals/stream` (one pipe, filtered by `correlationId=session`), or stay a separate endpoint
   with aligned framing? *Lean: separate at S3/S4, revisit after S5 when agent telemetry and chat
   share correlation ids.*

## 9. Touchpoints (indicative, S0–S2; refreshed to HEAD)

**Backend:** `signal/Signal.java` + `signal/Severity.java` (→ canonical, 6-level),
`event/Event.java` (→ projection), `event/EventLog.java` (stamp `space`/`causationId`),
`event/EventStore.java` + `InMemoryEventStore`/`ParquetEventStore` (structured payload),
`etl/BatchEvent.java` + `service/BatchEventBus.java` (→ compat view), `etl/BatchAuditWriter.java:98`
(emit signals), `job/JobTypeDescriptor.java` (→ catalog), `job/JobService.java:399/423/436`
(chaining reads canonical), `job/ParameterResolver.java` + `job/WhenGuard.java` (→ one evaluator),
`notify/NotificationRule.java:42` + `NotificationTemplate.java` + `NotificationRules.java` +
`NotificationService.java:116` + `notify/ChannelConfig.java` (+`template`, wire dispatch),
`control/SignalRoutes.java` (+ `/signals/stream` in S3), `control/AgentRoutes.java` (S3/S4 frames),
`intelligence/AgentAskResult.java` + `AgentAnswerSink.java` +
`inspecto-intelligence/.../InspectoIntelligenceAgent.java:108` (S4),
`inspecto-intelligence/.../InspectoTools.java` (S5 tools),
`inspecto-agent/.../observe/` (+`EventLogAuditSink`, S1).
**Frontend (S4/S7):** `inspecto-ui/src/app/inspecto/a2ui/*` *(new)*, chat surface *(new; SSE
pattern from `notifications.component.ts:204`)*, reuses `component-model/*`, `viz-render`,
`data-table`, `chart`, `kpi`, `schema-form`, `empty-state`.
**Docs:** this file; `openapi-v1.json` if it drifts; distil to
`docs/okf/backend/control-plane/events-metrics.md` on ship; `docs/INDEX.md`; spike doc stays as
the S4/S6/S7 design reference.

## 10. Vocabulary & offline conformance (binding)

Canonical terms only (`docs/GLOSSARY.md`): **Signal · Consequence · Decision Engine · Alert Rule ·
Decision Rule · Expectation · Incident · Pipeline · Collector · Measure · Widget** (never
Flow/Issue/bare Rule/BI Metric/Source-as-acquisition-entity). Signal type names, catalog categories
and A2UI artifact kinds are canonical-term-checked. Signals, projections and artifacts **must not**
reference external assets (offline-first). AG-UI/A2UI protocol names live only in the adapters.
