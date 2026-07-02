import type { AlertRule, FiredAlert } from '../../api/alerts.service';
import type { EventRow } from '../../api/events.service';
import type { AuditRow, EnrichmentJobView } from '../../api/models';
import type { OperationalObject } from '../../api/objects.service';
import { MockFlags } from '../mock-flags';
import { json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';

/**
 * The operational-intelligence mock domain (events · alerts · objects · enrichment) — the port of
 * the old `ops-mock` interceptor onto the persistent {@link MockStore}, so the reusable query panel
 * runs over real-shaped, reload-surviving rows with no backend. Events and fired alerts are also
 * appended to by the liveness simulator (`../simulator.ts`), so the Ops screens visibly move.
 * User-triggered mutations the old mock let pass (transition/comment) still fall through.
 */

export const EVENTS_COLL = 'event';
export const EVENT_VIEWS_COLL = 'event-view';
export const ALERT_RULES_COLL = 'alert-rule';
export const FIRED_ALERTS_COLL = 'fired-alert';
export const OPS_OBJECTS_COLL = 'ops-object';
export const ENRICHMENT_COLL = 'enrichment-job';

const EVENTS_SEARCH = /\/events\/search$/;
const EVENTS_VIEWS = /\/events\/views$/;
const ALERTS_RULES = /\/alerts\/rules$/;
const ALERTS_EVAL = /\/alerts\/evaluate$/;
const ALERTS = /\/alerts$/;
const OBJECTS = /\/objects$/;
const ENRICH_RUNS = /\/enrichment\/([^/]+)\/runs$/;
const ENRICH_LINEAGE = /\/enrichment\/([^/]+)\/lineage$/;
const ENRICH_LIST = /\/enrichment$/;

export function opsHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockOps) return undefined;
        const { method, url, space } = req;
        let m: string[] | null;

        if (method === 'GET' && EVENTS_SEARCH.test(url)) {
            return json(store.list<EventRow>(space, EVENTS_COLL).sort((a, b) => b.ts - a.ts));
        }
        if (method === 'GET' && EVENTS_VIEWS.test(url)) return json(store.list(space, EVENT_VIEWS_COLL));
        if (method === 'POST' && EVENTS_VIEWS.test(url)) {
            const body = (req.body as { name?: string; filters?: unknown }) ?? {};
            const view = { name: body.name ?? 'view', filters: body.filters ?? {}, createdAt: Date.now() };
            return json(store.put(space, EVENT_VIEWS_COLL, view.name, view));
        }
        if (method === 'GET' && ALERTS_RULES.test(url)) return json(store.list<AlertRule>(space, ALERT_RULES_COLL));
        if (method === 'POST' && ALERTS_EVAL.test(url)) return json([]);
        if (method === 'GET' && ALERTS.test(url)) {
            return json(store.list<FiredAlert>(space, FIRED_ALERTS_COLL).sort((a, b) => b.epochMillis - a.epochMillis));
        }
        if (method === 'GET' && OBJECTS.test(url)) {
            const objects = store.list<OperationalObject>(space, OPS_OBJECTS_COLL);
            const type = req.params['type'];
            return json(type ? objects.filter((o) => o.objectType === type.toUpperCase()) : objects);
        }
        if (method === 'GET' && ((m = match(url, ENRICH_RUNS)) || (m = match(url, ENRICH_LINEAGE)))) {
            return json(enrichRuns(m[1]));
        }
        if (method === 'GET' && ENRICH_LIST.test(url)) return json(store.list<EnrichmentJobView>(space, ENRICHMENT_COLL));

        return undefined;
    };
}

/** Deterministic per-job run history — generated, not stored (read-only reporting rows). */
function enrichRuns(job: string): AuditRow[] {
    const now = Date.now();
    return Array.from({ length: 20 }, (_, i) => ({
        run_id: job + '-r' + (1000 + i),
        status: i % 7 === 0 ? 'FAILED' : 'SUCCESS',
        output_rows: String((i * 311) % 9000),
        duration_ms: String(800 + ((i * 53) % 5000)),
        run_ts: new Date(now - i * 86_400_000).toISOString(),
    }));
}
