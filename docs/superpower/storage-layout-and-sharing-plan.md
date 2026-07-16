# Storage Layout & Cross-Space Sharing — Plan

> **Status: BACKEND SHIPPED L0→S3 (2026-07-09, on `master`, unpushed). UI track (§3.6 surfaces) still
> open.** Commits: L0 `76e51a9`, S1 `6170123`, S2 `4d175d4`, S2b+S3 (this shift). New backend code:
> `com.gamma.exchange` (Exchange/Offer/ShareGrant/SharedRef/Ledger/ExchangeSnapshots/ExchangeSnapshotWriter),
> `com.gamma.control.{ExchangeRoutes,ExchangeRefResolver}`, `com.gamma.query.SharedRefResolver`,
> `com.gamma.service.SpaceLayoutContract`. See §5 for the per-phase status.
>
> **Original plan follows.** Two linked
> requirements: **(1)** a formal, system-organized layout for config and data files; **(2)** **Datasets
> shareable across Spaces on request (Share Grants)** — a deliberate, narrowly-scoped change to the
> tenancy model (glossary §1: today "Activity in one Space is invisible to another").
> **Review corrections folded in:** sharing is selective and grant-mediated, never wholesale;
> **Schema sharing was dropped** (a Dataset carries its own descriptive metadata — its **Result Set**,
> glossary §6-B — so a consumer never needs the producer's ETL-side Schema component); shareable kinds
> are **Dataset and Widget, both strictly read-only** (a granted Widget renders in the consumer's
> space but is never editable; its underlying Dataset grant travels with it — §3.5). Operating model: **departments / e-governance ministries** — each Space
> is a department with its own users, pipelines and Datasets; a Dataset can be *requested for shared
> use* by another department **without reprocessing or re-creating pipelines** on either side.
> Companion designs: [`../archived-documents/plans-archive/job-framework-design.md`](../archived-documents/plans-archive/job-framework-design.md) (the refresh Job,
> Run Artifacts), [`metadata-bundle.md`](../archived-documents/plans-archive/metadata-bundle.md) / [`transportability-plan.md`](../archived-documents/plans-archive/transportability-plan.md) (current: [`okf/backend/control-plane/metadata-bundle.md`](../okf/backend/control-plane/metadata-bundle.md))
> (Bundle v2 contentHash — reused here for drift detection).

## 1. Current state (as-built — the layout largely exists)

Multi-space shipped in 4.x. Everything an installation owns lives under `spaces/<id>/` with the
canonical subdirs `config/ data/ audit/ duckdb/ flows/` (`SpaceManager.SPACE_SUBDIRS`,
`SpaceManager.java:50`); `SpaceMigrator` already performs the organizing remap
(`database/→data/`, `jobs_audit/→audit/`, `inspecto-events/→data/events/`, `*.duckdb→duckdb/`) and is
idempotent. Component registry dirs live under each space's `config/`
(`ComponentRegistry.TYPE_BY_DIR`, `ComponentRegistry.java:44-57`):
`connections/ grammars/ schemas/ transforms/ sinks/ datasets/ widgets/ dashboards/ queries/
expectations/ requirements/ link-analysis-views/ geo-map-views/`.

Isolation facts that constrain this plan:

| Fact | Where |
|---|---|
| One `SpaceManager` hosts N `SpaceContext`s; each space has its own `SourceService`, stores, scheduler, event log, connection registry | `SpaceManager.java:44`, `SpaceContext.java:24` |
| Requests bind a space via the `/spaces/{id}` prefix → `MDC(space)` for the request's life; `SpaceId` is jailed to `[a-z0-9-]` | `ControlApi.java:178,430-442`, `SpaceId.java:11` |
| `ComponentStore`/`ComponentRegistry` are constructed on **one** space's `registryRoot` — no cross-space resolution exists anywhere | `ComponentRegistry.java:99` |
| `DatasetRelation.relationSql(cfg, dataRoot, views)` hard-wires the **calling** space's data root + `ViewStore` | `query/DatasetRelation.java:33` |
| The only cross-space transport is a **copy** (Bundle export/import, `SpaceManager.createFromBundle`) — with Bundle v2 `contentHash` drift classification | `BundleExporter.java:21-34`, glossary §1 |
| Path jail rejects any config path outside the space's allowed roots | `ConfigSafetyValidator.java:139-173` |
| Deletion fence is per-space (installed per `SourceService`) | `JobService.java:332` |
| `dirs.*` in pipeline configs resolve against the **JVM CWD**, not the space root (known trap) | `architecture.md` §Directory Layout ⚠ |

**Gap vs requirement (1):** the layout exists but is a *convention*, not a contract — nothing validates
a space tree, `flows/` carries a historical name, and Job Packs (job-framework §12) have no assigned
home yet. **Gap vs requirement (2):** no sharing concept exists at all — the Bundle copy is the only
precedent, and it duplicates rather than shares.

## 2. Requirement (1) — the layout contract

Formalize the existing tree as a **validated contract** (checked at space boot; violations are WARN
signals, not boot failures), extended with the two new roots this plan and the job framework need:

```
<install-root>/
  packs/                            host-wide Job Packs (job-framework §12; -Djobs.packs.dir default)
  spaces/                           -Dspaces.root
    _shared/                        THE EXCHANGE (§3) — reserved, not a Space
      offers.toon                   datasets each owner has listed as shareable
      grants.toon                   grant ledger: dataset, owner, consumer, mode, status, versions
      exchange/<owner>/<dataset>/   snapshot-mode data: <version>/ dirs + current.toon pointer
      duckdb/                       exchange-scope DuckDB state (catalog projections)
    <space-id>/
      space.toon                    space manifest
      config/                       ALL authored definitions (the write-root)
        <data_source>/              *_gen / *_schema / *_pipeline / *_job / *_alert .toon (suffix convention)
        {connections,grammars,schemas,transforms,sinks,datasets,widgets,dashboards,
         queries,expectations,requirements,link-analysis-views,geo-map-views}/   component registry
        share-grants/               this space's grants (owner-side originals; §3.2)
      flows/                        authored Pipeline graphs (historical name — see §6 note)
      views/                        ViewDefinitions (T32-C)
      data/                         data plane: inbox, Tables, backup, errors, quarantine,
        events/                     markers, status, temp, logs; signal/event ledger files
      audit/                        batch/file/job audit ledgers, branch-commit logs, run logs
      duckdb/                       per-space DuckDB state
```

Rules of the contract: **config vs data vs audit vs duckdb never mix** (the migrator's axes, now
enforced); every new subsystem must declare its home in this table before landing (the job framework's
run logs → `audit/`, artifacts DB → `duckdb/`, packs → install-root `packs/`); relative paths only
(the CWD trap stands until `dirs.*` resolution is made space-root-relative — a candidate fix listed in
§6 but **not** bundled into this plan).

## 3. Requirement (2) — Dataset sharing across Spaces (Share Grants)

### 3.1 Scope and model

**Datasets and Widgets, read-only.** Dashboards, Pipelines, Jobs, Schemas, Queries stay space-private —
integral parts of a department's workspace. Schemas in particular are *producer-side ETL artifacts*;
what a consumer needs travels **with the Dataset**: its Result Set (columns, types, analytic roles),
partitioning, freshness. A **Widget** is pure config (visualization spec + query binding) over a
Dataset, so it shares cleanly as a read-only rendering (§3.5). Sharing is **per-item, opt-in, and
grant-mediated** — nothing is shared by default, and nothing is ever *discoverable* across spaces
unless its owner offers it. **Read-only is absolute** in both kinds: a consumer can query/render,
never edit, rebind, or write back.

The workflow (the ministries model):

```
OWNER (finance)                          CONSUMER (audit-ministry)
1. marks Dataset "offered"  ──────────►  2. sees it in the Exchange catalog (metadata only:
   (offer = name, description,              Result Set, description, freshness — never rows)
    Result Set, refresh cadence)
                                         3. requests use (purpose note)
4. approves / denies  ◄──────────────────
   (capability-gated, audited)
5. grant ACTIVE ─────────────────────►   6. references shared/finance/tax_receipts in its own
   snapshot Job armed (if snapshot          Widgets, Queries, Alert Rules, Jobs — NO pipeline
   mode)                                    authored, NO data reprocessed, NO schema imported
```

Both sides keep their own user base: request/approve are actor-attributed and audited in *both*
spaces' audit logs, and the grant lifecycle emits `exchange.grant.*` signals on both ledgers.

### 3.2 The Share Grant object

A new component type `share-grant`, authored owner-side (`config/share-grants/`), projected into the
Exchange's `grants.toon` ledger for cross-space visibility:

```json
{
  "kind": "dataset",
  "item": "tax_receipts",
  "owner": "finance",
  "consumer": "audit-ministry",
  "mode": "snapshot",
  "status": "active",
  "requestedBy": "r.gupta@audit.gov", "requestedAt": 1783990000000, "purpose": "FY26 revenue audit",
  "approvedBy": "a.rao@finance.gov",  "approvedAt": 1783991000000,
  "pin": null,
  "expiresAt": null
}
```

- `status` lifecycle: `offered → requested → active | denied → revoked | expired`. Every transition is
  audited (existing `AuditTrail` seam) and signalled.
- `mode`: `snapshot` (default) or `live` (§3.4).
- `pin`: optional version pin (`"v12"`); null = track current.
- `kind`: `dataset | widget`. One grant per (kind, item, consumer) triple; the offer itself sits on
  the component (`offered: true` + offer metadata on the dataset/widget component).
- A **widget grant requires its dataset grant**: offering a Widget auto-offers (or requires an existing
  offer of) the Dataset its query binds — the Bundle v2 `requires`/refs-closure rule applied to grants.
  Approving the widget request approves the pair atomically; revoking the dataset grant suspends every
  widget grant that depends on it (fail-closed).

### 3.3 References and resolution

Consumer-side refs are **`shared/<owner>/<dataset>`** — provenance readable in the name, no
shadowing, and the ref stays a plain string everywhere refs already flow (Widget bindings, Alert Rule
`dataset:`, `$signal.dataset`, job `params`). Resolution is **grant-checked and fail-closed**: no
active grant for the calling space ⇒ the ref does not resolve (404/403), even if the files exist.

| Seam | Change |
|---|---|
| `ComponentRegistry` / pickers | `shared/…` refs resolve the *offer metadata* (Result Set, freshness) from the Exchange — read-only; never the owner's registry |
| `DatasetRelation.relationSql` | `shared/` refs route `dataRoot` per grant mode (§3.4); local refs unchanged |
| `ConfigSafetyValidator` | Exchange dir becomes a **read-scope** root; `live` grants add the granted dataset's directory (only) as a per-grant read root |
| Deletion fence | Consults active grants: owner deleting/compacting an offered+granted Dataset ⇒ `409` listing consumer spaces (fence lifted to installation scope for granted items only) |
| Catalog / `IdScheme` | Scoped ids (`shared/<owner>/<id>`) as distinct catalog nodes with `rel:'grants'` lineage edges — sizing spike in S1 (internals untraced) |

### 3.4 Delivery modes — how "no reprocessing" is honored

Neither mode requires the consumer to author a pipeline, copy configs, or re-ingest anything:

- **`snapshot` (default — the inter-ministry posture).** A **refresh Job** (job-framework `sql.template`
  or `core.publish`) in the *owner's* space writes versioned snapshots to
  `_shared/exchange/<owner>/<dataset>/<vN>/`, then flips the `current.toon` pointer — **atomic swap**,
  so consumers never read a half-written refresh (resolves the staleness question from the first
  draft). Cadence: cron or `on_signal: pipeline.commit` of the producing Pipeline. Each refresh is a
  Run with Run Artifacts (rows, watermark, `ResultSetMeta`) → consumers see freshness in the Exchange
  catalog and can gate their own jobs on `exchange.refreshed` signals. Strongest isolation: consumers
  never touch the owner's tree; revocation = pointer removal.
- **`live` (opt-in, high-trust pairs).** The consumer's `DatasetRelation` reads the owner's Table
  directory **read-only**, via the per-grant jail root. Zero duplication and always current, but reads
  contend with the owner's workload and revocation must invalidate in-flight query plans — hence
  opt-in per grant, never the default.

### 3.5 What the consumer does with it

**Shared Dataset** — bind directly: a local Widget over `shared/finance/tax_receipts`, an Alert Rule
(`dataset: shared/finance/tax_receipts`, `measure: sum(amount)`), a `sql.template` Job joining it with
local Datasets, a Decision Rule arm routing on its values. Local *derived* processing stays local — a
consumer may build its own Derived Tables/Views **on top of** a shared ref (that's their compute, their
space), but can never write back through it.

**Shared Widget** — render-only: it appears in the consumer's Widget library under
`shared/<owner>/<widget>` with a scope badge, can be **placed on the consumer's own Dashboards** and
viewed in the BI-6 embed viewer, but its config is immutable there — no rebinding, no channel edits,
no save-as (a copy would silently drift; if a department wants an editable variant, the owner exports
a Metadata Bundle — the copy path stays explicit). Rendering executes the widget's own Query against
its shared Dataset through the grant's delivery mode (§3.4), so freshness and revocation behave
identically for both kinds: revoke the grant and the widget tile degrades to an "access revoked"
empty-state, never stale cached data.

### 3.6 Surfaces

- **API** (installation-scope, unprefixed like `/spaces`; endpoint-skill gate order; all audited):
  `GET /exchange/offers` · `POST /exchange/offers` (owner lists/updates an offer) ·
  `POST /exchange/requests` (consumer) · `POST /exchange/grants/{id}/approve|deny|revoke` (owner) ·
  `GET /exchange/grants?space=` · `GET /exchange/datasets/{owner}/{id}` (metadata + freshness).
- **UI**: Catalog gains **"Shared with me"** (consumer view: granted datasets, freshness, scope badge
  in every picker) and **"Shared by me"** (owner view: offers, pending requests, consumers per
  dataset, revoke). Dataset editor gains "Offer for sharing…".
- **Capabilities**: `canOfferDatasets` / `canApproveShares` (owner side), `canRequestShares`
  (consumer side) — capability pattern like `canAuthorWorkbench`; enforcement stays at the IAM edge,
  core remains auth-free.

## 4. What this deliberately does NOT do

- No Schema sharing (dropped — the Result Set travels with the Dataset offer).
- No sharing of Dashboards, Pipelines, Jobs, Queries, Expectations — integral to their Space;
  cross-instance/staging transport keeps using Metadata Bundles (copy semantics). Widgets share
  **render-only** (§3.5) — no shared-editing mode exists for anything.
- No wholesale/public scope — nothing is visible without an owner's offer, nothing usable without a
  grant.
- No cross-space **writes**, ever, in either mode.
- No per-row/column masking policies in v1 (an owner shares the whole Dataset or offers a *derived*
  Dataset that pre-filters/pre-masks — the recommended pattern for partial sharing; row/column-level
  policy is listed in §6 as future work).

## 5. Phasing

| Phase | Scope | Status |
|---|---|---|
| **L0 — layout contract** | Boot-time tree validation (WARN signals), `views/` + `packs/` + `share-grants/` homes documented, contract table in `architecture.md` | ✅ **SHIPPED** — `SpaceLayoutContract.verify` in `SpaceBootstrap.load`; `LAYOUT_CONTRACT_VIOLATION` WARN events; `SpaceLayoutContractTest` |
| **S1 — Exchange + grant workflow (no data path)** | `_shared` reservation, offer/request/approve/revoke lifecycle + ledger, `shared/<owner>/<id>` ref parsing (resolving to metadata only), capabilities | ✅ **SHIPPED (backend)** — `Exchange` (offers/grants ledgers), `ExchangeRoutes`, caps `canOfferDatasets`/`canRequestShares`/`canApproveShares`. **IdScheme spike finding:** the catalog (`com.gamma.catalog`) models pipeline/schema lineage, *not* Studio datasets — no re-keying needed; shared refs flow as plain `physicalRef` strings (`ComponentStore.SAFE_ID` rejects `/`, so a shared ref is never a component id) |
| **S2 — snapshot mode (Datasets)** | Versioned exchange dirs + atomic `current.toon` pointer flip, `DatasetRelation` snapshot routing, freshness metadata + `EXCHANGE_REFRESHED` signal, consumer deletion fence | ✅ **SHIPPED** — `ExchangeSnapshotWriter`/`ExchangeSnapshots` + `POST /exchange/refresh` (owner-triggered; the cron/`on_signal` *cadence* awaits job-framework P1, but the data path is complete), `SharedRefResolver` grant-checked routing, `ComponentRoutes` fence |
| **S2b — shared Widgets** | `kind: widget` grants with dataset-grant closure, render-only resolution, revoked empty-state | ✅ **SHIPPED (backend)** — widget offer requires its dataset offer; request auto-pairs the dataset grant; approve activates the pair; revoke cascades; `GET /exchange/widgets/{owner}/{item}` render-authorization. **UI** (library/dashboard placement, empty-state) is in the UI track |
| **S3 — live mode + governance** | Live `DatasetRelation` routing, revocation/expiry enforcement, version pinning | ✅ **SHIPPED (backend)** — `ExchangeRefResolver` live vs snapshot vs pinned branching; `activeGrant` enforces expiry; `POST /exchange/grants/{id}/{pin,expiry}`. **Per-grant `ConfigSafetyValidator` jail roots proved unnecessary:** shared refs resolve to server-computed *trusted* paths, never config-authored ones. Drift/pinning **UI** is in the UI track |
| **UI — Exchange surfaces (§3.6)** | Catalog "Shared with me"/"Shared by me", "Offer for sharing…", scope badges, revoked empty-state, pin/drift UI | ⬜ **OPEN** — the remaining track; backend endpoints + `bootstrap.features.exchange` flag are all in place |

## 6. Risks & open questions

1. **`IdScheme` / lineage / provenance** under a scoped id (`shared/<owner>/<id>`) — untraced
   internals; S1 starts with that spike (worst case: catalog nodes need a `scope` column and
   re-keying).
2. **`flows/` rename** (→ `pipelines/`) — belongs to the glossary §13 Flow→Pipeline final step
   ("largest blast radius, save for last"); explicitly **out of scope** here, listed so nobody bundles
   it in.
3. **CWD-relative `dirs.*`** — making them space-root-relative would eliminate the layout's one trap;
   separate change, larger blast radius (every existing config), candidate for the same release as L0.
4. **Row/column-level sharing policy** (mask columns, filter rows per consumer) — deferred; v1's
   answer is "offer a derived Dataset". Revisit if ministries need per-consumer projections of one
   physical Dataset.
5. **Cross-installation sharing** (two separate Inspecto deployments) — out of scope; that remains the
   Metadata Bundle + data-transport problem, not a grant.
6. **Single-tenant mode** (no `-Dspaces.root`): the Exchange is disabled — fail-closed, matches the
   packs-dir pattern; a lone space has no one to share with.
7. **Glossary registration** (implementation time): **Exchange** (the shared scope), **Share Grant**,
   **Offer**; amend the §1 Space definition with the grant exception clause. ⛔ avoid "Commons"
   (dropped with the first draft) and bare "share" as a noun.
