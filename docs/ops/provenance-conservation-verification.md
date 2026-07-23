# Provenance conservation — live-data verification protocol (OPS-5)

**Status:** feature built + tested + off by default; verified so far only against synthetic data.
**What's missing is not code** — it's running this protocol once against a real feed. **Owner:** ops,
first live deployment. Cannot be closed offline/in a sandbox; there is no substitute for real traffic
here.

## What's already shipped

- Per-edge provenance recording: `DbProvenanceStore` (`-Dprovenance.backend=duckdb`; default-off — a
  pipeline with the flag unset records nothing, zero overhead).
- The conservation invariant (`ConservationCheck`): at each non-amplifying node, records that entered
  must equal records that left. A violation emits `EventType.FLOW_CONSERVATION_IMBALANCE` with
  `node`/`recordsIn`/`recordsOut`/`kind` (`LOSS` or `AMPLIFICATION`) attributes, correlated to the
  run's `batchId`.
- Downstream consequences already wired: the event is promoted to a managed **ALERT object** by
  `EventObjectBridge` (de-duplicated per `(pipeline, node)`), so an imbalance surfaces in the
  Alerts/Incidents view; and the run's per-node counts drive the Lineage/Sankey overlay served by
  `JobRoutes` — `GET /provenance` and `GET /provenance/batches` (not `LineageRoutes`, which is the
  separate file→store→flow ingest-lineage endpoint).
  - A conservation imbalance now also raises an operator **notification** (product Q resolved
    2026-07-23): `NotificationRules.defaults()` carries a rule for `FLOW_CONSERVATION_IMBALANCE`
    (category `ops`, `minLevel=WARN` so both `LOSS`/ERROR and `AMPLIFICATION`/WARN notify — matching
    that the ALERT bridge fires for both kinds), so an imbalance reaches the in-app feed and any
    enabled channel (email, etc.). The rules stay code-only in `NotificationRules.defaults()` (no
    authorable `notifications` TOON section yet); the `OBJECT_OPENED` the ALERT itself emits still has
    no rule. During the soak you can watch the **notification feed as well as** the ALERT object list
    and the event ledger — but step 3 still verifies `recordsIn`/`recordsOut` against ground truth,
    not just "an alert/notification fired".

## The protocol

1. **Enable on one real pipeline**, not a synthetic fixture: `-Dprovenance.backend=duckdb` on a
   deployment ingesting live production data.
2. **Soak.** Run through enough natural variation to matter: multiple batch sizes, at least one
   partial/failed batch (retry path), at least one enrichment or join stage if the pipeline has one
   (the highest-risk node for a silent record-count mismatch).
3. **Compare invariants against ground truth**, not just "no alert fired":
   - Pick a handful of batches and manually verify `recordsIn`/`recordsOut` at each node against the
     actual file/table row counts (e.g. `wc -l` on the source, `SELECT count(*)` on the output).
   - Deliberately construct one batch that *should* amplify (a join with multiple matches) and one
     that *should* lose rows (a legitimate filter stage) — confirm `ConservationCheck` correctly
     does NOT flag these as violations (it must distinguish declared amplification/filtering from an
     unexplained imbalance) and DOES flag a real mismatch if you inject one (e.g. truncate an output
     file mid-run).
4. **Check performance overhead** — provenance recording is default-off for a reason; confirm the
   `-Dprovenance.backend=duckdb` overhead is acceptable at the deployment's real batch volume before
   recommending it as a standing default anywhere.
5. **Record the outcome here** (append a dated entry below) — pass/fail per check, any tuning needed
   (e.g. a node type that needs an explicit amplification declaration the engine didn't infer).

## Outcome log

_(empty — awaiting the first live deployment to run this protocol)_
