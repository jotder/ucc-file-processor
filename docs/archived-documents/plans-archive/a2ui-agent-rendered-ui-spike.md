# A2UI — Agent-Rendered UI (Design Spike)

- **Status:** ABSORBED (2026-07-19) into `event-signal-backbone-plan.md` (combined plan) — execution
  is tracked there: P0+P1 → its **S4**, P2 → **S6**, P3+P4 → **S7**. This doc remains the detailed
  design reference for the render host, wire format, kind allowlist, and security model (§4–§6).
- **Date:** 2026-07-09 (absorbed 2026-07-19)
- **Scope:** Let the embedded intelligence agent emit **declarative UI descriptors** that the
  Angular UI renders inline (charts, tables, KPIs, forms, action buttons) instead of returning
  only prose + a navigation hint, with user interactions flowing back to the agent as new turns.
- **Related:** `docs/superpower/embedded-intelligence-plan.md` (AGT-5),
  `docs/superpower/component-model.md`, `docs/superpower/api-contract-design.md`,
  `docs/GLOSSARY.md` (canonical vocabulary — binding).

---

## 1. TL;DR

A2UI ("agent-to-UI") is the pattern where an agent returns a **declarative, framework-agnostic
component tree** — not text, not raw HTML — which a thin client renders in its own design system,
and where user actions on that tree flow back to the agent. For a data-inspection / BI product,
the answer to "what's wrong with this dataset?" *wants* to be a filtered table or a chart, not a
paragraph. Inspecto is unusually well-positioned to adopt it because the three pieces normally
built from scratch **already exist**:

1. A descriptor metamodel — `Component<C> = { kind, id, name, config, parts?, wiring? }`
   (`inspecto-ui/src/app/inspecto/component-model/component-types.ts:11`), framework-free TS, JSON
   over HTTP.
2. A proven runtime `kind → Angular component` dispatcher with `ngComponentOutlet`
   (`inspecto-ui/src/app/inspecto/viz/viz-render.component.ts`).
3. A reserved answer kind — `AgentAskResult.kind` already documents an `INLINE_ARTIFACT` value
   that P0 deliberately never emits (`inspecto/src/main/java/com/gamma/intelligence/AgentAskResult.java:18`).

The work is to **connect** them: carry a descriptor on the agent answer under the reserved
`INLINE_ARTIFACT` kind, build the generic render host (designed-for but not yet built), add the
first UI consumer of the streaming endpoint, and — critically — route any *action* the descriptor
proposes back through the existing fail-closed gate chain rather than a privileged side-door.

**Recommendation:** worth a P0 spike. Cost is low relative to payoff because the rendering
substrate exists; the risk surface is concentrated in the action round-trip, which the spike can
defer (render-only first).

---

## 2. What A2UI is (and is not)

**Is:** an agent emits a structured description of UI — a tree of typed components with config and
bindings, plus declarative *action intents*. A trusted client maps each node to a real component in
its own framework and renders it. Interactions post structured events back to the agent. The agent
never ships executable code or markup.

**Is not:** the agent returning HTML/JS to `innerHTML` (injection surface), nor the agent driving
the DOM directly (couples agent to framework), nor bespoke hand-written components per answer type
(what the reflex `assist-panel` does today — see §3).

It is the UI-layer complement to agent-to-agent transport: eoiagent already carries model traffic,
A2UI carries the agent↔user-surface contract.

---

## 3. Current state (grounded)

There are **two agent layers**, and only the non-streaming one has a UI today.

### 3.1 Reflex layer — has UI, structured, hardcoded rendering
- `POST /assist/{intent}` → `AssistRoutes`; client `inspecto-ui/src/app/inspecto/api/assist.service.ts`.
- UI: `inspecto-ui/src/app/inspecto/components/assist-panel.component.ts` (reusable widget) +
  `modules/admin/assist/assist.component.ts` (console).
- Returns a structured `AssistResult` (`inspecto-ui/src/app/inspecto/api/models.ts:303`:
  `answer`, `status`, `confidence`, `validated`, `applyVia`, `rationale`, `citations`, `links`,
  loose `data` bag). **Rendered by a fixed presentational switch** — `answer` as `<p>`, `sql`/
  `draftToon` in `<pre>`, `sampleRows`/`findings` as hand-rolled `<table>`s, `links` as raw
  `<a href>`, raw-JSON `<details>` fallback. No markdown, no descriptor-driven rendering.

This is the anti-pattern A2UI replaces: every new answer shape needs new template code.

### 3.2 Deliberative layer — streaming, backend-only, no UI consumer
- SPI `com.gamma.intelligence.spi.IntelligenceAgent`
  (`inspecto/src/main/java/com/gamma/intelligence/spi/IntelligenceAgent.java:30`):
  `openSession`, `ask`, `askStream(sessionId, request, AgentAnswerSink)`, `close`.
- Provider `inspecto-intelligence/src/main/java/com/gamma/intelligence/InspectoIntelligenceAgent.java:37`
  (optional module, wraps eoiagent; `ask` → `AgentAskResult` via `toResult`).
- Answer record `AgentAskResult(String kind, String text, List<Citation> citations,
  String navigationTarget)` — `kind ∈ TEXT | NAVIGATION | CLARIFICATION | ERROR`, with
  **`INLINE_ARTIFACT` reserved but never emitted at P0**
  (`inspecto/src/main/java/com/gamma/intelligence/AgentAskResult.java:18`).
- Streaming port `AgentAnswerSink`: `onToken(String)`, `onComplete(AgentAskResult)`,
  `onError(String)` (`.../intelligence/AgentAnswerSink.java:9`).
- Routes `inspecto/src/main/java/com/gamma/control/AgentRoutes.java`: `POST /agent/sessions` (:27),
  `/agent/sessions/{id}/ask` (:33), `/agent/sessions/{id}/ask/stream` SSE (:44). SSE framing today:
  unnamed `data:` frame per token, then `event: complete` carrying the JSON `AgentAskResult`, or
  `event: error`. `agentOr503` (:103) returns 503 when the optional module is absent.
- **No frontend consumes `/agent/sessions...`.** The only `EventSource` in the UI is
  `layout/common/notifications/notifications.component.ts:197` (for `/notifications/stream`) — the
  SSE pattern to copy.

### 3.3 The descriptor substrate (frontend)
- `component-model/component-types.ts:11` — `Component<C>{kind,id,name,space?,config,parts?,wiring?}`
  + `Part`, `ComponentRef{kind,id?,inline?}`, `Wiring{strategy}`. JSON, framework-free.
- `component-model/component-kind.ts` — `ComponentKind` registry entry with `config.validate`,
  `attributes?: AttributeSpec[]`, `deriveWiring`, and the **indirection seams**
  `authoring?: {editorKey}` / `exec?: {runnerKey}`, documented as "resolved Angular-side via a token
  map (NgComponentOutlet)" — **the resolver host does not exist yet**; editors are still reached by
  a hardcoded route table (`modules/admin/catalog/registry.component.ts:28`).
- `component-model/component-registry.ts` — `Map<string,ComponentKind>`; kinds self-register via
  `*.kind.ts` side-effect files.
- Canonical spec: `docs/superpower/component-model.md:22`.

### 3.4 The proven renderer + form precedent
- `inspecto/viz/viz-render.component.ts` — `<inspecto-viz-render>` `@switch`es on
  `plugin().render.kind`: `chartjs`→`<inspecto-chart>`, `aggrid`→`<inspecto-data-table>`,
  `component`→`*ngComponentOutlet` (map `COMPONENT_BY_KEY` at :14; async loaders in
  `viz/viz-components.ts`). **This is the reference implementation for the A2UI render host.**
- `inspecto/components/schema-form.component.ts` — `<inspecto-schema-form>`: `AttributeSpec[]` →
  reactive form, three-tier disclosure, `dependsOn`. The descriptor→form renderer.
- Jobs authoring (P3c) — `modules/admin/jobs/job-form.dialog.ts` fetches a type descriptor via
  `JobsService.describeType(typeId)`, converts `ParamDecl[]`→`AttributeSpec[]` with
  `job-parameter-specs.ts`, renders through the shared form. **Precedent: server descriptor →
  spec → generic UI.**

### 3.5 Note on the backend "Component" name collision
The Java `ComponentRegistry.Component(type, name, path, content)`
(`inspecto/src/main/java/com/gamma/pipeline/ComponentRegistry.java:74`) and
`ComponentStore.WRITABLE_TYPES` (`ComponentStore.java:46`: grammar/schema/transform/sink/dataset/
widget/dashboard/query/expectation/requirement/link-analysis-view/geo-map-view) are a **registry
storage concept**, unrelated to the frontend `Component` metamodel. The A2UI descriptor reuses the
**frontend** metamodel shape, not this record.

### 3.6 The gate chains (for §6)
- `inspecto/src/main/java/com/gamma/control/WriteGates.java:6` — order: **write-root 503 →
  unsafe-name 422 → path-jail 403 → conflict 409**.
- `inspecto/src/main/java/com/gamma/config/safety/ConfigSafetyValidator.java:42` — only on
  config/pipeline draft writes (`ConfigRoutes.java:84,108`, `RunRoutes.java:165`).
- `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:33` — capability
  `canAuthorWorkbench` → write-root 503 → `ComponentStore` own type/id/path-jail. No
  `ConfigSafetyValidator` in this path.

---

## 4. Proposed design

### 4.1 Wire format — the descriptor is a render-only `Component`
The agent emits an **A2UI artifact**: a restricted, render-only projection of the frontend
`Component` shape:

```jsonc
{
  "kind": "widget",              // MUST be in the render allowlist (§4.5)
  "id": "a2ui-<uuid>",           // ephemeral; not persisted to ComponentStore
  "config": { /* kind-specific, validated by ComponentKind.config.validate */ },
  "parts": [ /* nested render-only artifacts */ ],
  "actions": [ /* declarative intents, §4.4 — new field, render-only */ ]
}
```

- Reuses `component-types.ts` so the render host is the same code path as authored components.
- **Not** persisted through `ComponentStore` — an A2UI artifact is a transient answer payload, not
  a saved workbench object. (A "save this" affordance becomes an *action*, §4.4.)
- `wiring` is out of scope for render-only artifacts; artifacts are self-contained trees.

### 4.2 Backend — carry the artifact on `INLINE_ARTIFACT`
- Add an `artifact` field to `AgentAskResult` (JSON string or a minimal typed record), populated
  only when `kind = INLINE_ARTIFACT` — the reserved value that already exists. Existing `TEXT` /
  `NAVIGATION` answers are untouched (backward compatible; the field is null).
- The backend treats the artifact as **near-opaque**: it enforces the kind allowlist (§4.5)
  server-side (reject/degrade a non-allowlisted kind) but does not deeply interpret config.
  Rendering is inert, so the descriptor itself carries no execution risk — the risk is in actions
  (§6), which are gated regardless of what the artifact says.
- **Streaming:** add a named SSE frame `event: artifact` carrying the artifact JSON, emitted before
  `event: complete`. Extend `AgentAnswerSink` with `onArtifact(...)` (default no-op so existing
  providers keep compiling). Token `data:` frames and `event: complete` semantics are unchanged.

### 4.3 Frontend — generic render host + first SSE consumer
Two new pieces (the gaps §3.3/§3.2 flagged):

1. **`<inspecto-a2ui-render [artifact]>`** — a generic host modeled directly on
   `viz-render.component.ts`: `@switch` / token-map from `artifact.kind` → the trusted component,
   via `ngComponentOutlet` and a `A2UI_COMPONENT_BY_KIND` map (mirror of `COMPONENT_BY_KEY`).
   Unknown kind → the existing `empty-state` / placeholder, **never** raw HTML. This host is also
   the natural home for the long-designed `authoring.editorKey`/`exec.runnerKey` resolver — A2UI is
   the forcing function to finally build the token-map indirection.
2. **A streaming chat surface** that opens a session against `/agent/sessions`, consumes
   `/ask/stream` (reuse the `EventSource` pattern at `notifications.component.ts:197`), renders
   `data:` tokens as streaming markdown, and mounts `<inspecto-a2ui-render>` on the `artifact`
   frame. This is greenfield UI.

The render host targets the **existing design-system widgets**: `<inspecto-data-table [tier]>`
(`inspecto/data-table/data-table.component.ts`), `<inspecto-chart>`
(`inspecto/components/chart.component.ts`), KPI (`inspecto/viz/plugins/kpi.component.ts`),
`<inspecto-schema-form>`, `status-badge`/`empty-state`/`alert`.

### 4.4 Actions — declarative intents, executed through existing endpoints
An artifact may declare `actions`, but an action is **data, not code**:

```jsonc
{ "label": "Open dataset", "intent": "navigate", "target": "<catalog route>" }
{ "label": "Materialize", "intent": "invoke", "op": "<allowlisted op id>", "params": { } }
```

- **`navigate`** → in-app router navigation, validated against the **same navigation catalog the
  agent already uses for `NAVIGATION` answers** (auto-derived from `app.routes.ts`). No external
  URLs.
- **`invoke`** → maps to a **whitelisted control-plane operation**. The UI issues that call
  through the normal API client, so it hits `WriteGates` + capability + `ConfigSafetyValidator`
  exactly as a human-initiated action would. Writes get the standard confirm-then-apply UX. The
  agent proposes; the user confirms; the platform executes and gates.
- No inline JS, no arbitrary-URL fetch, no `innerHTML` of agent output beyond sanitized markdown.

### 4.5 Render allowlist (fail-closed)
Like `ComponentStore.WRITABLE_TYPES`, the set of agent-emittable kinds is a **closed enum**, not
open. P0 allowlist proposal: `text` (markdown), `kpi`, `chart`, `data-table`, `status-badge`,
`empty-state`, `form` (read-only echo of an `AttributeSpec[]`). Enforced **both** server-side
(§4.2) and client-side (§4.3, unknown→placeholder). Widening the allowlist is an explicit,
reviewed change.

---

## 5. Why not the alternatives

| Option | Verdict |
|---|---|
| Agent returns HTML/markdown-with-embeds to `innerHTML` | ✗ Injection surface; couples agent to CSS; no interaction round-trip. |
| Bespoke per-answer components (today's `assist-panel` switch) | ✗ Every new answer shape = new template code; doesn't scale; already the pain point. |
| Adopt an external A2UI/AG-UI SDK wholesale | ✗ Violates framework-free / offline-first ethos; the substrate already exists in-tree — a foreign renderer would duplicate `viz-render`. |
| **Reuse the in-tree `Component` metamodel as the A2UI contract** | ✓ Minimal new surface; one render path for authored + agent-emitted UI; offline-clean. |

A2UI-the-external-spec informs the *shape* (declarative tree + action intents + streaming); we
implement it against our own metamodel rather than importing a rendering runtime.

---

## 6. Security model (the spine)

The whole feature is safe iff **agent-emitted UI gets no privileged execution path**:

1. **Rendering is inert.** Descriptors map to a closed set of trusted components (§4.5); an unknown
   or malformed kind degrades to a placeholder. No code/markup from the agent is executed.
2. **Actions are proposals.** Every mutating action flows through the *existing* gated endpoints
   (`ConfigRoutes`/`ComponentRoutes`/`RunRoutes`) and therefore through `WriteGates` (write-root
   503 → name 422 → jail 403 → conflict 409), the relevant capability check
   (`canAuthorWorkbench` etc.), and `ConfigSafetyValidator` where that endpoint already applies it
   (§3.6). A2UI adds **no new write path** and no bypass.
3. **Confirm-then-apply** for writes, reusing the platform's existing optimistic-UI + confirm
   pattern. The user, not the agent, authorizes the mutation.
4. **Navigation is catalog-bound**; `invoke` is op-allowlist-bound; neither accepts free-form URLs.

Net: a hallucinated or adversarial descriptor can at worst render a wrong-looking (inert) widget or
propose an action the user declines — it cannot mutate state unmediated.

---

## 7. Vocabulary & offline conformance (binding)

- Artifact kinds MUST map to **canonical terms** (`docs/GLOSSARY.md`): a visualization instance is a
  **Widget** (Type vs Instance); use **Dataset**, **Pipeline**, **Measure**, **Expectation / Alert
  Rule / Decision Rule** — never the banned synonyms (Flow / Data Store / Metric / bare Rule /
  Collector / Issue). The allowlist enum names are canonical.
- **Offline-first:** descriptors and rendered widgets MUST NOT reference external assets (CDN fonts,
  remote tiles/images, remote scripts). Same constraint the app already honors (e.g. offline
  MapLibre basemap). A2UI is transport + rendering only, so this is a review rule, not a blocker.

---

## 8. Phased plan

Each phase has a Definition-of-Done that can be verified with the `build-verify` (GAUNTLET) loop.

- **P0 — Render-only spike (prove the pipe).**
  - Backend: `AgentAskResult.artifact` field + `INLINE_ARTIFACT` emission; `event: artifact` SSE
    frame; `AgentAnswerSink.onArtifact` default no-op. Server-side kind allowlist.
  - Frontend: `<inspecto-a2ui-render>` host (allowlist: `text`, `kpi`, `chart`, `data-table`); the
    first streaming chat surface consuming `/agent/sessions` + `/ask/stream`.
  - Agent: make the intelligence provider emit one real artifact for one query class (e.g. "show me
    row-count over time for dataset X" → `chart`).
  - **DoD:** ask a seeded question, see a real chart/table render inline from the stream; reactor +
    UI lint/test/build green; no external asset loaded (offline check).

- **P1 — Actions (navigate).**
  - `actions[].intent = navigate` end-to-end, catalog-validated. **DoD:** an artifact's button
    routes in-app to the correct catalog page; invalid targets are refused.

- **P2 — Actions (invoke) with confirm-then-apply.**
  - One whitelisted mutating op (e.g. "save this widget") through the normal gated endpoint + confirm
    UX. **DoD:** the write goes through `WriteGates`/capability/`ConfigSafetyValidator`; a real HTTP
    gate test (per the `endpoint` skill) proves each gate still fires; declining the confirm mutates
    nothing.

- **P3 — Unify the reflex layer.**
  - Replace `assist-panel`'s hardcoded switch (§3.1) with `<inspecto-a2ui-render>`; `AssistResult`
    optionally carries an artifact. **DoD:** existing assist intents render via the generic host with
    no visual regression; dead template branches removed.

- **P4 — Build the authoring/exec resolver on the same host.**
  - Wire `authoring.editorKey`/`exec.runnerKey` token maps into the render host, retiring the
    hardcoded `EDITOR_PATH` table (`registry.component.ts:28`).

Stop after P0 if the spike doesn't earn its keep; P1–P4 are independently valuable.

---

## 9. Open decisions

1. **Artifact typing on the backend:** opaque JSON string (fastest P0) vs a minimal Java record
   mirroring the descriptor (earlier server-side validation). *Lean: opaque at P0, typed at P2 when
   actions raise the stakes.*
2. **Who authors the descriptor:** the model directly (prompt/format-constrained) vs a backend tool
   the agent calls that returns a validated descriptor. *Lean: tool-produced — deterministic,
   easier to allowlist and test.*
3. **Session UX:** dockable side panel vs full page vs contextual popover on the current page (the
   agent already receives a `page` context on `openSession`). *Lean: dockable panel reusing
   `assist-panel` ergonomics.*
4. **Confirm-then-apply reuse:** which existing confirm component/pattern to standardize on for
   `invoke` writes.

---

## 10. Non-goals (for this spike)

- Persisting agent-emitted artifacts as first-class `ComponentStore` objects.
- A general server-driven-UI framework for the whole app (this is scoped to the agent answer
  surface; authored UI keeps its current path until P4).
- Cross-agent (A2A) UI negotiation.
- Widening the render allowlist beyond §4.5 without an explicit review.

---

## 11. Touchpoints (files a P0 would add or change)

**Backend**
- `inspecto/src/main/java/com/gamma/intelligence/AgentAskResult.java` — add `artifact`; emit
  `INLINE_ARTIFACT`.
- `inspecto/src/main/java/com/gamma/intelligence/AgentAnswerSink.java` — add `onArtifact` (default).
- `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` — `event: artifact` SSE frame.
- `inspecto-intelligence/src/main/java/com/gamma/intelligence/InspectoIntelligenceAgent.java` —
  produce a real artifact for the seed query class.
- (P2) real-HTTP gate test per the `endpoint` skill for the first `invoke` op.

**Frontend**
- `inspecto-ui/src/app/inspecto/a2ui/a2ui-render.component.ts` *(new)* — generic host
  (model on `viz/viz-render.component.ts`).
- `inspecto-ui/src/app/inspecto/a2ui/a2ui-kinds.ts` *(new)* — `A2UI_COMPONENT_BY_KIND` allowlist map.
- New streaming chat surface (SSE consumer; pattern from
  `layout/common/notifications/notifications.component.ts`).
- Reuses: `component-model/*`, `data-table/`, `components/chart.component.ts`,
  `viz/plugins/kpi.component.ts`, `components/schema-form.component.ts`, `components/empty-state`.

**Docs**
- This file; index in `docs/INDEX.md`; MoSCoW line in `docs/REQUIREMENTS.md` if promoted from spike.
