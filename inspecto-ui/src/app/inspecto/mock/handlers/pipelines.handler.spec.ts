import { describe, expect, it } from 'vitest';
import { registerIntegrityRules } from '../integrity';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { pipelinesHandler } from './pipelines.handler';

const req = (method: string, url: string, body: unknown = null): MockRequest => ({
    method,
    url,
    body,
    params: {},
    space: 'default',
});

function seededStore(): MockStore {
    const store = new MockStore();
    registerIntegrityRules(store);
    store.ensureSeeded('default', seedDefaultSpace);
    return store;
}

describe('pipelinesHandler — authored DELETE referential integrity (R2)', () => {
    const handler = pipelinesHandler({ mockFlows: true, mockStudio: true });

    it('409s deleting a pipeline a job triggers on, listing the referencing job', () => {
        const store = seededStore();
        // Seeded: job enrich_roaming has onPipeline: 'cdr_ingest'.
        const res = handler(req('DELETE', '/api/pipelines/authored/cdr_ingest'), store);
        expect(res?.status).toBe(409);
        expect(String((res?.body as { error: string }).error)).toContain('job/enrich_roaming');
        expect(store.get('default', 'authored-pipeline', 'cdr_ingest')).toBeDefined();
    });

    it('deletes an unreferenced pipeline', () => {
        const store = seededStore();
        const res = handler(req('DELETE', '/api/pipelines/authored/subscriber_load'), store);
        expect(res?.body).toEqual({ deleted: true });
        expect(store.get('default', 'authored-pipeline', 'subscriber_load')).toBeUndefined();
    });
});
