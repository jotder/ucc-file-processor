# Consolidated Backlog — every pending item, one page

**Updated:** 2026-07-09 · **Owner:** whole team (update at every handoff that closes/opens an item)

> **What this is.** The single index of ALL pending/deferred/open work, consolidated from
> `REQUIREMENTS.md` §5 (the product MoSCoW of record), every `docs/superpower/*` plan,
> `flow-graph-design.md` §14, `FEATURE_INVENTORY.md`, review sheets, and the live session/handover
> notes. **Detail stays in the linked source doc** — this page only tracks *that* an item is open,
> its one-line scope, status/blocker, and where the truth lives. Duplicate IDs across plans have
> been collapsed (see §8).
>
> `docs/superpower/backend-backlog.md` remains for its item detail but is now indexed here.

---

## 1. In-flight repo state (act first, before any new work)

| Item | Status | Source |
|---|---|---|
| AGT-5 P0 **hardening pass** (RAG corpus, SSE streaming, nav auto-derivation) | **COMMITTED & PUSHED** (verified 2026-07-09: working tree clean, `master`==`origin/master` @ `7b6c7db`) | `SESSION_STATUS.local.md` |
| AGT-5 P0 spine `f2f9506` + earlier chain (`c06bf7e`…`23cc08c`, `e67bea0`, `3afe606`) | **PUSHED** (verified 2026-07-09: `master`==`origin/master` @ `7b6c7db`) | `SESSION_STATUS.local.md` |
| `ControlApiConnectionsTest.java` | Another session's deliverable — **hands off, do not commit** | `SESSION_STATUS.local.md` |
| Full `package.ps1` bundle (npm+jlink) not re-run since the `spaces/` migration | Smoke-test the built artifact before any release | `HANDOVER-multi-space.md` |

## 2. MUST — release-gating remainder (product)

| ID | Item | Status / blocker | Source |
|---|---|---|---|
| ACQ-4 | Object-storage connectors — **open: Azure Blob native, GCS-native APIs** (S3/MinIO/GCS-interop shipped; NFS/SMB stays mounted-share) | PARTIAL | `REQUIREMENTS.md` |
| SEC-7 | RBAC/ABAC — **open: data-scoped grants** (= rbac-groundwork Q2 = SEC-7d) | Blocked on product decision | `REQUIREMENTS.md`, `superpower/rbac-groundwork.md` §4, `superpower/resource-permissions-design.md` §5 |
| EOI-7 | eoiagent `0.1.0` release: cut+pin version, publish artifacts (today: `0.1.0-SNAPSHOT`, local-`.m2` only — CI cannot resolve) | PLANNED; infra/product call | `REQUIREMENTS.md`, `superpower/agent-kernel-replacement-plan.md` §open-items |

## 3. SHOULD (product)

| ID | Item | Status / blocker | Source |
|---|---|---|---|
| DAT-4 | **Matrix materialization** (`materialize` job task) — same work as backend-backlog §2 and ia-reorg Phase C | PLANNED, ~1 shift | `REQUIREMENTS.md`, `superpower/backend-backlog.md` §2, `superpower/ia-vocabulary-reorg.md` C |
| DAT-5 | **Row-level calculated columns** — same as frontend-completion C8, studio-bi Phase B | PLANNED; expression-safety design first | `REQUIREMENTS.md`, `superpower/frontend-review-and-completion-plan.md` C8 |
| — | **Alert-Rule write endpoints** (`POST/PUT/DELETE /alerts/rules`; UI is mock-only, live server 503s). New capability `canAuthorAlertRules` | Not started | `superpower/backend-backlog.md` §4, `superpower/alert-rule-authoring-plan.md` |
| — | **Widget-Library M2**: `DatasetResultService` M2 form, materialized-dataset refresh; sharing/RBAC blocked on SEC-7 | Not started | `superpower/backend-backlog.md` §1, `superpower/widget-library-spec.md` |
| — | **Job templates** (backend) — backend-backlog §3 = ia-reorg Phase D; REQUIREMENTS shows PIP-6 SHIPPED — **reconcile before starting** | Not started / verify | `superpower/backend-backlog.md` §3 |
| INC-4 | Incident workflow depth (queues/escalation/watchers) | Blocked on product design | `REQUIREMENTS.md` |
| ACQ-5 | Streaming (Kafka) source consumer | **Hard-blocked offline** (no client jars in `.m2`) | `REQUIREMENTS.md` |
| API-5 | Legacy route sunset — gated on `inspecto_legacy_api_requests_total` soak signal (= W7 deferred follow-ons) | Policy call | `REQUIREMENTS.md`, `superpower/w7-ui-v1-migration.md` |
| OPS-5 | Provenance conservation on live data (built, off by default) | Needs live verification | `REQUIREMENTS.md` |

## 4. COULD (product / feature phases)

| ID / plan | Item | Status | Source |
|---|---|---|---|
| AGT-5 P1 | Investigation: timeline/diff/anomaly tools, `root_cause_analysis`, Case Store, event ingress | Open (P0 shipped+hardened) | `superpower/embedded-intelligence-plan.md` §8 |
| AGT-5 P2–P5 | Author-everything (L1) → gated action (L2) + approvals inbox → bounded autonomy (L3) → learning | Open | same |
| AGT-5 cuts | QA-only (`incident_explain` waits on eoiagent host seam); local-models-only (no hosted-provider companion) | Open scope cuts | same |
| AGT-6 | AI behind every screen / agent graphs | PLANNED | `REQUIREMENTS.md` |
| BI-6 / BI-8 | Embed viewer UI; template gallery/browsing UI (backends shipped) | PARTIAL | `REQUIREMENTS.md` |
| MET-5 | Component draft/published version history | PLANNED, ~1 shift | `REQUIREMENTS.md` |
| SPC-5 | Per-tenant ABAC (rides SEC-7 grants model) | PLANNED (E) | `REQUIREMENTS.md` |
| E1 | Enterprise distributed tier / Stage-2 streaming | Demand-gated | `REQUIREMENTS.md` |
| Link-analysis V1 | Multi-entity mapping, multi-root, incremental expand, all-paths, layout save, widget/dashboard placement, SVG/GraphML export, undo/redo; design §7 `attrCols` + schema-relationship model | Open | `superpower/link-analysis-studio-plan.md` §6–7 |
| Link-analysis V2+ | Advanced traversal, timeline, scoring, collaboration (backend-blocked); backlog: algorithm library, AI assist, pattern packs, geospatial, scale | Open | same |
| Geo-map Phase 4 | Backend+perf: DuckDB spatial extension, server-side projection/aggregation endpoint, `ComponentStore` view-kind widening (geo/link), progressive loading, worker binning | Open (UI phases 0–4c done) | `superpower/geo-map-analysis-plan.md` |
| Flow T15 | Adaptive back-pressure defaults configurable | Not started | `flow-graph-design.md` §14 |
| Flow T16 | `GET /pipelines/{id}/graph` → G6 renderer projection | Not started | same |
| Flow T17 | Node inspector panel + live last-run overlay | Not started | same |
| Flow T19 | **Flow-topology editor** (node/edge CRUD on G6 canvas) + wiring authored flows into live executor | Partial (CRUD backend done) | same |
| Flow T32 | Dedicated `POST /pipelines/authored/{id}/run` (config-less ad-hoc run); `sink.view` `derived_sql` follow-up | Deferred (phases A–C done) | `flow-live-execution-plan.md` |
| Menu builder | Slices M1–M5; open points O1 (who curates), O2 (icons), O3 (seed example) | **DRAFT, awaiting sign-off** | `superpower/menu-builder-plan.md` |
| Query kind | Structured-query editor UI (SQL-only today); graph/spatial/search/api `QueryType`s not built; more `$`-resolvers | Follow-on | `superpower/query-kind-plan.md` |
| Signal network | Raw `/signals` endpoint + `SignalsService`; backend Signal contract | Follow-on (UI/mock shipped) | `superpower/signal-network-plan.md` |
| Decision network | Expectation/Alert-Rule ComponentKind promotion; real platform-action execution (mock today); LLM-backed Assist proposal | Deferred cuts | `superpower/decision-network-plan.md` |
| Studio BI | Remaining Phase A/B/C items (filter bar, drill-through, time-grain, Measure builder, viz plugins, PNG export, chart a11y, KPI & Reports gallery) — overlaps BI-3/DAT-5; **verify what remains** | No status header | `superpower/studio-bi-improvements-plan.md` |
| Component model | Verify P2 adapters / P3 registry view (menu placement + gating deferred to security module) / P4 formatter consolidation completion | Verify | `superpower/component-model-adoption-plan.md` |
| Transportability | `requires` present-but-different classification; bespoke editor draft-load; connection/pipeline/job/view bundle kinds (deferred to own stores) | Deferred cuts | `superpower/transportability-plan.md` |
| Multi-space leftover | `SpaceManager.delete` doesn't tear down per-space ConnectionRegistry/StabilityGate entries (`forget(spaceId)`) | Minor, fix if it bites | `HANDOVER-multi-space.md` |
| Exchange UI | **Bind-a-shared-dataset flow**: consumer creates a local dataset whose `physicalRef` = a granted `shared/<owner>/<item>` ref. Needed to exercise the scope badge + widget "Access revoked" empty-state end-to-end against a live backend (both shipped but only unit-verified). | Not started (§3.6 UI track shipped) | `superpower/storage-layout-and-sharing-plan.md` §3.6 · `SESSION_STATUS.local.md` |
| Exchange UI | **Sharing-grid row-action ergonomics**: the grant/offer grids carry many columns, so the action column (approve/refresh/pin/expiry) sits behind a horizontal scroll on a narrow pane. Consider pinning the actions column or condensing columns. | Minor polish | `SESSION_STATUS.local.md` |

## 5. Engineering / tech-debt backlog

The full engineering MoSCoW (build hygiene, `SourceService` decomposition, `agent.spi` facade,
Fuse-leftover removal, reactor split, shutdown robustness, `@PublicApi` freezing, etc.) lives in
**`superpower/modularization-optimization-plan.md`** §4 — not duplicated here. Headline Musts:
M1 parent `dependencyManagement` · M2 `SourceService` decomposition · M3 `agent.spi` facade ·
M4 UI Fuse-leftover removal (~25.8k lines) · M5 coverage baseline (intelligence/agent-hosted) ·
M6 repo-clutter sweep (incl. `file-processor-deploy-old/`, root logs, `.iml`).

## 6. Security-module scope (deferred wholesale)

Identity/login, user model, role-assignment UI, Admin pane, server enforcement, lens-switcher
constraint, data scoping — all explicitly deferred to the security module by
`superpower/rbac-groundwork.md` §5. Do not partially implement these elsewhere.

## 7. Docs & open product questions

| Item | Status | Source |
|---|---|---|
| User-guide audit P1: #8 KPI authoring + Measure-reuse doc; #10 quarantine remediation/replay doc (or file product gap "D-ETL") | Open | `superpower/reviews/user-guide-audit.md` |
| User-guide audit P2: #11 de-jargon ("pro/pro max" C8, "Stage-2" C9); #12 parser format-list alignment (C11) + GLOSSARY §6-B Matrix tense (C5) | Open | same |
| Open product questions R4/R6/R8: data-scoped grants, `canOnboardConnections` split, sunset timing, structured queries client-compiled | Awaiting product | `REQUIREMENTS.md` §7 |
| Interview backlog Qs: #2 parser required-vs-advanced, #5 Incident/Case mandatory fields + assignment model, #6 template scope, #7 KPI target ownership | Awaiting product | `superpower/frontend-review-and-completion-plan.md` §6 |
| rbac-groundwork Q1 (`canOnboardConnections` → Admin), Q3 (Requirements triage grant set), Q4 (Requirements SLA — declined, revisit) | Awaiting product | `superpower/rbac-groundwork.md` §4 |
| FEATURE_INVENTORY gaps: LDIF `record_split "\n\n"` `[PROPOSED]`; structured/`text_regex` block records; no `*_flow.toon`/`*_rca.toon` example files; no subscriber `.dat` sample; `package.ps1` pre-creates dirs only for adjustment+voucher | Open (snapshot 2026-06-20 — verify against code) | `FEATURE_INVENTORY.md` |
| NFR-7 compliance certifications | PARTIAL (not started) | `REQUIREMENTS.md` |

## 8. Duplicate map (same work, multiple IDs — always update all listed sources when closing)

| Canonical | Also recorded as |
|---|---|
| DAT-4 Matrix materialization | backend-backlog §2 · ia-vocabulary-reorg Phase C |
| DAT-5 calculated columns | frontend-completion C8 · studio-bi Phase B |
| Job templates | backend-backlog §3 · ia-vocabulary-reorg Phase D · PIP-6 (**REQUIREMENTS says SHIPPED — reconcile**) |
| SEC-7 data-scoped grants | rbac-groundwork Q2 · resource-permissions SEC-7d · prerequisite of SPC-5 |
| API-5 legacy sunset | w7-ui-v1-migration deferred follow-ons |
| EOI-7 eoiagent release | agent-kernel-replacement §open-items · SESSION_STATUS eoiagent blocker |
| Alert-Rule backend endpoints | alert-rule-authoring §Backend · backend-backlog §4 |
| Geo-map Phase 4 backend | INV-2 Phase 4 · ComponentStore view-kind widening |

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays
authoritative), then delete or strike the row here. New pending items discovered mid-shift get a
row here at handoff time (see the `handoff` skill).
