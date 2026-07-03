# Inspecto Frontend — Review & Completion Plan (mock-first)

**Status:** APPROVED direction, 2026-07-02 · **Owner:** enterprise-PM track
**Companions:** [`feature-matrix-editions.md`](feature-matrix-editions.md) (MoSCoW) · [`../GLOSSARY.md`](../GLOSSARY.md) (binding vocabulary) ·
[`component-model.md`](component-model.md) (metamodel) · UI ground-truth inventory in §7.

**Mandate.** Complete the platform **frontend-first with a full mock backend**. Inspecto is a
**meta-product**: it builds multitenant projects (**Spaces**) — Telecom Revenue Assurance, Fraud
Management, Financial Auditing, Link Analysis — for three persona sets (Business User, App Developer,
Operations). Everything currently marked "shipped" is treated as **review-required**: field sets are
wrong-sized, UX is inconsistent, logic is trapped in components, mocks are scattered and ephemeral.

## 0. Decisions locked (product owner, 2026-07-02)

| # | Decision | Choice |
|---|---|---|
| D1 | Persona surfacing | **One console + persona lens** — nav/toolbars tagged Business/Builder/Ops, "View as" switcher; maps onto RBAC when `inspecto-security` lands |
| D2 | Meta-product delivery | **Space Templates gallery** — Space Template = blueprint bundle of Components; "New Space from template" with RA / FMS / Audit / Link-Analysis templates |
| D3 | Mock backend | **Unified stateful mock store** — one per-Space in-memory entity store behind a single interceptor, localStorage-persisted, seeded per template, REST-contract-faithful |
| D4 | Forms | **Schema-driven** — every Component Type's config schema declares attributes as `required \| optional \| advanced`; one shared SchemaForm renderer implements 3-tier disclosure |

---

## 1. Persona → surface map (lens model)

A **Lens** is a persona-scoped filter over one console: nav items, toolbar actions, and home page.
Every route declares `lenses: ('business'|'builder'|'ops')[]`; the switcher filters, never forks.

| Lens | Mission | Panes (existing) | Panes (new — §5) |
|---|---|---|---|
| **Business** | Consume data; raise KPI/Report/Reconciliation requirements; investigate provenance/lineage; state rule requirements | Dashboards (view), KPI & Reports, Catalog + lineage graph, Runs (read-only observe), Studio explore (read) | **Requirements intake**, **Reconciliation reports**, Expectation viewer |
| **Builder** (App Developer) | Collect + medallion streams in the **Workbench**; build Datasets/Widgets/Dashboards in **Studio**; satisfy Requirements; test outcomes | Connections (workbench), Sources, Pipelines editor, Enrichment, Jobs, Studio (datasets/widgets/dashboards), Components/Registry, Catalog, Assist | Expectation builder, Decision Rule builder, Scheduled reports, Link Analysis studio |
| **Ops** | Built-in, cross-Space standard features | Events, Alerts, Incidents/Cases (objects), Audit logs, Diagnoses, Processing status, Runs, Jobs (ops view), Spaces admin, Config, Notification prefs | Notification center/channels |

**Glossary additions required** (do in Wave 0; GLOSSARY.md is binding — new concepts must land there
before code):

- **Lens** — a persona-scoped view of the one console (Business / Builder / Ops). Not a permission.
- **Workbench** — the Builder surface for Connections + Sources + Pipelines (already used informally in §3 Stream; formalize).
- **Space Template** — a reusable blueprint bundle of Components (Sources, Pipelines, Schemas, Datasets, Widgets, Dashboards, Rules, seed data) that instantiates a new Space. Type→Instance: Template is the Type, Space the Instance.
- **Requirement** — a Business-authored request (KPI / Report / Reconciliation / Rule) with lifecycle `draft → submitted → in-build → delivered → accepted`, linkable to the Components that satisfy it.
- **Reconciliation** — a comparison between two Datasets on matching keys producing a **Break** report (unmatched/mismatched records). Core RA/Audit vertical concept.

---

## 2. Platform workstreams (foundations — everything else depends on these)

### W1 — Unified stateful mock store *(replaces 7 scattered interceptors)* — ✅ SHIPPED 2026-07-02

**Done** (`ae36be3` Wave-0 core + `7bbc56d` full migration, on `origin/master`): all six feature mocks
(`studio`, `pipeline`, `demo`, `connection`, `ops`, `jobs`) are handlers over the persistent per-Space
`MockStore` (`inspecto/mock/`), incl. the liveness simulator (lazy per-request tick — Runs complete,
Events/Alerts append) and integrity 409s. `space.interceptor` stays (header injection), as planned.
Connections CRUD is store-backed with the real ConnectionRoutes contract (`087d0e9`).

- `inspecto/mock/` (framework-free core + one thin `HttpInterceptorFn`):
  - **Entity store**: per-Space collections keyed by Component kind + ops entities (events, runs, alerts, incidents, audit entries), CRUD with referential-integrity hooks (deleting a Dataset flags bound Widgets; deleting a Connection blocks Sources referencing it → mirrors the real 409 behavior).
  - **Persistence**: localStorage snapshot per Space (versioned, `Reset demo data` action); seeded from **template seed packs** (§W5).
  - **Contract fidelity**: routes/status codes/error envelopes mirror the real ControlApi (incl. 409/422/503 semantics) so backend cutover is a flag flip (`environment.mock*` per area, as `mockStudio` does today).
  - **Liveness**: a small simulator ticks Runs/Events/Alerts (queued→running→complete, event stream append) so Ops screens feel real.
- Migrate the six feature mocks onto it; delete them. `space.interceptor` stays (header injection).

### W2 — Attribute registry + SchemaForm (the "required/optional/advanced" engine)

- In the framework-free `component-model/` lib: `AttributeSpec { key, label, type, tier: 'required'|'optional'|'advanced', default?, validation?, dependsOn?, help }`; each **Component Type** (parser kinds, connection kinds, source, job, widget, dataset, rule kinds…) declares `attributes: AttributeSpec[]`.
- One **`<inspecto-schema-form>`** renderer: required tier always visible; optional collapsed group; advanced behind a ⚙ icon; inline validation from the spec; consistent toolbar (Save/Test/Reset) — replaces bespoke reactive forms in the 12+ form dialogs found in inventory.
- The **attribute audit** (per-component review step R2, §3) is what *produces* these specs — the field-set problems ("more or less fields than the function needs") get fixed in the spec, and every form inherits the fix.
- Bespoke canvases (pipeline DAG editor, dashboard tile layout) keep custom UIs but embed SchemaForm for their config panels (parser dialog property sheet, node config, widget options).

### W3 — TS core library consolidation (framework portability)

Target layering — components become thin Angular shells over pure TS:

| Lib (framework-free) | Contents | Today |
|---|---|---|
| `inspecto/component-model/` | metamodel + **AttributeSpec registry** (W2) | exists, pure ✓ |
| `inspecto/query/` | query model/compiler/runtime | exists, mostly pure ✓ |
| `inspecto/viz/` | plugin registry, QuerySpec, show-me | pure except `viz-render.component` + `dataset-result.service` → split Angular shell out |
| `inspecto/graph/` · `format/` · `rule/` | graph types/layout · formatters · rule types | pure ✓ / grow with C2/C3/C5 |
| `inspecto/mock/` (new) | mock entity store + simulator + seeds | W1 |

Rules enforced in review: no business logic, validation, or data shaping in `.component.ts`;
components = template + signals + calls into libs. Add a lint boundary check (no `@angular/*` imports
under the pure libs) to CI alongside `lint:tokens`.

### W4 — Persona lens shell — ✅ COMPLETE (2026-07-03)

Header "View as" switcher (persisted per user, `reviews/lens-shell.md` Phase 1) + Workbench read-only
gating including defense-in-depth on canvas mutation methods (Phase 1b) + per-lens home page
(`reviews/lens-shell-phase2.md`: Business → `kpi-reports`, Builder → `pipelines`, Ops → `events`).
**Nav-model lens tagging was evaluated and explicitly declined** (confirmed with the product owner
2026-07-03): the single-console decision means every lens sees identical nav — only actions are gated,
never nav visibility. No duplicated components; the switcher is one shared `LensService` + component.

### W5 — Space Templates gallery — ✅ SHIPPED 2026-07-03

- **Done** (`reviews/space-templates.md`): `SpaceTemplate` as a **static TS registry + REST-faithful
  mock endpoints** (`GET /spaces/templates`, `template?` on `POST /spaces`) — deliberate, owner-approved
  deviation from the original "Component kind" wording (templates are server-global and carry seed
  *functions*, unserializable into the per-space store). **Seed packs** (TS modules, full-rich):
  `telecom-ra`, `fraud-mgmt`, `financial-audit`, `link-analysis` — each a coherent set of
  Connections→Pipelines→Datasets(+sample rows)→Widgets→Dashboards→Requirement→Reconciliation/Rules +
  Incidents/Events for Ops realism, coherence-tested (widgets↔datasets↔sources↔tiles↔recon keys).
- Spaces admin gained the "New space from template" gallery (cards w/ contents preview →
  ask-the-minimum naming step w/ inline dup-id block → offer to switch). Enabler: the server-global
  `/spaces` surface is now mocked (`mockSpaces` flag, dev default **on**) — mock dev boot is genuinely
  multi-space (header switcher live, per-space localStorage isolation).
- Seed packs keep getting richer as C-items land (C7 continuous; Entity/Link graph viz arrives with C5).

---

## 3. Component review protocol (applies to EVERY pane, including "shipped" ones)

Each functional component gets one **review sheet** (`docs/superpower/reviews/<pane>.md`) worked in
this order:

| Step | Check | Output |
|---|---|---|
| R1 **Glossary** | Canonical name for the pane + every on-screen noun; concept missing → propose GLOSSARY addition first | vocabulary diff |
| R2 **Attribute audit** | Enumerate the entity's real attributes; classify `required / optional / advanced / remove`; add missing, delete speculative | `AttributeSpec[]` in component-model (feeds W2) |
| R3 **UX pass** | Toolbar-first: actions as icon buttons w/ tooltips in a consistent `<inspecto-toolbar>`; icon-led density; empty/loading/error/skeleton states; progressive disclosure per D4; keyboard + WCAG (axe gate) | redesigned pane |
| R4 **Reuse pass** | Map every widget on the pane to the design system (status-badge, empty-state, skeleton, data-table tiers, connectivity-banner, chart, schema-form, toolbar); kill one-off variants | consolidation diff |
| R5 **Logic extraction** | Move data shaping/validation/state machines to the pure libs (W3); component ≤ ~150 lines as the working target | lib modules + thin component |
| R6 **Mock contract** | Pane runs fully on the W1 store: CRUD round-trips, survives reload, integrity errors surfaced properly | seeds + interceptor routes |
| R7 **Interview sheet** | Anything not obvious — field semantics, defaults, which actions each Lens sees, vertical-specific behavior — becomes explicit questions; batched to the product owner per wave (AskUserQuestion); answers recorded in the sheet | decision log |
| R8 **Verify (DoD)** | `lint:tokens` · boundary lint · a11y spec · unit tests for extracted lib code · `test:ci` green · live smoke on :4204 (0 console errors) | evidence in the sheet |

**Definition of Done for a pane** = all eight steps evidenced. No pane is "shipped" again without a sheet.

---

## 4. Review waves (existing surfaces, sequenced)

Foundations W1+W2 land first (W3 proceeds opportunistically inside R5; W4/W5 land with Waves 2–3).

| Wave | Panes | Rationale / known issues |
|---|---|---|
| **0 — Foundations** | W1 mock store · W2 SchemaForm MVP · Glossary additions (§1) · lens tagging groundwork | everything depends on these — **STATUS 2026-07-02: SHIPPED** — glossary terms in; `inspecto/mock/` (MockStore + localStorage persistence + per-space seeds + integrity rules + unified interceptor) live with studio/pipeline mocks absorbed (jobs/ops/connection/demo migrate as the Wave-1 opener); `AttributeSpec` registry + `<inspecto-schema-form>` shipped with the jobs dialog as pilot (incl. restoring the dropped `catchUp` attribute) + `/design` gallery entry. Lens tagging moved to Wave 2 with the shell. |
| **1 — Builder: Workbench** | Connections + connection-workbench (247L) — ✅ shipped (ask-the-minimum two-step) · Sources · **Pipelines editor — ✅ decomposed 2026-07-02** (752L→685L container + `pipeline-palette`/`pipeline-inspector` components + 14 pure fns/reducers extracted to `pipeline-graph.ts`; review `docs/superpower/reviews/pipeline-editor.md`; further split of dry-run/validate panels deferred) · **parser-config dialog — ✅ shipped 2026-07-02** (first big SchemaForm conversion, all 9 parser kinds tiered; review sheet `docs/superpower/reviews/parser-config.md`) · **node-config dialog — ✅ shipped 2026-07-02** (per-node-type AttributeSpec schemas for collectors/transforms/sinks via `node-attributes.ts`; SchemaForm + collapsed free-form escape hatch; review `docs/superpower/reviews/node-config.md`) · **Sources — ✅ reviewed 2026-07-02** (read-only; added missing pane spec/a11y gate; `reviews/sources.md`) · **Enrichment — ✅ reviewed 2026-07-02** (read-only; hand-rolled empty-state→`<inspecto-empty-state>` + status-badge renderer + pane spec; `reviews/enrichment.md`) · **Jobs + job-form — ✅ reviewed 2026-07-02** (SchemaForm pilot; added inline dup-id guard per rule #1; `reviews/jobs.md`) | **WAVE 1 COMPLETE** — all panes have review sheets · **interview answered 2026-07-02** (dup-name block product-wide · keep persisting SchemaForm defaults · Business read-only in Workbench) |
| **2 — Builder: Studio** + W4 lens shell | **Dataset editor — ✅** (dup-guard; SchemaForm conversion evaluated + deferred, `reviews/dataset-editor.md`) · **Widget-options — ✅** (first Studio SchemaForm conversion; shared renderer gained an always-visible-optional tier, `reviews/widget-options.md`) · **Widgets/Explore — ✅** (`reviews/widgets-explore.md`) · **Dashboards editor — ✅** (`reviews/dashboard-editor.md`) · **Components/Registry — ✅** (already compliant, no changes needed, `reviews/registry.md`) · **Catalog + lineage graph — ✅** (empty-state fix + 2 new specs, `reviews/catalog-lineage.md`) · **Assist — ✅** (`reviews/assist.md`) — **all named panes reviewed 2026-07-02/03**; **W4 lens shell — ✅ COMPLETE 2026-07-03** (switcher, Workbench read-only gating, per-lens home page — see the W4 section above) | **WAVE 2 FULLY COMPLETE** — every named pane reviewed and the lens shell shipped |
| **3 — Business** + W5 templates | **Dashboard — ✅** (`reviews/dashboard.md`) · **KPI & Reports — ✅** (`reviews/kpi-reports.md`) · **Runs/run-detail — ✅** (Business read-only observe, `reviews/runs.md`) · Catalog lineage (business view — covered by Wave-2 `reviews/catalog-lineage.md`) · **Requirements intake (C1) — ✅ SHIPPED 2026-07-03** (`reviews/requirements-intake.md`) · **Reconciliation MVP (C9) — ✅ SHIPPED 2026-07-03** (`reviews/reconciliation.md`) | business lens is real; **all Wave-3 panes + C1 + C9 done**; **W5 Space-Templates gallery — ✅ SHIPPED 2026-07-03** (4 full-rich seed packs + gallery + mocked `/spaces`, `reviews/space-templates.md`) — **WAVE 3 FULLY COMPLETE** |
| **4 — Ops** | **Events — ✅ reviewed 2026-07-03** (mock search-filters/CSV-export/saved-view fixes + first spec, `reviews/events.md`) · **Alerts — ✅** (severity badge + banned-toast fix; mock evaluate now fires, `reviews/alerts.md`) · **Objects → Incidents/Cases — ✅** (full `/objects` mock domain — create/transition/links/graph/notes/RCA were all un-mocked; 5 empty-state fixes; 2 specs, `reviews/incidents-cases.md`) · **Audit logs — ✅** (seeded a 10-entry audit trail; pane was already the reference style, `reviews/audit-logs.md`) · **Diagnoses — ✅** (badge/toast/fmtDateTime trio + spec, `reviews/diagnoses.md`) · **Processing status — ✅** (review-only, fully compliant, `reviews/processing-status.md`) · **Notification center (C4) — ✅ SHIPPED 2026-07-03** (new 3-tab Ops pane: Channels (SchemaForm-authored email/webhook) · Deliveries ledger · embedded Preferences; alert+incident fan-out via the shared `mock/notify.ts` core; prefs PUT now persists; owner decisions in `reviews/notification-center.md`) · **Spaces admin — ✅ reviewed 2026-07-03** (banned-toast + empty-state fixes; inline dup-id guard added to space-form + create-from-bundle — 9th pane on the rule, `reviews/spaces-admin.md`) · Config | cross-Space standard features; live simulator makes them demo-strong — **8/9 panes done 2026-07-03**; only Config remains |
| **5 — Hardening** | design-system gallery updated w/ new primitives · icon-settings/model-settings sweep · full-app a11y + responsive pass · GAUNTLET + bundle smoke | release-candidate quality |

Each wave ends with: review sheets committed · batched interview answered · GAUNTLET green · live
smoke · handoff per shift protocol.

---

## 5. Completion plan (net-new, mock-backed — from the MoSCoW gaps + persona needs)

| # | Item | Persona | Depends on | Priority |
|---|---|---|---|---|
| C1 | **Requirements intake** — author KPI/Report/Reconciliation/Rule Requirements; lifecycle; link to delivering Components; Builder sees a queue | Business | W1, W2 | **P1** (Wave 3) |
| C9 | **Reconciliation** — define Dataset-vs-Dataset match (keys, tolerances) → Break report + Break drill grid; feeds RA/Audit templates | Business | W1, W2, query lib | **P1** (Wave 3) |
| C2 | **Expectation builder** — DQ rules (non-null/range/regex/referential) authored via SchemaForm, attached to Pipelines/Jobs; mock failures raise Incidents | Builder | W2, `rule/` lib | **P1** (Wave 4) |
| C4 | **Notification center** — Alert/Incident delivery to channels (email/webhook mock), per-user prefs (extends existing pane) — **✅ SHIPPED 2026-07-03** (`reviews/notification-center.md`) | Ops | W1 | **P1** (Wave 4) |
| C3 | **Decision Rule builder** — business routing rules + consequences, surfaced as first-class (today buried in `transform.route`) | Builder | C2 patterns | **P2** |
| C6 | **Scheduled reports** — Dashboard/Report on a Trigger → export (PNG exists; add PDF/CSV mock + schedule UI) | Builder/Business | W1, Jobs review | **P2** |
| C5 | **Link Analysis studio** — Entity/Link Graph Visualization Type; Widget binds a Dataset via mapping wiring (per GLOSSARY §11 P3 design); pivot/expand UI inside Cases | Builder/Business | W3 graph lib, Studio wave | **P2** (keystone of the link-analysis template + xDR story) |
| C8 | Row-level calculated columns in Datasets | Builder | (already spawned as separate task) | P2 |
| C7 | Template seed-pack enrichment (RA breaks, FMS alert scenarios, audit trails, entity graphs) | all | C9, C2, C5 | **P3** continuous |

**Dependency spine:** W1 → W2 → (Wave 1–2 reviews) → C1/C9 → C2/C4 → C3/C6/C5 → C7.
Backend cutover later = replace mock store per area behind the existing env flags; the REST contract
was kept faithful, and the AttributeSpec registry becomes the shared config-schema source when the
backend `ComponentStore` enum is widened.

## 6. Interview backlog (standing; batched per wave)

Seed questions already known to be non-obvious — each wave adds its own via R7:

1. Per pane × lens: which actions are visible to Business vs Builder vs Ops? (default: Business read-only everywhere except Requirements.) — **ANSWERED 2026-07-02 (Wave-1 batch):** Business is **read-only** across the Builder/Workbench panes, authoring gated to Builder; gating wiring lands with the lens shell in Wave 2.
2. Parser kinds: which of the 9 formats' options are truly `required` vs `advanced`? (drives the first big AttributeSpec set)
3. Reconciliation semantics: match keys only, or tolerance-based amount matching? Break lifecycle (auto-close on re-match?) — **ANSWERED 2026-07-03 (Wave-3 batch):** keys + a configurable tolerance (absolute or %) on numeric compare columns; a break **auto-closes** when its key re-matches within tolerance on a later run (with an auto-resolved note), manual override remains possible.
4. Requirement lifecycle: who accepts — Business author or Ops? SLA on requirements? — **ANSWERED 2026-07-03 (Wave-3 batch):** Business submits a Requirement (Submitted); it lands in a **Builder-facing queue**; Builder accepts/rejects (Accepted/Rejected), then delivers by linking the requirement to the Component(s) that satisfy it. No separate approval role; no SLA timer built yet (flag if wanted later).
5. Incident/Case: which fields are mandatory at creation vs triage? Assignment model (queues vs direct)?
6. Space Template scope: config-only blueprint, or including sample data by default?
7. KPI targets/thresholds: authored by Business (requirement) or Builder (implementation)?

## 7. Ground-truth inventory (2026-07-02, basis for this plan)

48 components / 26 admin panes; 44 API services; design system = alert, assist-panel, chart,
connectivity-banner, empty-state, skeleton, status-badge (+ grid, data-table tiers, query panel).
Pure libs: component-model, graph, format, query (viz partially coupled). Mocks: 7 interceptors,
stateful-but-ephemeral, no persistence. Forms: 100% hand-written reactive forms, no schema renderer.
Top oversize components: pipeline-editor 752L, jobs 330L, pipeline-editor-graph 299L, object-detail
279L, events 259L, run-detail 252L, connection-workbench 247L, dashboard-editor 243L, explore 236L.
