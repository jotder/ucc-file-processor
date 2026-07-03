import { describe, expect, it } from 'vitest';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { demoHandler } from './demo.handler';

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

describe('demoHandler', () => {
    const handler = demoHandler({ mockDemo: true });

    it('round-trips channels (C4) and enforces the duplicate-id 409', () => {
        const store = seededStore();
        expect(handler(req('GET', '/api/notifications/channels'), store)?.body).toEqual([]);

        handler(req('POST', '/api/notifications/channels', { id: 'ops_email', kind: 'EMAIL', target: 'ops@x.com' }), store);
        const dup = handler(req('POST', '/api/notifications/channels', { id: 'ops_email', kind: 'EMAIL', target: 'b@x.com' }), store);
        expect(dup?.status).toBe(409);

        handler(req('PUT', '/api/notifications/channels/ops_email', { enabled: false }), store);
        const list = handler(req('GET', '/api/notifications/channels'), store)?.body as Array<{ enabled: boolean }>;
        expect(list[0].enabled).toBe(false);

        handler(req('DELETE', '/api/notifications/channels/ops_email'), store);
        expect(handler(req('GET', '/api/notifications/channels'), store)?.body).toEqual([]);
    });

    it('persists the preference grid per space (was a static no-op)', () => {
        const store = seededStore();
        const before = handler(req('GET', '/api/notifications/preferences'), store)?.body as Array<{
            category: string;
            channels: { email: boolean };
        }>;
        const edited = before.map((r) => (r.category === 'PIPELINE' ? { ...r, channels: { ...r.channels, email: true } } : r));
        handler(req('PUT', '/api/notifications/preferences', { preferences: edited }), store);
        const after = handler(req('GET', '/api/notifications/preferences'), store)?.body as typeof before;
        expect(after.find((r) => r.category === 'PIPELINE')?.channels.email).toBe(true);
    });

    it('serves the health / status surface', () => {
        const store = seededStore();
        expect(handler(req('GET', '/api/health'), store)?.body).toEqual({ status: 'UP' });
        const status = handler(req('GET', '/api/status'), store)?.body as { pipelineCount: number };
        expect(status.pipelineCount).toBe(5);
    });

    it('round-trips notification reads and deletes through the store', () => {
        const store = seededStore();
        const unread = (): number =>
            (handler(req('GET', '/api/notifications/unread-count'), store)?.body as { count: number }).count;
        expect(unread()).toBe(3);

        handler(req('POST', '/api/notifications/notif-100/read'), store);
        expect(unread()).toBe(2);

        handler(req('POST', '/api/notifications/read-all'), store);
        expect(unread()).toBe(0);

        handler(req('DELETE', '/api/notifications/notif-101'), store);
        const list = handler(req('GET', '/api/notifications'), store)?.body as Array<{ id: string }>;
        expect(list.length).toBe(7);
        expect(list.some((n) => n.id === 'notif-101')).toBe(false);
    });

    it('validates a draft against the spec — missing required fields become ERROR findings', () => {
        const store = seededStore();
        const bad = handler(req('POST', '/api/validate', { type: 'pipeline', config: {} }), store)?.body as {
            clean: boolean;
            findings: { severity: string; fieldPath: string }[];
        };
        expect(bad.clean).toBe(false);
        expect(bad.findings.map((f) => f.fieldPath)).toContain('pipeline');
        expect(bad.findings.every((f) => f.severity === 'ERROR')).toBe(true);

        const ok = handler(
            req('POST', '/api/validate', {
                type: 'pipeline',
                config: { pipeline: 'cdr', source: { connector: 'sftp' } },
                safety: true,
            }),
            store,
        )?.body as { clean: boolean; findings: { fieldPath: string }[]; safetyChecked: boolean };
        expect(ok.findings.filter((f) => f.fieldPath === 'pipeline')).toEqual([]);
        expect(ok.safetyChecked).toBe(true);

        // file mode stays always-clean
        const file = handler(req('POST', '/api/validate', { configPath: 'configs/cdr.toon' }), store)?.body as { clean: boolean };
        expect(file.clean).toBe(true);
    });

    it('lets the SSE stream fall through and gates on mockDemo', () => {
        const store = seededStore();
        expect(handler(req('GET', '/api/notifications/stream'), store)).toBeUndefined();
        expect(demoHandler({})(req('GET', '/api/health'), store)).toBeUndefined();
    });
});
