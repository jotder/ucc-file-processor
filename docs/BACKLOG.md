# Consolidated Backlog — every OPEN item, one page

**Updated:** 2026-07-16 (doc-consolidation refresh) · **Owner:** whole team (update at every handoff
that closes/opens an item)

> **What this is.** The single index of ALL pending/deferred/open work: one line + status + where the
> detail lives. Detail stays in the linked source doc. **Shipped work is not recorded here** — it
> lives in git history, `REQUIREMENTS.md` status columns, and the archived plans under
> [`archived-documents/plans-archive/`](archived-documents/plans-archive/).
>
> **Where we are (2026-07-16):** roughly **75% of the chartered scope has shipped** — the
> REQUIREMENTS MUST + SHOULD engineering backlogs are empty; what remains is the COULD tier,
> externally-gated items (deployment, product decisions, the security module), feature follow-ons,
> and polish. Compiled from REQUIREMENTS §5 + the deferral sections of every archived plan
> (2026-07-16 sweep) + the live session notes.

---

## 1. Act first / in-flight repo state

| Item | Status | Source |
|---|---|---|
| Full `package.ps1` bundle (npm+jlink) not smoke-tested since the `spaces/` migration + bundle-prune fix | Smoke the built artifact before any release | `HANDOVER-multi-space.md`, `SESSION_STATUS.local.md` |
| **Dataset glob vs pipeline sink nesting** — a `physicalRef` Dataset reads `<dataRoot>/<ref>/**/*.parquet` recursively, but an authored pipeline `sink.persistent` store can nest INSIDE a source store's tree → silent double-count (UAT-proven, +72%). Demo symptom-fixed; the real contract is undecided: sink stores resolve under the data root, and/or dataset globs default to `database/`? | **Open — decide the contract, then align engine/docs/demo** | `docs/ops/uat-seeding.md` forensics |

## 2. Product remainder (MoSCoW of record: `REQUIREMENTS.md` §5)

| ID | Item | Status / blocker |
|---|---|---|
| ACQ-4 | Object-storage connectors — S3/MinIO/GCS-interop + SDK-free Azure Blob shipped; **open: GCS-native API** | Offline-blocked (no SDK jars) |
| OPS-5 | Provenance conservation on live data (built, off by default) | Needs a live deployment to verify |
| NFR-7 | Compliance certifications | PARTIAL (not started) |
| API-5 | Legacy route **physical deletion** — sunset mechanism + `inspecto_legacy_api_requests_total` meter shipped (W8); delete after the signed **30-days-at-zero soak** | Soak-gated policy call |
| EOI-7b | Publish eoiagent `0.1.0` artifacts to a registry (v0.1.0 cut + pinned 2026-07-08; CI rebuilds from tag meanwhile) | Infra/product call |
| BI-4 | Scheduled report **delivery**: CSV export ships as a `type:'report'` Job; **real PDF/PNG rendering** is placeholder text | Backend/Standard scope |
| AGT-5 P1 | Investigation: timeline/diff/anomaly tools, `root_cause_analysis`, Case Store, event ingress | Open (P0 shipped + hardened) — `superpower/embedded-intelligence-plan.md` §8 |
| AGT-5 P2–P5 | Author-everything (L1) → gated action (L2) + approvals inbox → bounded autonomy (L3) → learning | Open — same |
| AGT-5 cuts | QA-only (`incident_explain` waits on the eoiagent host seam); local-models-only | Open scope cuts — same |
| AGT-6 | AI behind every screen / agent graphs | PLANNED |
| SPC-5 | Per-tenant ABAC (rides the SEC-7 grants model; absorbs per-resource ACLs/ownership) | PLANNED (Enterprise) |
| E1 | Enterprise distributed tier / Stage-2 streaming | Demand-gated |
| INV-1 | Entity Projection backend (Link Analysis is UI/mock-projected today) | Open |

## 3. Feature follow-ons (deferral sections of shipped work)

| Area | Open items | Source (detail) |
|---|---|---|
| **API v1** | X-Actor full retirement on Standard · UI sign-out affordance · UI↔backend `ContentHash` float-parity conformance test · ETag beyond components+bootstrap · cursor pagination · Standard-edition jlink runtime vs Nimbus not re-verified (`-NoRuntime` until confirmed) | `archived-documents/plans-archive/api-contract-design.md` |
| **Bundle / Exchange** | `BundleRoutes` missing kinds: connection / authored-pipeline / job / saved-view (own stores) · `requires` present-but-different classification · per-editor "load as draft" import · **UI Exchange-surfaces track §3.6** (catalog Shared-with/by-me, offer flow, scope badges, pin/drift; backend + `bootstrap.features.exchange` ready) | `superpower/storage-layout-and-sharing-plan.md` (ACTIVE) · `archived-documents/plans-archive/transportability-plan.md` |
| **Job framework** | Pack in-flight-Run quiesce · sink→`ViewStore` auto-registration · `args`/`bind` type-inference · MNT-14 Archived-Incident sweep (**blocked** on backend Incident lifecycle + `ObjectStore` delete) · maintenance COULD tier (trends, space comparison, predictive) · `Scheduler` has no cron-cancel primitive (deleted job's timer ticks as inert no-op — revisit if churn bites) | `okf/backend/control-plane/jobs.md` · `archived-documents/plans-archive/system-maintenance-plan.md` |
| **Queries / BI** | Structured-query editor UI (SQL is the only authoring surface) · `graph`/`spatial`/`search`/`api` QueryTypes · more `$`-resolvers · responsive dashboard tiles (ResizeObserver re-render — the one unshipped studio-bi item) · dataset/widget/dashboard **sharing RBAC** (SEC-gated, `inspecto-security`-owned) · calculated-columns v2 = whitelist growth only (window/aggregate fns) | `archived-documents/plans-archive/query-kind-plan.md` · `studio-bi-improvements-plan.md` · `calculated-columns-design.md` |
| **Notifications** | Email channel impl + delivery-status webhooks · digest batching · time-based retention sweep · GeoIP · auth-gated per-user prefs/security triggers · **channels admin CRUD** (`/notifications/channels*` mock-only; backend channels are JVM flags) | `archived-documents/plans-archive/notification-system-and-audit-trail-plan.md` |
| **Signal / Decision networks** | Raw `/signals` endpoint + `SignalsService` (backend Signal contract) · Expectation/Alert-Rule ComponentKind promotion · **real condition-tree evaluator** (simulate is an honest 0-matched stub; routing consequences are no-ops until it exists) · LLM-backed Assist proposal | `archived-documents/plans-archive/signal-network-plan.md` · `decision-network-plan.md` |
| **Link analysis** | V1: multi-entity mapping, multi-root, incremental expand, all-paths, layout save, widget/dashboard placement, SVG/GraphML export, undo/redo, `attrCols` + schema-relationship model · V2+: advanced traversal, timeline, scoring, collaboration, algorithm library, AI assist, pattern packs | `archived-documents/plans-archive/link-analysis-studio-plan.md` §6–7 |
| **Geo map** | Phase 4 backend: DuckDB spatial, server-side projection/aggregation endpoint, `ComponentStore` widening for geo/link view kinds, progressive loading, worker binning | `archived-documents/plans-archive/geo-map-analysis-plan.md` |
| **Pipeline graph** | T15 adaptive back-pressure config · T16 `GET /pipelines/{id}/graph` G6 projection · T17 node inspector + live last-run overlay · T19 topology-editor wiring into live executor (CRUD backend done) · T32 config-less ad-hoc run route + `sink.view` `derived_sql` · mock-only: run-to-here `POST …/run`, `/config/icon-map`, `/asn1/modules` | `okf/backend/pipeline-graph/pipeline-graph-design.md` §14 · `archived-documents/plans-archive/flow-live-execution-plan.md` |
| **Acquisition / connections** | Connection workbench probe/explore/sample (`/connections/{id}/probe\|explore\|sample` — B2 never landed; real CRUD + `/test` work) · `target=proxy` on `/connections/test` 422s (proxy not a `ConnectionProfile` field) | 2026-07-15 gap sweep · `SESSION_STATUS.local.md` |
| **Incidents / cases** | I1 backend workflow resolution-gate · `CaseRule` scheduler auto-evaluation · Studio-dataset binding of case analytics · C3 configurable Findings sections + auto member-timeline · first-class `category`/`tags` params on `GET /objects` (low value) · workflow TOON overrides (`Workflow.load` exists, no boot scan into `ObjectService`) | `archived-documents/plans-archive/case-management-design.md` · `incidents-mail-ui-design.md` |
| **Reconciliation** | Scheduled `recon.run` Job + Alert Rule → Incident wiring (designed-for) · explicit non-goals: N>3, non-additive aggs, fuzzy keys | `okf/frontend/features/reconciliation.md` · `archived-documents/plans-archive/reconciliation-board-design.md` |
| **Menu builder** | M5: favorites, polish, a11y, seeded example · real backend navigation endpoint (mock-first) · open points O1 (curator) O2 (icons) O3 (seed) | `archived-documents/plans-archive/menu-builder-plan.md` |
| **Branding / misc backend** | Real `GET\|PUT /settings/branding` (mock-only persistence) · report-run artifact **content** download route (metadata ships) · db-browser: promote `DbJobRunStore.detectPostgres` to a shared helper + `DbAcquisitionLedger` Postgres round-trip test · legacy-alias usage logging for the W7 sunset · `SpaceManager.delete` doesn't `forget(spaceId)` on ConnectionRegistry/StabilityGate (minor) | gap sweeps · `archived-documents/plans-archive/db-browser-design.md` · `w7-ui-v1-migration.md` *(2026-07-16: "Enrichment routes read-only stub" closed — `POST /enrichment` hot-register shipped with onboarding P3)* |
| **Onboarding (Stream/Reference)** | Per-stage `POST /validate` findings → `blocked` chip state · "View as graph" link (lift proven null-tolerant; UI wiring only) · discard: unregister the live registry entry (ghost row ≤60s) + cascade the companion `_schema`/`_enrich` TOONs · enrichment deregister (deleted-on-disk job runs until restart) + schedule-interval change needs restart (`Scheduler` no-cancel, same as the jobs row) · enrichment stage has no `Validated` state (needs an enrichment sample-preview endpoint; design D4 Phase-2 = bounded end-to-end sample run) · `read_json array\|auto` preview timestamp-serialization edge (NDJSON immune) · date-less dumps partition as `year=1900` (cosmetic) · Reference Phase-2 engine semantics: cache/upsert/SCD versioning + refresh scheduling, row-level dedup, Stream grouping (GLOSSARY §3/§6-B roadmap) · optional templates entry (space-template-gallery precedent) | `okf/backend/control-plane/onboarding-authoring.md` · `okf/frontend/features/onboarding.md` · `archived-documents/plans-archive/stream-onboarding-design.md` |
| **Collector rename residual** | Pipeline TOON config-key `source:` block kept (renaming breaks authored TOON) — separate migration if ever wanted; `'SOURCE'` stage category unchanged | `okf/backend/gotchas/cross-cutting.md` |

## 4. UI residuals (small, valuable)

| Item | Source |
|---|---|
| ui-design-review residuals — R2 column suggestions, R2 object-create chips, R3 command registry + `/`-focus + j/k nav all SHIPPED 2026-07-17; remaining: R6 true offset paging per pane (revisit if a pane outgrows widen-and-refetch) · R8 pivot-bar (design-only until demanded twice) | `archived-documents/plans-archive/ui-design-review.md` · `okf/frontend/log.md` |
| Requirements "Delivered via" is free text — should be a real Component link (cross-kind picker + Registry reuse edge) | review sweep |
| `ComponentKind.deriveParts` seam — formalize when a 3rd composite kind needs it | same |
| Minor: shared chip primitive (sources/widgets/events) · mock audit trail seed-only · mock `POST /alerts/evaluate` always breaches · events live-tail cadence hardcoded 5 s · parser/node attribute tiers best-guess pending firm backend specs · pipeline-editor dry-run panel extraction (may be moot) · dataset editor `save()` create-on-edit — backend PUT exists, verify | same |

## 5. Engineering / tech-debt

The full engineering MoSCoW (build hygiene, `SourceService` decomposition, `agent.spi` facade,
Fuse-leftover removal, reactor split, shutdown robustness, `@PublicApi` freezing) lives in
**`superpower/modularization-optimization-plan.md`** §4 (ACTIVE, analysis unexecuted). Headline Musts:
M1 parent `dependencyManagement` · M2 `SourceService` decomposition · M3 `agent.spi` facade ·
M4 UI Fuse-leftover removal (~25.8k lines) · M5 coverage baseline · M6 repo-clutter sweep.

## 6. Security-module scope (deferred wholesale — do not partially implement elsewhere)

Identity/login, user model, role-assignment UI, Admin pane, server enforcement, lens-switcher
constraint — per `archived-documents/plans-archive/rbac-groundwork.md` §5. Rides on top:
**Lens Access P3** (subjects become Roles, matrix enforcement server-side — the shipped
catalog/profile/matrix reuse as designed) · dataset/widget/dashboard sharing RBAC ·
rbac open Qs: Q1 `canOnboardConnections` → Admin split · Q3 `canTriageRequirements` grant set ·
Q4 Requirements SLA (declined, revisit with roles) · X-Actor retirement (overlaps API v1 row).

## 7. Docs & open product questions

| Item | Status | Source |
|---|---|---|
| User-guide audit P1: #8 KPI authoring + Measure-reuse doc; #10 quarantine remediation/replay doc (or file product gap "D-ETL") | Open | `archived-documents/superpower-reviews/user-guide-audit.md` |
| User-guide audit P2: #11 de-jargon ("pro/pro max", "Stage-2"); #12 parser format-list alignment + GLOSSARY §6-B Matrix tense | Open | same |
| ADVANCED_GUIDE Control-API section regen (post-W7 route changes) | Open | review sweep |
| Open product questions: `canOnboardConnections` split, sunset timing, structured queries client-compiled | Awaiting product | `REQUIREMENTS.md` §7 |
| Interview backlog Qs: #2 parser required-vs-advanced, #5 Incident/Case mandatory fields + assignment model, #6 template scope, #7 KPI target ownership | Awaiting product | `archived-documents/plans-archive/frontend-review-and-completion-plan.md` §6 |
| FEATURE_INVENTORY gaps: LDIF `record_split` proposal; structured/`text_regex` block records; missing example files; `package.ps1` dir pre-creation | Open (snapshot 2026-06-20 — verify vs code) | `FEATURE_INVENTORY.md` |
| Template seed-pack enrichment (frontend C7) — continuous, not discrete | Ongoing | — |

## 8. Duplicate map (same work, multiple IDs — update all sources when closing)

| Canonical | Also recorded as |
|---|---|
| API-5 legacy sunset | w7-ui-v1-migration deferred follow-ons · legacy-alias logging row (§3 misc) |
| EOI-7b eoiagent publish | agent-kernel-replacement §open-items |
| Geo-map Phase 4 backend | INV-2 Phase 4 · ComponentStore view-kind widening |
| INV-1 Entity Projection backend | link-analysis V1 backend rows |
| MNT-14 archived-incident sweep | blocked-on = Incidents I1 backend workflow row |
| Sharing RBAC (SEC-gated) | Queries/BI row §3 · security-module scope §6 |

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays
authoritative), then delete the row here. New pending items discovered mid-shift get a row here at
handoff time (see the `handoff` skill). This page lists **open work only** — no DONE rows.
