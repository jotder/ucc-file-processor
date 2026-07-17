import { type Signal, NOTIFY_TYPES, notifyMeta } from '../signal/signal';
import { MockStore } from './mock-store';
import { fanOut } from './notify';

/**
 * R4 — the Signal network's single ledger + emission seam. Every operational fact (runs/jobs from the
 * simulator, fired alerts, failed expectations, operator object transitions, and R5 decision
 * consequences) is written here via {@link emitSignal}, replacing the old parallel `event` /
 * `fired-alert` stores. The `/events` and `/alerts` read surfaces are projections over this one
 * collection (see `inspecto/signal/signal.ts`). Framework-free — unit-tests in plain vitest.
 */

export const SIGNALS_COLL = 'signal';

/** Cap the stored ledger so the localStorage snapshot doesn't grow unbounded (was MAX_EVENTS + MAX_ALERTS). */
const MAX_SIGNALS = 250;

/**
 * Emit one signal: append to the ledger, trim the oldest over the cap, and fan out a notification for
 * notify-worthy types (`ALERT_FIRED` / `INCIDENT_OPENED`) — so the notification center is a *consumer*
 * of signals, not a parallel store.
 */
export function emitSignal(store: MockStore, space: string, signal: Signal): Signal {
    store.put(space, SIGNALS_COLL, signal.signalId, signal);
    trimOldest(store, space, MAX_SIGNALS);
    if (NOTIFY_TYPES.has(signal.type)) {
        const meta = notifyMeta(signal);
        if (meta) fanOut(store, space, signal.type, meta.category, meta.title, meta.body, meta.sourceId);
    }
    return signal;
}

let auditSeq = 0;

/**
 * Append one AUDIT signal for a mock mutation — so the Audit-log pane grows with what the operator
 * actually does offline instead of staying a canned seed. Shape mirrors the seeded audit entries
 * (attributes: actor/action/action_category/target_type/target_id); the actor is the mock's local
 * `'operator'` fallback (a MockRequest carries no X-Actor header).
 */
export function emitAudit(
    store: MockStore,
    space: string,
    opts: { action: string; category: 'config' | 'operate' | 'read' | 'destructive'; targetType: string; targetId: string; message: string },
): Signal {
    const at = Date.now();
    return emitSignal(store, space, {
        signalId: `audit-${at}-${auditSeq++}`,
        type: 'AUDIT',
        at,
        source: { kind: 'audit', id: 'audit', rel: 'emits' },
        correlationId: null,
        severity: 'info',
        payload: {
            message: opts.message,
            attributes: {
                actor: 'operator',
                action: opts.action,
                action_category: opts.category,
                target_type: opts.targetType,
                target_id: opts.targetId,
            },
        },
    });
}

function trimOldest(store: MockStore, space: string, max: number): void {
    const rows = store.entries<Signal>(space, SIGNALS_COLL);
    if (rows.length <= max) return;
    const oldest = rows.sort(([, a], [, b]) => a.at - b.at).slice(0, rows.length - max);
    for (const [id] of oldest) store.delete(space, SIGNALS_COLL, id);
}
