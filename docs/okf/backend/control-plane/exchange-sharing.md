---
type: Concept
title: Exchange — Cross-Space Sharing
description: Grant-mediated, read-only Dataset/Widget sharing across Spaces — offer/request/approve ledger, snapshot/live delivery, version pin + drift, the sharing.component UI.
resource: inspecto/src/main/java/com/gamma/control/ExchangeRoutes.java
tags: [control-plane, multi-space, exchange, sharing]
timestamp: 2026-07-19T00:00:00Z
---

# Exchange — Cross-Space Sharing

Datasets and Widgets can be shared **read-only, per-item, opt-in** across [Spaces](multi-space.md) via
a grant-mediated ledger — the "ministries" model (a department's Dataset used by another department's
Widgets/Queries/Alert Rules, without reprocessing or re-creating pipelines). Nothing is discoverable
across Spaces unless its owner offers it; nothing is usable without an active grant (fail-closed).

## Backend — `com.gamma.exchange` + `ExchangeRoutes`

Installation-scope, un-prefixed routes (like `/spaces`) — they address `spaces/_shared/`, not one
Space's engine, so they fall through `ControlApi.dispatch`'s `/spaces/{id}` seam untouched. Every route
409s outside a multi-space runtime (`Exchange.under(containerRoot)`), and 409s are open only to reads
until a capability check (writes gated on `canOfferDatasets`/`canRequestShares`/`canApproveShares`; a
no-op on Personal, enforced on Standard).

```
GET  /exchange/offers[?owner=]                 the shareable catalog (metadata only, never rows)
POST /exchange/offers                          owner lists/updates an offer
POST /exchange/refresh                         owner republishes a dataset's snapshot
POST /exchange/requests                        consumer requests use
POST /exchange/grants/{id}/{approve|deny|revoke}  owner acts on a grant
POST /exchange/grants/{id}/pin                 consumer pins/clears a snapshot version
POST /exchange/grants/{id}/expiry               owner sets/clears a grant's expiry
GET  /exchange/grants[?space=]                 the grant ledger
GET  /exchange/datasets/{owner}/{item}[?consumer=]  one item's metadata (+ grant status)
GET  /exchange/widgets/{owner}/{item}?consumer=     render-only view of a shared Widget
```

**`ShareGrant`** lifecycle: `requested → active | denied`; `active` → `revoked`/`expired`. One grant per
`(kind, item, owner, consumer)` quad, id `consumer~owner~kind~item`. Fields include `mode`
(`snapshot`|`live`), `pin` (a `"v<n>"` version string; null = track current), `expiresAt` (epoch millis;
null = no expiry). A **Widget grant requires its Dataset grant** — offering/requesting a widget
auto-pairs its bound dataset's offer/grant; approving the widget activates both; revoking the dataset
grant cascades revocation to every widget grant that depends on it.

**Snapshot delivery (S2, the default).** `ExchangeSnapshotWriter.publish` resolves the owner's own
`DatasetRelation` (never a shared ref), `COPY`s it to
`spaces/_shared/exchange/<owner>/<item>/v<epochMillis>/snapshot.parquet`, then atomically flips a
sibling `current.toon` pointer (`ExchangeSnapshots`) — a reader never observes a half-written version.
Freshness (`version`, `rows`, `refreshedAt`, Result Set columns) travels in `current.toon`, merged into
every offer/metadata response as `freshness`. Versions are **monotonic**: real backend mints
`v<System.currentTimeMillis()>`; the UI mock mints `v1/v2/v3` — both match `/^v(\d+)$/`, comparable by
the trailing integer.

**Live delivery (S3, opt-in).** `ExchangeRefResolver` routes a `shared/<owner>/<item>` ref straight to
the owner's Table directory, read-only, through a per-grant jail root — no snapshot, always current, but
contends with the owner's workload; revocation must invalidate in-flight query plans. Consumer refs are
plain strings (`shared/<owner>/<item>`) that flow through Widget bindings, Alert Rule `dataset:`,
`$signal.dataset`, job `params` exactly like a local ref — resolution is grant-checked and fail-closed
(no active grant ⇒ ref does not resolve, even if the files exist).

## UI — Catalog `sharing.component` (§3.6, fully shipped 2026-07-19)

`inspecto-ui/src/app/modules/admin/catalog/sharing.component.ts` is one pane, two views selected by the
Catalog tab that hosts it (`shared-with-me`/`shared-by-me`, gated on `SessionService.exchangeEnabled()`
← `bootstrap.features.exchange`):

- **with-me** (consumer): grants where the active Space consumes + the requestable catalog of other
  Spaces' offers. Actions: request access (`RequestShareDialog`), pin/clear a version
  (`GrantGovernanceDialog`).
- **by-me** (owner): inbound requests + grants on the active Space's offers (approve/deny/revoke) + its
  listed offers. Actions: approve/deny/revoke, refresh a dataset's snapshot, set/clear expiry.
- **Offer flow**: `datasets.component`/`widgets.component` gain an "Offer for sharing" action
  (`OfferShareDialog` → `ExchangeService.offer()`), gated on the same `exchangeEnabled` signal.
- **Scope badges**: any dataset/widget bound to a `shared/<owner>/<item>` ref renders
  `<inspecto-status-badge value="shared" [label]="'Shared · '+owner">` in Studio's dataset/widget lists.

**Pin-drift indicator (the last §3.6 piece, 2026-07-19).** The Pinned column renders a warning-tone
"Behind" chip when an active grant's pin trails the offer's current `freshness.version` — client-computed,
no backend change, since both numbers are already loaded for the grid:

```
driftVersion(grant, currentVersion) =
  grant.status === 'active' && grant.pin && currentVersion && currentVersion !== grant.pin
    && versionSeq(currentVersion) > versionSeq(grant.pin)
  ? currentVersion : null
```

`versionSeq` parses the trailing `/^v(\d+)$/` integer; an unparseable version (either side) never
triggers a false "Behind". `myGrantRows` (a `computed`) joins each view's grants against a
`owner~kind~item → freshness.version` map built from the loaded offers. `statusBadgeHtml` gained an
optional `label` param (`statusBadgeHtml('warning', 'Behind')`) so the string cellRenderer can show
custom text under a chosen tone, mirroring the `<inspecto-status-badge>` component's `value`+`label`
split — no new color owner, still passes `lint:tokens`.

**Mock parity**: `exchange.handler.ts` mirrors the backend lifecycle in the unified mock store
(`_server` pseudo-space ledgers), gated on `mockExchange`; its seed pins the demo "Shared with me" grant
at `v2` against a `v3` snapshot so drift is visible with no backend.

## What is deliberately out of scope

No Schema sharing (a Dataset's own Result Set is self-describing). No sharing of Dashboards, Pipelines,
Jobs, Queries, Expectations — cross-instance/staging transport for those stays [Metadata Bundles](metadata-bundle.md)
(copy semantics). No cross-space writes, ever. No per-row/column masking in v1 (an owner shares the
whole Dataset or offers a pre-filtered derived one). No wholesale/public scope.

## Design-of-record

`docs/archived-documents/plans-archive/storage-layout-and-sharing-plan.md` — the full phasing (L0–S3 +
UI track), now entirely shipped; kept for provenance/rationale, not maintained.
