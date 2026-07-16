# R4 — The Signal Network (one signal envelope, full store unification)

> Approved 2026-07-06 (product owner chose **full unification**, not additive-converge). Slice R4 of the
> Living Operational System roadmap (`docs/superpower/living-operational-system.md` §2/§5). R1 gave the one
> ref-derivation; R2 the `job` kind; R3 the `query` kind + `$`-parameters + Result Set. R4 gives the organism
> its **nervous system**. UI-only / mock-first / independently shippable — same rhythm as R1–R3.

## Thesis

Today the Signal network is **three parallel stores with three shapes**, emitted from scattered sites:

| Store (`MockStore` collection) | Shape | Written by |
|---|---|---|
| `event` | `EventRow {eventId,ts,timestamp,level,type,source,pipeline,correlationId,message,attributes}` | `simulator.appendEvent`, 5 seeds, (read by audit-logs / object-detail / dashboard too) |
| `fired-alert` | `FiredAlert {rule,severity,pipeline,metric,value,comparator,threshold,window,epochMillis,message}` | `simulator.fireAlert`, `/alerts/evaluate`, 2 seeds |
| `notification` (+ `-channel` / `-delivery`) | `NotificationRow` + channels + deliveries | `notify.fanOut` (already a *consumer* pattern) |

R4 collapses this to **one canonical envelope + one emission seam + one ledger store**:

```ts
interface Signal {
  signalId: string;
  type: string;                 // BATCH_COMMITTED | ALERT_FIRED | JOB_SUCCEEDED | EXPECTATION_FAILED | OBJECT_ACTIVITY | AUDIT | …
  at: number;                   // epoch millis
  source: Ref;                  // {kind,id,rel:'emits',via?} — the emitting component/producer (joins the R1 metadata network)
  correlationId?: string | null;
  severity: SignalSeverity;     // 'trace'|'debug'|'info'|'warn'|'error'|'critical'
  payload: Record<string, unknown>; // message, pipeline, metric, value, threshold, attributes, …
}
```

## What "full unification" means here (the deliberate interpretation)

The `event` collection is a **shared substrate** (audit rows = `source:'audit'` events; object-activity = `OBJECT_*`
events; the dashboard reads events). So full unification = **`signal` is the single source of truth; the `event`
and `fired-alert` collections are removed**; the existing read endpoints become **thin projections** over the one
ledger — no parallel stores, one place things are written. Concretely:

- `SIGNALS_COLL = 'signal'` is the only ledger. `EVENTS_COLL` / `FIRED_ALERTS_COLL` are **deleted** as write targets.
- **`emitSignal(store, space, signal)`** is the ONE writer. It appends to `SIGNALS_COLL`, trims to a cap, and
  fans out a notification for notify-worthy types (`ALERT_FIRED`, `INCIDENT_OPENED`) — so notifications are
  provably *consumers of signals*, not a parallel store.
- `/events/search`, `/events/export`, `/events/views` keep working by **projecting** `Signal → EventRow`
  (`signalToEvent`) — audit-logs / object-detail / dashboard are unchanged (they query the one store through the
  projection).
- `/alerts` keeps working by **projecting** the alert-typed signals `Signal → FiredAlert` (`signalToAlert`) — the
  Alerts page is now a filtered view over the ledger.
- The **Events page becomes the Signal Ledger**: enriched to render each signal's `source` (as a component **Ref
  chip**, linking to the emitting artifact) and `severity` (status-badge). Route stays `/events`; heading →
  "Signal Ledger". Alert-rule *definitions* (`alert-rule` collection, config) are untouched — they are promoted to
  the Decision network in **R5**, not here (R4 collapses the *occurrence/fired* layer only).

This is a real single-source-of-truth (distinct from additive-converge, which would keep the old stores and
dual-write). The projecting endpoints ARE the query layer — "Events/Alerts query the one store directly."

## Producers (proves "emitted by runs/jobs/rules/user actions")

| Producer category | Site | Signal |
|---|---|---|
| runs / jobs | `simulator.appendEvent` | `BATCH_*` / `JOB_*` / `FILE_*` (severity from level, `source` = Ref to the pipeline) |
| rules | `simulator.fireAlert` + `/alerts/evaluate` | `ALERT_FIRED` (severity from alert severity, `source` = Ref to the `alert-rule`) → fans out |
| rules | `expectations.handler` FAILED eval | `EXPECTATION_FAILED` (`source` = Ref to the expectation) — alongside the existing incident |
| user actions | `ops.handler` POST `/objects/{id}/transition` | `OBJECT_ACTIVITY` (operator moved an incident/case) |
| user actions | `ops.handler` POST `/objects` (incident) | `INCIDENT_OPENED` → fans out |
| decisions (R5) | decision-rule `emit-signal` consequence | any — the R4↔R5 link |

## Files

**New (pure + mock core):**
- `inspecto/signal/signal.ts` — `Signal`, `SignalSeverity`, `SIGNAL_SEVERITIES`, `severityToLevel`/`levelToSeverity`,
  `signalToEvent`, `signalToAlert`, `isAlertSignal`, `eventToSignal` (seed adapter), `alertToSignal` (seed adapter),
  `NOTIFY_TYPES`, `notifyMeta(signal)`.
- `inspecto/signal/index.ts` — barrel.
- `inspecto/signal/signal.spec.ts` — projection round-trips + severity mapping + isAlertSignal + notify metadata.
- `inspecto/mock/signals.ts` — `SIGNALS_COLL`, `emitSignal()`, `MAX_SIGNALS` trim; imports `fanOut` from `notify.ts`.

**Edited:**
- `component-model/component-types.ts` — add `'emits'` to `RefRel`.
- `inspecto/mock/handlers/ops.handler.ts` — `/events*` + `/alerts` read `SIGNALS_COLL` and project; `/alerts/evaluate`
  + incident-open + object-transition emit via `emitSignal`; remove `EVENTS_COLL`/`FIRED_ALERTS_COLL` writes (keep
  `ALERT_RULES_COLL`, `EVENT_VIEWS_COLL`, object/enrichment collections).
- `inspecto/mock/simulator.ts` — `appendEvent`/`fireAlert` build a `Signal` and call `emitSignal` (drop the direct
  `EVENTS_COLL`/`FIRED_ALERTS_COLL` puts + the bespoke `fanOut` in `fireAlert`).
- `inspecto/mock/handlers/expectations.handler.ts` — emit `EXPECTATION_FAILED` on a FAILED evaluation.
- `inspecto/api/events.service.ts` — `EventRow` gains optional `severity?: SignalSeverity` + `sourceRef?: Ref`
  (display-only projection fields; documented as the signal projection, `level` stays for the min-level filter).
- `modules/admin/events/events.component.ts` (+ `.html`, `.spec.ts`) — heading "Signal Ledger"; Source Ref chip +
  severity badge columns; a11y assertion kept.
- 5 seeds (`operations`, `telecom-ra`, `fraud-mgmt`, `financial-audit`, `link-analysis`) — event/fired-alert seed
  literals routed through `eventToSignal`/`alertToSignal` → `SIGNALS_COLL`.
- `inspecto/mock/mock-store.ts` — `MOCK_STORE_KEY` v11 → **v12**.
- `inspecto/mock/simulator.spec.ts` — assert against `SIGNALS_COLL`.
- `docs/GLOSSARY.md` — adopt **Signal** (§6 proposed → binding); note the ledger, the `source` Ref, and that
  Event/Alert/Notification are **views** over the signal ledger.
- `docs/superpower/living-operational-system.md` — flip R4 → SHIPPED.

## Scope cuts (R2 "no abstraction without a second consumer")

- `SignalSeverity` ladder replaces the two ad-hoc severity vocabularies (`EVENT_LEVELS` TRACE..ERROR + `FiredAlert`
  INFO/WARNING/CRITICAL); `level` on the projection is the min-level filter's backward-compatible view.
- Alert-rule *definitions* stay in `alert-rule` (config → R5). Notification channels/deliveries/read-state stay
  (the delivery + per-user-state layer = a legitimate consumer, not a parallel signal store).
- No new `/signals` endpoint or `SignalsService` — the Events page renders signal fields through the projecting
  `/events` endpoint (avoids re-plumbing saved-views/CSV/filters). A raw `/signals` query is a follow-on if needed.
- The backend `com.gamma.event.Event` mirror is unchanged (UI/mock-only slice); the backend Signal contract is a
  later concern (noted in the north-star doc).

## Verification (Definition of Done)

1. `npm run lint:tokens`  2. `npm run build`  3. `npm run test:ci` (baseline 963; expect ~+15).
4. **Preview:** Events page shows the signal ledger (source Ref chips + severity); Alerts page still lists fired
   alerts; the bell still fans out on a fired alert; audit-logs + object-detail + dashboard still render; the
   simulator advances the ledger on tick.
5. Commit `feat(ui): R4 …` (master-only per release-workflow); push only on explicit ask.
