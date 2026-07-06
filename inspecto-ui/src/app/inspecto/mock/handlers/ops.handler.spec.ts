import { describe, expect, it } from 'vitest';
import type { FiredAlert } from '../../api/alerts.service';
import type { EventRow } from '../../api/events.service';
import type { OperationalObject } from '../../api/objects.service';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { NOTIFICATION_CHANNELS_COLL, NOTIFICATION_DELIVERIES_COLL } from '../notify';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { opsHandler } from './ops.handler';

const req = (method: string, url: string, body: unknown = null, params: Record<string, string> = {}): MockRequest => ({
    method,
    url,
    body,
    params,
    space: 'default',
});

function seededStore(): MockStore {
    const store = new MockStore();
    store.ensureSeeded('default', seedDefaultSpace);
    return store;
}

describe('opsHandler', () => {
    const handler = opsHandler({ mockOps: true });

    it('serves seeded events newest-first and alerts newest-first', () => {
        const store = seededStore();
        const events = handler(req('GET', '/api/events/search'), store)?.body as EventRow[];
        // R4: /events projects the ONE signal ledger — 30 operational + 10 audit + 12 fired-alert signals.
        expect(events.length).toBe(52);
        expect(events[0].ts).toBeGreaterThanOrEqual(events[1].ts);

        const alerts = handler(req('GET', '/api/alerts'), store)?.body as FiredAlert[];
        expect(alerts.length).toBe(12);
        expect(alerts[0].epochMillis).toBeGreaterThanOrEqual(alerts[1].epochMillis);
    });

    it('round-trips a saved event view through the store', () => {
        const store = seededStore();
        expect(handler(req('GET', '/api/events/views'), store)?.body).toEqual([]);
        handler(req('POST', '/api/events/views', { name: 'errors_only', filters: { level: 'ERROR' } }), store);
        const views = handler(req('GET', '/api/events/views'), store)?.body as Array<{ name: string }>;
        expect(views.map((v) => v.name)).toEqual(['errors_only']);
    });

    it('accepts the real flattened saved-view body and deletes by name', () => {
        const store = seededStore();
        handler(req('POST', '/api/events/views', { name: 'warn_batches', level: 'WARN', type: 'BATCH_FAILED' }), store);
        const views = handler(req('GET', '/api/events/views'), store)?.body as Array<{
            name: string;
            filters: Record<string, string>;
        }>;
        expect(views[0].filters).toEqual({ level: 'WARN', type: 'BATCH_FAILED' });

        handler(req('POST', '/api/events/views/warn_batches/delete'), store);
        expect(handler(req('GET', '/api/events/views'), store)?.body).toEqual([]);
    });

    it('applies the search query semantics (min level, exact type, q substring, limit)', () => {
        const store = seededStore();
        const all = handler(req('GET', '/api/events/search'), store)?.body as EventRow[];

        const warnPlus = handler(req('GET', '/api/events/search', null, { level: 'WARN' }), store)?.body as EventRow[];
        expect(warnPlus.length).toBeGreaterThan(0);
        expect(warnPlus.length).toBeLessThan(all.length);
        expect(warnPlus.every((e) => ['WARN', 'ERROR'].includes(e.level))).toBe(true);

        const typed = handler(req('GET', '/api/events/search', null, { type: all[0].type }), store)?.body as EventRow[];
        expect(typed.every((e) => e.type === all[0].type)).toBe(true);

        const limited = handler(req('GET', '/api/events/search', null, { limit: '5' }), store)?.body as EventRow[];
        expect(limited.length).toBe(5);

        const q = all[0].message.slice(0, 8).toLowerCase();
        const searched = handler(req('GET', '/api/events/search', null, { q }), store)?.body as EventRow[];
        expect(searched.length).toBeGreaterThan(0);
        expect(
            searched.every((e) => (e.message + ' ' + e.source).toLowerCase().includes(q)),
        ).toBe(true);
    });

    it('serves the audit trail by exact type (the Audit-log pane contract)', () => {
        const store = seededStore();
        const audit = handler(req('GET', '/api/events/search', null, { type: 'AUDIT' }), store)?.body as EventRow[];
        expect(audit.length).toBe(8);
        expect(audit.every((e) => e.attributes['actor'] && e.attributes['action'])).toBe(true);
        const denied = handler(req('GET', '/api/events/search', null, { type: 'ACCESS_DENIED' }), store)
            ?.body as EventRow[];
        expect(denied.length).toBe(2);
    });

    it('exports matching events as CSV text', () => {
        const store = seededStore();
        const csv = handler(req('GET', '/api/events/export', null, { level: 'ERROR', format: 'csv' }), store)
            ?.body as string;
        const lines = csv.split('\n');
        expect(lines[0]).toBe('timestamp,level,type,source,pipeline,correlationId,message');
        expect(lines.length).toBeGreaterThan(1);
        expect(lines.slice(1).every((l) => l.includes('ERROR'))).toBe(true);
    });

    it('a manual evaluation sweep fires and persists one alert off the first armed rule', () => {
        const store = seededStore();
        const before = (handler(req('GET', '/api/alerts'), store)?.body as FiredAlert[]).length;
        const fired = handler(req('POST', '/api/alerts/evaluate'), store)?.body as FiredAlert[];
        expect(fired.length).toBe(1);
        expect(fired[0].value).toBeGreaterThan(fired[0].threshold);
        const after = handler(req('GET', '/api/alerts'), store)?.body as FiredAlert[];
        expect(after.length).toBe(before + 1);
        expect(after[0].rule).toBe(fired[0].rule);

        const limited = handler(req('GET', '/api/alerts', null, { limit: '3' }), store)?.body as FiredAlert[];
        expect(limited.length).toBe(3);
    });

    it('filters operational objects by the type query param', () => {
        const store = seededStore();
        const all = handler(req('GET', '/api/objects'), store)?.body as OperationalObject[];
        expect(all.length).toBe(15);
        const cases = handler(req('GET', '/api/objects', null, { type: 'case' }), store)?.body as OperationalObject[];
        expect(cases.length).toBe(5);
        expect(cases.every((o) => o.objectType === 'CASE')).toBe(true);
    });

    it('creates an object, fetches it, and walks the workflow transitions', () => {
        const store = seededStore();
        const created = handler(
            req('POST', '/api/objects', { type: 'incident', title: 'Late feed', severity: 'WARNING', dueInMinutes: 60 }),
            store,
        )?.body as OperationalObject;
        expect(created.objectType).toBe('INCIDENT');
        expect(created.status).toBe('OPEN');
        expect(Number(created.attributes?.['dueAt'])).toBeGreaterThan(created.createdAt);

        const fetched = handler(req('GET', `/api/objects/${created.id}`), store)?.body as OperationalObject;
        expect(fetched.title).toBe('Late feed');

        const assigned = handler(req('POST', `/api/objects/${created.id}/transition`, { action: 'assign' }), store)
            ?.body as OperationalObject;
        expect(assigned.status).toBe('ASSIGNED');

        const bad = handler(req('POST', `/api/objects/${created.id}/transition`, { action: 'frobnicate' }), store);
        expect(bad?.status).toBe(422);

        const closed = handler(req('POST', `/api/objects/${created.id}/transition`, { action: 'close' }), store)
            ?.body as OperationalObject;
        expect(closed.closedAt).toBeGreaterThan(0);
    });

    it('links two objects and returns the correlation graph around the root', () => {
        const store = seededStore();
        const [a, b] = handler(req('GET', '/api/objects'), store)?.body as OperationalObject[];
        const link = handler(req('POST', `/api/objects/${a.id}/links`, { to: b.id, relationship: 'CAUSED_BY' }), store)
            ?.body as { from: string; to: string };
        expect(link.from).toBe(a.id);

        const graph = handler(req('GET', `/api/objects/${a.id}/graph`, null, { depth: '2' }), store)?.body as {
            nodes: Array<{ id: string }>;
            edges: unknown[];
        };
        expect(graph.nodes.map((n) => n.id).sort()).toEqual([a.id, b.id].sort());
        expect(graph.edges.length).toBe(1);
    });

    it('round-trips comments and attachments and seeds an RCA skeleton', () => {
        const store = seededStore();
        const [a] = handler(req('GET', '/api/objects'), store)?.body as OperationalObject[];

        handler(req('POST', `/api/objects/${a.id}/comments`, { body: 'looking into it' }), store);
        const comments = handler(req('GET', `/api/objects/${a.id}/comments`), store)?.body as Array<{ body: string }>;
        expect(comments.map((c) => c.body)).toEqual(['looking into it']);

        handler(req('POST', `/api/objects/${a.id}/attachments`, { name: 'evidence.log', uri: 's3://x/evidence.log' }), store);
        const atts = handler(req('GET', `/api/objects/${a.id}/attachments`), store)?.body as Array<{
            attributes?: Record<string, string>;
        }>;
        expect(atts[0].attributes?.['uri']).toBe('s3://x/evidence.log');

        const rca = handler(req('POST', `/api/objects/${a.id}/rca`, { template: { sections: ['Summary', 'Root cause'] } }), store)
            ?.body as unknown[];
        expect(rca.length).toBe(2);
        const after = handler(req('GET', `/api/objects/${a.id}/comments`), store)?.body as Array<{ body: string }>;
        expect(after.length).toBe(3); // 1 comment + 2 RCA sections
    });

    it('fans fired alerts and opened incidents out to enabled channels (C4)', () => {
        const store = seededStore();
        store.put('default', NOTIFICATION_CHANNELS_COLL, 'ops_email', {
            id: 'ops_email', kind: 'EMAIL', target: 'ops@x.com', enabled: true, createdAt: 1,
        });

        handler(req('POST', '/api/alerts/evaluate'), store);
        handler(req('POST', '/api/objects', { type: 'INCIDENT', title: 'Late feed' }), store);
        handler(req('POST', '/api/objects', { type: 'CASE', title: 'No fan-out for cases' }), store);

        const deliveries = store.list<{ trigger: string }>('default', NOTIFICATION_DELIVERIES_COLL);
        expect(deliveries.map((d) => d.trigger).sort()).toEqual(['ALERT_FIRED', 'INCIDENT_OPENED']);
    });

    it('falls through entirely when mockOps is off', () => {
        const store = seededStore();
        expect(opsHandler({})(req('GET', '/api/events/search'), store)).toBeUndefined();
    });
});
