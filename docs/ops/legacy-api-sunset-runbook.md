# Legacy API sunset runbook (API-5)

**Status:** mechanism SHIPPED 2026-07-08 (`docs/superpower/api-contract-design.md` §10 W8); soak
criterion **SIGNED 2026-07-08** (product decision, recorded here). **Owner:** whoever operates the
first live Standard/Enterprise deployment. This is the last artifact API-5 needed — from here the
retirement of the unversioned pre-`/api/v1` route aliases is a per-deployment ops procedure, not code.

## The signed criterion

> **A deployment may set `-Dapi.legacy.routes=off` once `inspecto_legacy_api_requests_total` has
> scraped at zero for 30 consecutive days on that deployment.**

30 days, not 14 — conservative by design: this metric has never been watched against real traffic,
and a monthly-batch caller (a quarter-end export script, an annual reconciliation job) must get at
least one full cycle to prove itself before the door closes on it.

## Step-by-step

1. **Confirm every caller has migrated to `/api/v1`.** Every legacy (non-`/api/v1`) response already
   carries `Deprecation: @1783382400` + `Link: </api/v1>; rel="successor-version"` — if a caller reads
   response headers it self-identifies as needing to move.
2. **Watch the signal.** Prometheus query against the deployment's `/metrics` scrape:
   ```promql
   sum(increase(inspecto_legacy_api_requests_total[30d])) == 0
   ```
   The metric is labelled per route (`route="..."`) — a non-zero result names exactly which route
   alias still has a caller, so migration work can target it instead of guessing.
3. **Hold for 30 consecutive days at zero** (not "cumulatively low" — a single stray call resets the
   clock, by design: the point is proving *nothing* depends on the alias anymore).
4. **Flip the deployment**: set `-Dapi.legacy.routes=off` and restart. Verify:
   ```bash
   curl -i http://<host>/sources        # expect 410, body names /api/v1
   curl -i http://<host>/api/v1/sources # expect 200, unaffected
   curl -i http://<host>/health         # expect 200, unaffected (infra probe, always exempt)
   ```
   The metric keeps counting after the flip — a 410'd call is still recorded, so a caller who missed
   the deprecation window stays visible instead of silently failing.
5. **One release after every deployment runs clean with `off`**, the unversioned route aliases can be
   deleted from the route table entirely (an engineering PR at that point — trivial, since nothing
   live depends on them by construction of step 3).

## Per-deployment, not global

Each deployment flips independently — a slow-migrating customer's `/api` alias staying open does not
block a fast-migrating one from closing theirs. `-Dapi.legacy.routes` is a plain JVM property, not a
shared flag.
