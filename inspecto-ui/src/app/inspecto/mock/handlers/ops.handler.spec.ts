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

    it('round-trips alert-rule authoring (create 409-on-duplicate, update name-immutable, delete 404-when-absent)', () => {
        const store = seededStore();
        const rule = { name: 'slow_batch_2', metric: 'duration_ms', comparator: 'gt', threshold: 60_000, window: '1h', severity: 'WARNING' };

        expect(handler(req('POST', '/api/alerts/rules', rule), store)?.status).toBe(200);
        expect(handler(req('POST', '/api/alerts/rules', rule), store)?.status).toBe(409); // duplicate name
        expect(handler(req('POST', '/api/alerts/rules', { name: 'no_metric' }), store)?.status).toBe(422);

        const updated = handler(
            req('PUT', '/api/alerts/rules/slow_batch_2', { ...rule, name: 'renamed', threshold: 90_000 }),
            store,
        );
        expect(updated?.status).toBe(200);
        // The path name wins — the id is immutable.
        expect((updated?.body as { name: string; threshold: number }).name).toBe('slow_batch_2');
        expect((updated?.body as { threshold: number }).threshold).toBe(90_000);

        expect(handler(req('DELETE', '/api/alerts/rules/slow_batch_2'), store)?.status).toBe(200);
        expect(handler(req('DELETE', '/api/alerts/rules/slow_batch_2'), store)?.status).toBe(404);
        expect(handler(req('PUT', '/api/alerts/rules/never_existed', rule), store)?.status).toBe(404);
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
            req('POST', '/api/objects', {
                type: 'incident',
                title: 'Late feed',
                severity: 'WARNING',
                dueInMinutes: 60,
                attributes: { category: 'Data Quality / Timeliness / Late arrival', tags: 'urgent' },
            }),
            store,
        )?.body as OperationalObject;
        expect(created.objectType).toBe('INCIDENT');
        // Incidents enter the mail lifecycle at IDENTIFIED (GLOSSARY §9) with attributes passed through.
        expect(created.status).toBe('IDENTIFIED');
        expect(created.attributes?.['category']).toBe('Data Quality / Timeliness / Late arrival');
        expect(Number(created.attributes?.['dueAt'])).toBeGreaterThan(created.createdAt);

        const fetched = handler(req('GET', `/api/objects/${created.id}`), store)?.body as OperationalObject;
        expect(fetched.title).toBe('Late feed');

        const accepted = handler(req('POST', `/api/objects/${created.id}/transition`, { action: 'accept' }), store)
            ?.body as OperationalObject;
        expect(accepted.status).toBe('DIAGNOSING');

        const bad = handler(req('POST', `/api/objects/${created.id}/transition`, { action: 'frobnicate' }), store);
        expect(bad?.status).toBe(422);

        const archived = handler(req('POST', `/api/objects/${created.id}/transition`, { action: 'archive' }), store)
            ?.body as OperationalObject;
        expect(archived.status).toBe('ARCHIVED');
        expect(archived.closedAt).toBeGreaterThan(0);
    });

    it('manages the tag registry (list sorted, create validates + 409s duplicates)', () => {
        const store = seededStore();
        const seeded = handler(req('GET', '/api/tags'), store)?.body as Array<{ name: string }>;
        expect(seeded.map((t) => t.name)).toEqual(['billing', 'data-quality', 'network', 'regression', 'urgent']);
        expect(handler(req('POST', '/api/tags', { name: '  ' }), store)?.status).toBe(422);
        expect(handler(req('POST', '/api/tags', { name: 'a,b' }), store)?.status).toBe(422);
        expect(handler(req('POST', '/api/tags', { name: 'urgent' }), store)?.status).toBe(409);
        expect(handler(req('POST', '/api/tags', { name: 'feeds' }), store)?.status).toBe(200);
        const after = handler(req('GET', '/api/tags'), store)?.body as Array<{ name: string }>;
        expect(after.map((t) => t.name)).toContain('feeds');
    });

    it('saves a Tag Rule (criteria required, tag implicitly registered) and bulk-applies it', () => {
        const store = seededStore();
        // No criteria → 422 (would tag everything).
        expect(handler(req('POST', '/api/tags/rules', { name: 'r0', tag: 't0', filter: {} }), store)?.status).toBe(422);
        // Every seeded incident title contains "rejected" → the rule matches all 5.
        const saved = handler(
            req('POST', '/api/tags/rules', { name: 'feed-issues', tag: 'feed-issue', filter: { type: 'INCIDENT', q: 'rejected' } }),
            store,
        );
        expect(saved?.status).toBe(200);
        const tags = handler(req('GET', '/api/tags'), store)?.body as Array<{ name: string }>;
        expect(tags.map((t) => t.name)).toContain('feed-issue'); // saving the rule registered its tag
        const applied = handler(req('POST', '/api/tags/rules/feed-issues/apply', {}), store)
            ?.body as { matched: number; updated: number };
        expect(applied).toEqual({ matched: 5, updated: 5 });
        const incidents = handler(req('GET', '/api/objects', null, { type: 'incident' }), store)
            ?.body as OperationalObject[];
        expect(incidents.every((o) => (o.attributes?.['tags'] ?? '').includes('feed-issue'))).toBe(true);
        // Re-apply is idempotent.
        const again = handler(req('POST', '/api/tags/rules/feed-issues/apply', {}), store)
            ?.body as { matched: number; updated: number };
        expect(again).toEqual({ matched: 5, updated: 0 });
        expect(handler(req('DELETE', '/api/tags/rules/feed-issues'), store)?.status).toBe(200);
        expect(handler(req('POST', '/api/tags/rules/feed-issues/apply', {}), store)?.status).toBe(404);
    });

    it('auto-applies matching Tag Rules to newly created objects (Gmail-filter semantics)', () => {
        const store = seededStore();
        // The seeded rule "critical-is-urgent" tags CRITICAL incidents with "urgent".
        const created = handler(
            req('POST', '/api/objects', { type: 'incident', title: 'Broken feed', priority: 'CRITICAL' }),
            store,
        )?.body as OperationalObject;
        expect((created.attributes?.['tags'] ?? '').split(',')).toContain('urgent');
        // A non-matching object stays untagged.
        const minor = handler(
            req('POST', '/api/objects', { type: 'incident', title: 'Small glitch', priority: 'LOW' }),
            store,
        )?.body as OperationalObject;
        expect(minor.attributes?.['tags']).toBeUndefined();
    });

    it('merges cases (members re-point, tags union, source closes) and splits them back out', () => {
        const store = seededStore();
        // seeded membership: case-102 CONTAINS incident-101 + incident-104; case-108 CONTAINS incident-107
        const merged = handler(req('POST', '/api/objects/case-102/merge', { sources: ['case-108'] }), store)
            ?.body as { survivor: OperationalObject; merged: string[]; membersMoved: number };
        expect(merged.membersMoved).toBe(1);
        expect(merged.merged).toEqual(['case-108']);

        const absorbed = handler(req('GET', '/api/objects/case-108'), store)?.body as OperationalObject;
        expect(absorbed.status).toBe('CLOSED');
        expect(absorbed.attributes?.['mergedInto']).toBe('case-102');
        // the survivor now contains all three members
        const graph = handler(req('GET', '/api/objects/case-102/graph', null, { depth: '1' }), store)
            ?.body as { edges: Array<{ from: string; to: string; relationship: string }> };
        const members = graph.edges.filter((e) => e.from === 'case-102' && e.relationship === 'CONTAINS');
        expect(members.map((e) => e.to).sort()).toEqual(['incident-101', 'incident-104', 'incident-107']);

        // gates: merged-again → 422; self-merge → 422; empty sources → 400; non-CASE → 422
        expect(handler(req('POST', '/api/objects/case-102/merge', { sources: ['case-108'] }), store)?.status).toBe(422);
        expect(handler(req('POST', '/api/objects/case-102/merge', { sources: ['case-102'] }), store)?.status).toBe(422);
        expect(handler(req('POST', '/api/objects/case-102/merge', { sources: [] }), store)?.status).toBe(400);
        expect(handler(req('POST', '/api/objects/case-102/merge', { sources: ['incident-101'] }), store)?.status).toBe(422);

        // split two members back out into a new case
        const split = handler(req('POST', '/api/objects/case-102/split',
            { title: 'part B', members: ['incident-101', 'incident-107'], assignee: 'dana' }), store)
            ?.body as { case: OperationalObject; membersMoved: number };
        expect(split.membersMoved).toBe(2);
        expect(split.case.objectType).toBe('CASE');
        expect(split.case.assignee).toBe('dana');
        const after = handler(req('GET', '/api/objects/case-102/graph', null, { depth: '1' }), store)
            ?.body as { edges: Array<{ from: string; to: string; relationship: string }> };
        expect(after.edges.filter((e) => e.from === 'case-102' && e.relationship === 'CONTAINS')
            .map((e) => e.to)).toEqual(['incident-104']);
        // foreign member now → 422; missing title → 400
        expect(handler(req('POST', '/api/objects/case-102/split',
            { title: 'x', members: ['incident-101'] }), store)?.status).toBe(422);
        expect(handler(req('POST', '/api/objects/case-102/split',
            { members: ['incident-104'] }), store)?.status).toBe(400);
    });

    it('serves the effective lifecycle per type (C6 workflow-driven folders)', () => {
        const store = seededStore();
        const caseWf = handler(req('GET', '/api/workflows/CASE'), store)?.body as {
            initial: string; states: string[]; terminal: string[];
        };
        expect(caseWf.initial).toBe('OPEN');
        expect(caseWf.states).toEqual(['OPEN', 'INVESTIGATING', 'ESCALATED', 'RESOLVED', 'CLOSED']);
        expect(caseWf.terminal).toEqual(['CLOSED']);
        expect((handler(req('GET', '/api/workflows/incident'), store)?.body as { initial: string }).initial)
            .toBe('IDENTIFIED');
        expect(handler(req('GET', '/api/workflows/bogus'), store)?.status).toBe(400);
    });

    it('deletes a single link and 404s when it is already gone', () => {
        const store = seededStore();
        expect(handler(req('DELETE', '/api/objects/case-102/links', null,
            { to: 'incident-101', relationship: 'CONTAINS' }), store)?.status).toBe(200);
        expect(handler(req('DELETE', '/api/objects/case-102/links', null,
            { to: 'incident-101', relationship: 'CONTAINS' }), store)?.status).toBe(404);
        expect(handler(req('DELETE', '/api/objects/case-102/links', null, {}), store)?.status).toBe(400);
        expect(handler(req('DELETE', '/api/objects/nope/links', null,
            { to: 'x', relationship: 'CONTAINS' }), store)?.status).toBe(404);
    });

    it('patches priority and merges attributes without touching the rest', () => {
        const store = seededStore();
        const [o] = handler(req('GET', '/api/objects', null, { type: 'incident' }), store)?.body as OperationalObject[];
        const patched = handler(
            req('PATCH', `/api/objects/${o.id}`, { priority: 'CRITICAL', attributes: { escalated: 'true' } }),
            store,
        )?.body as OperationalObject;
        expect(patched.priority).toBe('CRITICAL');
        expect(patched.attributes?.['escalated']).toBe('true');
        expect(patched.attributes?.['pipeline']).toBe(o.attributes?.['pipeline']); // merge, not replace
        expect(patched.title).toBe(o.title);
        expect(handler(req('PATCH', '/api/objects/nope', { priority: 'LOW' }), store)?.status).toBe(404);
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
        const ids = graph.nodes.map((n) => n.id);
        expect(ids).toContain(a.id);
        expect(ids).toContain(b.id);
        // Depth 2 also reaches the seeded case that CONTAINS incident-101 (case membership, GLOSSARY §9).
        expect(ids).toContain('case-102');
        expect(graph.edges.length).toBeGreaterThanOrEqual(2);
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
