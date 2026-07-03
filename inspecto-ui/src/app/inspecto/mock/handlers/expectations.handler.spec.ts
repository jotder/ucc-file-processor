import { describe, expect, it } from 'vitest';
import type { Expectation } from '../../api/expectations.service';
import type { OperationalObject } from '../../api/objects.service';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { expectationsHandler } from './expectations.handler';
import { OPS_OBJECTS_COLL } from './ops.handler';

const req = (method: string, url: string, body: unknown = null): MockRequest => ({
    method,
    url,
    body,
    params: {},
    space: 'default',
});

function seededStore(): MockStore {
    const store = new MockStore();
    store.ensureSeeded('default', seedDefaultSpace);
    return store;
}

describe('expectationsHandler', () => {
    const handler = expectationsHandler({ mockOps: true });

    it('lists the seeded expectations sorted by name', () => {
        const store = seededStore();
        const list = handler(req('GET', '/api/expectations'), store)?.body as Expectation[];
        expect(list.map((e) => e.name)).toEqual([
            'cdr_duration_range',
            'cdr_msisdn_format',
            'cdr_msisdn_not_null',
            'cdr_tariff_known',
        ]);
    });

    it('round-trips create/update/delete and enforces the duplicate 409', () => {
        const store = seededStore();
        const body = { name: 'orders_id_not_null', targetType: 'pipeline', target: 'orders', column: 'id', kind: 'non_null' };
        const created = handler(req('POST', '/api/expectations', body), store)?.body as Expectation;
        expect(created.enabled).toBe(true);
        expect(created.severity).toBe('MAJOR');

        expect(handler(req('POST', '/api/expectations', body), store)?.status).toBe(409);

        const updated = handler(req('PUT', '/api/expectations/orders_id_not_null', { ...body, severity: 'CRITICAL' }), store)
            ?.body as Expectation;
        expect(updated.severity).toBe('CRITICAL');

        handler(req('DELETE', '/api/expectations/orders_id_not_null'), store);
        expect(handler(req('DELETE', '/api/expectations/orders_id_not_null'), store)?.status).toBe(404);
    });

    it('evaluate FAILs the seeded demoViolations row, raises ONE correlated Incident, and passes clean rows', () => {
        const store = seededStore();
        const failed = handler(req('POST', '/api/expectations/cdr_duration_range/evaluate'), store)?.body as Expectation;
        expect(failed.lastResult?.status).toBe('FAILED');
        expect(failed.lastResult?.violations).toBe(12);

        const incidents = store
            .list<OperationalObject>('default', OPS_OBJECTS_COLL)
            .filter((o) => o.correlationId === 'expectation:cdr_duration_range');
        expect(incidents.length).toBe(1);
        expect(incidents[0].objectType).toBe('INCIDENT');
        expect(incidents[0].severity).toBe('MAJOR');

        // A second evaluation must NOT open a second incident while one is still open.
        handler(req('POST', '/api/expectations/cdr_duration_range/evaluate'), store);
        expect(
            store
                .list<OperationalObject>('default', OPS_OBJECTS_COLL)
                .filter((o) => o.correlationId === 'expectation:cdr_duration_range').length,
        ).toBe(1);

        const clean = handler(req('POST', '/api/expectations/cdr_msisdn_not_null/evaluate'), store)?.body as Expectation;
        expect(clean.lastResult?.status).toBe('PASSED');
        expect(clean.lastResult?.violations).toBe(0);
    });

    it('the sweep evaluates only enabled expectations', () => {
        const store = seededStore();
        const rows = handler(req('POST', '/api/expectations/evaluate'), store)?.body as Expectation[];
        const byName = new Map(rows.map((r) => [r.name, r]));
        expect(byName.get('cdr_msisdn_not_null')?.lastResult?.status).toBe('PASSED');
        expect(byName.get('cdr_duration_range')?.lastResult?.status).toBe('FAILED');
        // disabled row is returned untouched
        expect(byName.get('cdr_tariff_known')?.lastResult).toBeNull();
    });

    it('gates on mockOps', () => {
        const store = seededStore();
        expect(expectationsHandler({})(req('GET', '/api/expectations'), store)).toBeUndefined();
    });
});
