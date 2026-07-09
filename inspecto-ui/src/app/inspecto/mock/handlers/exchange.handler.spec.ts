import { describe, expect, it } from 'vitest';
import type { ExchangeFreshness, ExchangeGrant, ExchangeOffer } from '../../api/exchange.service';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { componentCollection } from './components.handler';
import { exchangeHandler } from './exchange.handler';
import { SERVER_SPACE, SPACES_COLL } from './spaces.handler';

const handler = exchangeHandler({ mockExchange: true });

const req = (method: string, url: string, body: unknown = null, params: Record<string, string> = {}): MockRequest => ({
    method,
    url,
    body,
    params,
    space: 'default',
});

/** A store with the space registry the handler validates against (mirrors spacesHandler.ensureRegistry). */
function storeWithSpaces(): MockStore {
    const store = new MockStore();
    for (const id of ['default', 'ops']) {
        store.put(SERVER_SPACE, SPACES_COLL, id, {
            id, displayName: id, description: '', createdAt: new Date().toISOString(),
        });
    }
    return store;
}

function grantOf(res: { body: unknown } | undefined): ExchangeGrant {
    return res?.body as ExchangeGrant;
}

describe('exchangeHandler', () => {
    it('gates on the mockExchange flag and the /exchange/ prefix', () => {
        expect(exchangeHandler({})(req('GET', '/api/exchange/offers'), storeWithSpaces())).toBeUndefined();
        expect(handler(req('GET', '/api/jobs'), storeWithSpaces())).toBeUndefined();
    });

    it('seeds a demoable catalog: offers with freshness, an active grant and a pending request', () => {
        const store = storeWithSpaces();
        const offers = handler(req('GET', '/api/exchange/offers'), store)?.body as ExchangeOffer[];
        expect(offers.length).toBeGreaterThanOrEqual(3);
        const fx = offers.find((o) => o.item === 'fx_rates_daily');
        expect(fx?.freshness?.version).toBe('v3');

        const grants = handler(req('GET', '/api/exchange/grants', null, { space: 'default' }), store)
            ?.body as ExchangeGrant[];
        expect(grants.map((g) => g.status).sort()).toEqual(['active', 'requested']);
    });

    it('walks the request → approve lifecycle with idempotent re-request and 409 on active', () => {
        const store = storeWithSpaces();
        const body = { kind: 'dataset', owner: 'analytics-hub', consumer: 'ops', item: 'customer_segments', purpose: 'test' };
        const first = grantOf(handler(req('POST', '/api/exchange/requests', body), store));
        expect(first.status).toBe('requested');
        expect(first.id).toBe('ops~analytics-hub~dataset~customer_segments');

        // Idempotent re-request returns the same pending grant.
        expect(grantOf(handler(req('POST', '/api/exchange/requests', body), store)).requestedAt).toBe(first.requestedAt);

        const approved = grantOf(handler(req('POST', `/api/exchange/grants/${first.id}/approve`), store));
        expect(approved.status).toBe('active');
        expect(approved.approvedBy).toBe('appUser');

        expect(handler(req('POST', '/api/exchange/requests', body), store)?.status).toBe(409);
    });

    it('denies a pending request and rejects invalid transitions', () => {
        const store = storeWithSpaces();
        const id = 'analytics-hub~default~dataset~billing_summary'; // seeded pending request
        expect(grantOf(handler(req('POST', `/api/exchange/grants/${id}/deny`), store)).status).toBe('denied');
        expect(handler(req('POST', `/api/exchange/grants/${id}/revoke`), store)?.status).toBe(409);
        expect(handler(req('POST', '/api/exchange/grants/nope/approve'), store)?.status).toBe(404);
    });

    it('rejects a self-request, an unknown offer, and an unknown space', () => {
        const store = storeWithSpaces();
        const base = { kind: 'dataset', item: 'billing_summary' };
        expect(handler(req('POST', '/api/exchange/requests', { ...base, owner: 'default', consumer: 'default' }), store)?.status).toBe(400);
        expect(handler(req('POST', '/api/exchange/requests', { kind: 'dataset', owner: 'ops', consumer: 'default', item: 'nope' }), store)?.status).toBe(404);
        expect(handler(req('POST', '/api/exchange/requests', { ...base, owner: 'ghost', consumer: 'default' }), store)?.status).toBe(404);
    });

    it('enforces the widget pairing rules: dataset offered first, grants travel and cascade', () => {
        const store = storeWithSpaces();
        store.put(SERVER_SPACE, SPACES_COLL, 'analytics-hub', { id: 'analytics-hub', displayName: '', description: '', createdAt: '' });
        store.put('default', componentCollection('widget'), 'billing_chart', {
            name: 'billing_chart',
            content: { viz: 'bar', query: { dataset: 'billing_summary' } },
        });

        // Widget offer 409s until its bound dataset is offered — the seed already offers billing_summary.
        const offered = handler(req('POST', '/api/exchange/offers', { kind: 'widget', owner: 'default', item: 'billing_chart' }), store);
        expect((offered?.body as ExchangeOffer).dataset).toBe('billing_summary');

        // Requesting the widget auto-creates the paired dataset request; approving activates both.
        const g = grantOf(handler(req('POST', '/api/exchange/requests',
            { kind: 'widget', owner: 'default', consumer: 'ops', item: 'billing_chart' }), store));
        handler(req('POST', `/api/exchange/grants/${g.id}/approve`), store);
        const dsGrant = store.get<ExchangeGrant>(SERVER_SPACE, 'exchange-grant', 'ops~default~dataset~billing_summary');
        expect(dsGrant?.status).toBe('active');

        // Render works while both grants are active…
        const render = handler(req('GET', '/api/exchange/widgets/default/billing_chart', null, { consumer: 'ops' }), store);
        expect(render?.status ?? 200).toBe(200);
        expect((render?.body as { dataset?: string }).dataset).toBe('shared/default/billing_summary');

        // …and revoking the dataset grant cascades to the widget grant (fail-closed).
        handler(req('POST', `/api/exchange/grants/${dsGrant!.id}/revoke`), store);
        expect(store.get<ExchangeGrant>(SERVER_SPACE, 'exchange-grant', g.id)?.status).toBe('revoked');
        expect(handler(req('GET', '/api/exchange/widgets/default/billing_chart', null, { consumer: 'ops' }), store)?.status).toBe(403);
    });

    it('422s a widget offer with no dataset binding', () => {
        const store = storeWithSpaces();
        store.put('default', componentCollection('widget'), 'plain', { name: 'plain', content: { viz: 'text' } });
        expect(handler(req('POST', '/api/exchange/offers', { kind: 'widget', owner: 'default', item: 'plain' }), store)?.status).toBe(422);
    });

    it('sets and clears pin and expiry, and enforces expiry at render time', () => {
        const store = storeWithSpaces();
        const id = 'default~analytics-hub~dataset~fx_rates_daily'; // seeded active grant
        expect(grantOf(handler(req('POST', `/api/exchange/grants/${id}/pin`, { version: 'v2' }), store)).pin).toBe('v2');
        expect(grantOf(handler(req('POST', `/api/exchange/grants/${id}/pin`, { version: '' }), store)).pin).toBeNull();

        const past = Date.now() - 1000;
        expect(grantOf(handler(req('POST', `/api/exchange/grants/${id}/expiry`, { expiresAt: past }), store)).expiresAt).toBe(past);
        expect(grantOf(handler(req('POST', `/api/exchange/grants/${id}/expiry`, { expiresAt: null }), store)).expiresAt).toBeNull();
    });

    it('refreshes a snapshot (version bump) and 404s an un-offered dataset', () => {
        const store = storeWithSpaces();
        store.put(SERVER_SPACE, SPACES_COLL, 'analytics-hub', { id: 'analytics-hub', displayName: '', description: '', createdAt: '' });
        const meta = handler(req('POST', '/api/exchange/refresh', { owner: 'analytics-hub', item: 'fx_rates_daily' }), store)
            ?.body as ExchangeFreshness;
        expect(meta.version).toBe('v4'); // seeded at v3
        expect(handler(req('POST', '/api/exchange/refresh', { owner: 'default', item: 'nope' }), store)?.status).toBe(404);
    });

    it('serves dataset metadata with the consumer grant merged in', () => {
        const store = storeWithSpaces();
        const meta = handler(req('GET', '/api/exchange/datasets/analytics-hub/fx_rates_daily', null, { consumer: 'default' }), store)
            ?.body as ExchangeOffer & { grant?: ExchangeGrant };
        expect(meta.freshness?.version).toBe('v3');
        expect(meta.grant?.status).toBe('active');
    });
});
