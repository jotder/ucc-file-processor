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

    it('lets the SSE stream fall through and gates on mockDemo', () => {
        const store = seededStore();
        expect(handler(req('GET', '/api/notifications/stream'), store)).toBeUndefined();
        expect(demoHandler({})(req('GET', '/api/health'), store)).toBeUndefined();
    });
});
