import { describe, expect, it } from 'vitest';
import type { FiredAlert } from '../../api/alerts.service';
import type { EventRow } from '../../api/events.service';
import type { OperationalObject } from '../../api/objects.service';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
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
        expect(events.length).toBe(30);
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

    it('filters operational objects by the type query param', () => {
        const store = seededStore();
        const all = handler(req('GET', '/api/objects'), store)?.body as OperationalObject[];
        expect(all.length).toBe(15);
        const cases = handler(req('GET', '/api/objects', null, { type: 'case' }), store)?.body as OperationalObject[];
        expect(cases.length).toBe(5);
        expect(cases.every((o) => o.objectType === 'CASE')).toBe(true);
    });

    it('falls through entirely when mockOps is off', () => {
        const store = seededStore();
        expect(opsHandler({})(req('GET', '/api/events/search'), store)).toBeUndefined();
    });
});
