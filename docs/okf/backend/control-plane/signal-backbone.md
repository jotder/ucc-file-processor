---
type: Concept
title: Signal Backbone (canonical envelope, agent context fabric, gated agentic write)
description: The one canonical Signal envelope + type catalog + addressing grammar, projected to notification templating, agent diagnostic context, AG-UI streaming, A2UI artifacts, and the gated agentic write path.
resource: inspecto/src/main/java/com/gamma/signal/Signal.java
tags: [control-plane, signal, agent, a2ui, ag-ui, agentic-write, deadlock]
timestamp: 2026-07-19T00:00:00Z
---

# Signal Backbone

Shipped end-to-end (S0–S7, `docs/archived-documents/plans-archive/event-signal-backbone-plan.md`):
one canonical `Signal` envelope replaces three previously-disconnected event models (`Event`,
the old 3-field `Signal`, `BatchEvent`) plus the isolated `AgentEvent` island, then that one ledger
is projected out to five consumers — notification templating, `$`-parameter chaining, agent
diagnostic context, AG-UI streaming, A2UI inline artifacts — and finally to a gated write-back path.

## The canonical envelope (S0)

* **`Signal`** (`inspecto/src/main/java/com/gamma/signal/Signal.java`) — 13-field record: `signalId,
  type (dotted string), at:Instant, severity, source:Ref, subject:Ref, correlationId, causationId,
  space, actor:Ref, message, payload:Map<String,Object>, schemaVersion`. `toEvent()`/`fromEvent()` are
  a true, lossless round-trip onto the `Event` ledger (`EventType.SIGNAL`) — payload rides as a real
  structured map, never JSON-stuffed into a String attribute.
* **`Ref`** (`{kind, id, rel, via}`) — typed reference joining the metadata graph; replaces bare-String
  `source`. `Ref.of(kind, id)` factory; `toMap()` for the wire shape.
* **`Severity`** — 6 levels (`TRACE, DEBUG, INFO, WARN, ERROR, CRITICAL`), wire-serialized lowercase
  per `openapi-v1.json`; maps onto the legacy 5-level `EventLevel` (CRITICAL→ERROR, the one lossy
  direction, by design — documented precedent, not a bug).
* No `@PublicApi` version bump was required (decided explicitly: 4.x unreleased) — `Signal`'s and
  `Event`'s shapes were free to change within this major.
* **`Signals`** (`inspecto/src/main/java/com/gamma/signal/Signals.java`) — the static, stateless read
  side: `query(EventStore, type, sinceMs, untilMs, minSeverity, correlationId, limit)` (type is exact
  or a `prefix.*` glob; severity floor and correlationId filter in-store) and `matches(...)` (the
  shared predicate reused by both the in-store query page and a live push subscriber).

## One bus, one type catalog, one addressing grammar (S1–S2)

* **`EventLogAuditSink`** (`inspecto-agent/.../kernel/observe/EventLogAuditSink.java`) bridges the
  previously-discarded `AgentEvent` island onto the canonical ledger as `agent.run.started|completed|
  failed`, `agent.model.called`, `agent.tool.called|completed`, `agent.human.decided` — all carrying
  `correlationId = capabilityId`, `source = Ref("agent-capability", capabilityId)`.
* **`JobTypeCatalog`** flags a Job Type whose declared `emits` disagrees with what it actually fires
  (caught a real bug: `report` declared `emits=[]` while firing `REPORT_READY`).
* **`BatchAuditWriter`** additively emits `pipeline.batch.committed|failed` Signals alongside the
  pre-existing `BatchEvent` fan-out (both fire; `BatchEventBus` itself is untouched — see the
  `ingestLock` note below for why a full bus migration was deliberately not attempted).
* **`DottedPath`** (`inspecto-util/src/main/java/com/gamma/util/DottedPath.java`) — one shared `a.b.c`
  resolver now used by `{{template}}` interpolation (`NotificationTemplate`), `$signal.<path>` job
  binds (`ParameterResolver`), and `when:` guards (`WhenGuard`) — replacing three independently
  duplicated flat-lookup implementations. `NotificationRule.context(Event)` gained `ts`/`time`/
  `payload`; `ChannelConfig` gained a per-channel `template` field.

## AG-UI projection + A2UI render channel (S3–S4)

* **`GET /signals/stream`** (`control/SignalRoutes.java`) — SSE, mirrors `NotificationRoutes.stream()`
  exactly (`BlockingQueue` + `EventLog.addSubscriber` registered before headers commit, heartbeat
  poll, `finally`-deregister). Filters: `type`, `severity`, `correlationId` via the shared
  `Signals.matches`.
* **`AgUiProjection`** (`inspecto/src/main/java/com/gamma/signal/AgUiProjection.java`) — pure mapping
  from a domain Signal type to an AG-UI event type (`agent.run.started`→`RUN_STARTED`, etc.,
  `CUSTOM` for anything uncatalogued). Domain type names stay dotted/canonical internally; AG-UI is
  a thin edge adapter, never adopted wholesale (decision D3: "AG-UI-shaped, domain-named").
* **A2UI artifact channel**: `AgentAskResult.artifact` (nullable `Map`), `AgentAnswerSink.onArtifact`.
  `InspectoIntelligenceAgent.parseArtifact` validates mime type `application/vnd.a2ui+json`, parses
  JSON, and enforces a **closed kind allowlist**: `text | kpi | chart | data-table` — fails closed
  (drops with a warn) on any mismatch, never breaks the answer. `/agent/sessions/{id}/ask/stream`
  carries an `event: artifact` SSE frame before `event: complete`.
  **As of S6, no live eoiagent tool actually produces an `INLINE_ARTIFACT` answer** — the channel is
  proven by direct-construction tests; a real producer is future work (would ride the S5 tool belt).
* **`<inspecto-a2ui-render>`** (`inspecto-ui/src/app/inspecto/a2ui/`) — the frontend render host,
  mirroring `viz-render.component.ts`'s dispatch: `@switch` on `kind` → `inspecto-kpi` / `inspecto-
  chart` / `inspecto-data-table` / plain pre-wrap text; unknown kind → empty-state, **never
  innerHTML**. `parts` recurse (depth-capped at 3). `actions` are declarative intents — `navigate`
  (validated against the live Angular `Router` config via `route-validation.ts`'s fail-closed walker;
  external/protocol-relative targets and custom matchers are rejected) and, since S6, `invoke` (see
  below). `AgentService` (`inspecto/api/agent.service.ts`) is the app's first SSE-over-POST client
  (`fetch` + `ReadableStream`, since `EventSource` can't POST) — its `SseFrameParser` is exported pure
  for direct testing.

## Agent context fabric (S5)

* Two new tools on the agent's read-only belt (`inspecto-intelligence/.../pack/InspectoTools.java`,
  same `FunctionTool` pattern as `status_get`): **`signals_query`** (filtered ledger slice — type
  glob, time window, severity floor, correlationId) and **`signal_timeline`** (causation-ordered
  reconstruction for one correlationId: roots — no/absent `causationId` — oldest-first, each followed
  depth-first by its causal children; orphans and cycle remnants are appended timestamp-ordered,
  never dropped). Every entry carries a citable `signalId`. This is what makes "why did pipeline X
  fail?" and "narrate your own last run" (from the `agent.*` facts above) answerable.
* **`ContextBroker`** (`inspecto-intelligence/.../context/ContextBroker.java`) — the first real
  implementation of `embedded-intelligence-plan.md` §2's situation frame: deterministic, budget-bound
  (`FRAME_BUDGET_CHARS`, overlay evicted oldest-first) composition of identity (role) + focus (page
  context) + a live Signal overlay (recent WARN+, newest first) + a knowledge pointer. Wired into
  session open via eoiagent's `SessionRequest.attributes` map (the least-invasive seam found; the
  attributes map is a supply-side seam — actually surfacing it into the model's prompt is the eoiagent
  host layer's concern, not this repo's).
* **`SignalIngress`** (`inspecto-intelligence/.../context/SignalIngress.java`) — an `ingestLock`-safe
  `EventLog` subscriber (bounded queue, sheds rather than blocks, own daemon virtual-thread executor —
  mirrors `FailureReactor`'s pattern) retaining a window of *elevated* signals (severity ≥ ERROR, or
  the canonical failure types). **Shipped tested but deliberately unwired**: its ERROR+/failure floor
  differs from `ContextBroker`'s WARN+ overlay floor, and forcing them together added risk for no DoD
  gain. It's the substrate a future triage consumer attaches to — do not assume it's live without
  checking `InspectoIntelligenceAgent` for an `attach()` call.

## Gated agentic write (S6)

* **`actor=agent:*` audit convention** — additive `X-Agent-Session` request header.
  `ApiContext.actor(ex)`/`actorType(ex)`: an authenticated `Subject` always wins (`"user"`); the
  header applies only when there is none; its absence is byte-identical to pre-S6 behavior (actor
  from `X-Actor`, `actorType "user"`). Same trust model as the pre-existing client-supplied `X-Actor`
  header — **attribution only, never a capability grant**.
* **Invoke round-trip** (conservative cut — the plan explicitly allows it): an A2UI `invoke` action's
  `target` must name an **existing, human-authored Decision Rule** — the agent proposes to *run* one,
  never authors one on the fly. Client: "Dry-run" → `POST /decision-rules/{target}/simulate` (no
  mutation, shows matched/total) → a **separate, explicit** "Confirm & apply" click → `POST
  .../apply` — the exact same gated endpoint (`DecisionRoutes`) the human Decision Rules UI uses, no
  new gate machinery, no gate weakened. Declining/never confirming mutates nothing.
* **`create-alert` → real Alert authoring** — `DecisionRoutes`'s `create-alert` consequence now calls
  `AlertRoutes.authorFromConsequence` (the exact same parse/validate/`ComponentStore`/
  `AlertService.upsert` path — including the write-root gate — as the human `POST /alerts/rules`)
  **when** the consequence's params carry enough for a valid `AlertRule` (comparator+threshold plus
  either metric+window or dataset+measure). The pre-S6 stub shape (`{rule, severity}` only)
  deliberately still falls back to ledger-signal + ad-hoc Incident only — there's no sane default for
  a threshold or a window, and inventing one would author garbage rules.

## Reflex layer unification + editor resolver (S7)

* **Assist panel**, scoped honestly (not the wholesale replacement the spike imagined, which would be
  lossy — the A2UI kind allowlist has no code/chip/header kind): only the branches with a faithful
  artifact-kind equivalent (`humanReadable`/`narrative` → `text`, `sampleRows`/`findings` →
  `data-table`) now compose into one artifact rendered through `<inspecto-a2ui-render>`. A
  server-shaped `result.artifact` (new optional field on `AssistResult`) passes through verbatim when
  present — the seam for a future assist skill to emit a real artifact. SQL/`.toon` copy blocks,
  citations/links chips, and the status header stayed panel-owned chrome (no artifact-kind
  equivalent exists for them yet).
* **Editor-route resolver** (`inspecto-ui/src/app/inspecto/component-model/editor-registry.ts`) —
  `ComponentKind.authoring.editorKey`/`exec.runnerKey` were declared on 7 kinds since the
  component-model was built but had **zero consumers** until S7. `registerEditorRoute(editorKey,
  factory)` (throw-on-duplicate, mirrors `registerKind`) + `resolveEditorLink(kind|id, id)` (fail
  closed — `null` for an unknown kind or unregistered key) retires **both** `registry.component.ts`'s
  hardcoded `EDITOR_PATH` record and its parallel 4-kind `if`-chain (`pipeline`/`job`/`decision-rule`/
  `requirement` — these are dialog-based/id-less panes, kept via id-ignoring route factories, no UX
  change). `runnerKey` is still unconsumed — no exec resolver was built speculatively.

## Gotchas

* **The `ingestLock` deadlock seam is load-bearing for every piece above that subscribes to
  `EventLog`.** `CollectorService`/`SourceProcessor` hold `ingestLock` across a poll cycle; any
  subscriber (`SignalIngress`, `BatchAuditWriter`, a future triage consumer) that starts new
  synchronous work inline risks deadlock — hand off to a bounded queue + its own virtual-thread
  executor, exactly `FailureReactor`'s and `SignalIngress`'s pattern. Never "simplify" a subscriber to
  run inline.
* **`EventLog.global()` is JVM-wide.** A default-space `CollectorService` in a test rides the global
  ledger other tests also emit onto — scope test assertions by a unique `correlationId` (or seed
  future-dated signals for an overlay/newest-first assertion) rather than assuming an empty ledger.
* **Two unrelated `Tool` systems coexist**: eoiagent's `com.eoiagent.tool.Tool`/`ToolSpec` (what
  `InspectoTools`/the S5 signal tools use) vs. the older `com.gamma.agent.kernel.tool.ToolRegistry`
  (the `UccAssistAgent`/skills path). Don't conflate them when adding a new tool.
* **`AgentAskResult.artifact` has no live producer.** The whole S4 A2UI channel is proven by
  direct-construction tests (`InspectoIntelligenceAgentTest`, `AgUiProjectionTest`) — don't assume a
  real chat session can currently trigger it end-to-end without a new tool/skill producing one.

## Open / deferred (moved to `docs/BACKLOG.md`)

Optional S8 (connector-direct emission + cross-space controller); generalizing the hardwired
ALERT→INCIDENT promotion (`AlertService.promoteToIncident`) into a Decision Rule; a general
event-triggered consequence policy gate (today strictly `/apply`-only, human-initiated); an
authorable `notifications` TOON config section; RFC 6902 JSON Patch state deltas for AG-UI (no
diffing dependency exists yet and nothing emits a delta to consume one).
