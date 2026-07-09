import type {
    ExchangeFreshness,
    ExchangeGrant,
    ExchangeOffer,
} from '../../api/exchange.service';
import type { Space } from '../../api/spaces.service';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { componentCollection } from './components.handler';
import { SERVER_SPACE, SPACES_COLL } from './spaces.handler';

/**
 * The Exchange mock domain — cross-Space Dataset/Widget sharing (`/exchange/*`), the offline mirror
 * of `ExchangeRoutes` + `Exchange` so the whole §3.6 UI track works with no backend. Installation-
 * scope like `/spaces`: the ledgers live in the reserved `_server` pseudo-space, never per-space.
 *
 * Mirrors the backend lifecycle faithfully: `requested → active | denied`, active → `revoked`;
 * idempotent re-request, 409 on re-requesting an active grant; widget grants pair with their bound
 * Dataset grant (request auto-creates it, approve activates both, revoking the dataset cascades);
 * expiry is enforced at render time (fail-closed).
 */

export const EXCHANGE_OFFERS_COLL = 'exchange-offer'; // id: owner~kind~item
export const EXCHANGE_GRANTS_COLL = 'exchange-grant'; // id: consumer~owner~kind~item
export const EXCHANGE_SNAPSHOTS_COLL = 'exchange-snapshot'; // id: owner~item (dataset freshness)

const OFFERS = /\/exchange\/offers$/;
const REFRESH = /\/exchange\/refresh$/;
const REQUESTS = /\/exchange\/requests$/;
const GRANT_ACT = /\/exchange\/grants\/([^/]+)\/(approve|deny|revoke)$/;
const GRANT_PIN = /\/exchange\/grants\/([^/]+)\/pin$/;
const GRANT_EXPIRY = /\/exchange\/grants\/([^/]+)\/expiry$/;
const GRANTS = /\/exchange\/grants$/;
const DATASET_META = /\/exchange\/datasets\/([^/]+)\/([^/]+)$/;
const WIDGET_RENDER = /\/exchange\/widgets\/([^/]+)\/([^/]+)$/;

export function exchangeHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockExchange) return undefined;
        const { method, url } = req;
        if (!url.includes('/exchange/')) return undefined;
        let m: string[] | null;

        ensureSeed(store);

        if (method === 'GET' && OFFERS.test(url)) {
            const owner = req.params['owner'];
            return json(
                store
                    .list<ExchangeOffer>(SERVER_SPACE, EXCHANGE_OFFERS_COLL)
                    .filter((o) => !owner || o.owner === owner)
                    .map((o) => withFreshness(store, o)),
            );
        }
        if (method === 'POST' && OFFERS.test(url)) return putOffer(store, req.body as OfferBody);
        if (method === 'POST' && REFRESH.test(url)) return refresh(store, req.body as OfferBody);
        if (method === 'POST' && REQUESTS.test(url)) return requestGrant(store, req.body as RequestBody);
        if (method === 'POST' && (m = match(url, GRANT_ACT))) return actOnGrant(store, m[1], m[2]);
        if (method === 'POST' && (m = match(url, GRANT_PIN))) {
            const version = (req.body as { version?: string })?.version;
            return mutateGrant(store, m[1], (g) => ({ ...g, pin: version ? version : null }));
        }
        if (method === 'POST' && (m = match(url, GRANT_EXPIRY))) {
            const expiresAt = (req.body as { expiresAt?: number | null })?.expiresAt ?? null;
            return mutateGrant(store, m[1], (g) => ({ ...g, expiresAt }));
        }
        if (method === 'GET' && GRANTS.test(url)) {
            const space = req.params['space'];
            return json(
                store
                    .list<ExchangeGrant>(SERVER_SPACE, EXCHANGE_GRANTS_COLL)
                    .filter((g) => !space || g.owner === space || g.consumer === space),
            );
        }
        if (method === 'GET' && (m = match(url, DATASET_META))) {
            return datasetMeta(store, m[1], m[2], req.params['consumer']);
        }
        if (method === 'GET' && (m = match(url, WIDGET_RENDER))) {
            return widgetRender(store, m[1], m[2], req.params['consumer']);
        }
        return undefined;
    };
}

interface OfferBody {
    kind?: string;
    owner?: string;
    item?: string;
    description?: string;
    resultSet?: Record<string, unknown>;
}

interface RequestBody extends OfferBody {
    consumer?: string;
    purpose?: string;
    mode?: string;
}

// ── offers ─────────────────────────────────────────────────────────────────────

function putOffer(store: MockStore, b: OfferBody): ReturnType<typeof json> {
    const bad = validate(store, b);
    if (bad) return bad;
    const { kind, owner, item } = b as Required<Pick<OfferBody, 'kind' | 'owner' | 'item'>>;

    const component = store.get<{ content?: Record<string, unknown> }>(
        owner,
        componentCollection(kind),
        item,
    );
    if (!component) return error(404, `no ${kind} '${item}' in space '${owner}'`);

    // A Widget shares render-only; its bound Dataset must already be offered by the same owner (§3.5).
    let dataset: string | null = null;
    if (kind === 'widget') {
        dataset = findKey(component.content, ['dataset', 'datasetId']);
        if (!dataset) return error(422, `widget '${item}' has no dataset binding to share`);
        if (!store.has(SERVER_SPACE, EXCHANGE_OFFERS_COLL, `${owner}~dataset~${dataset}`)) {
            return error(409, `offer the widget's dataset '${dataset}' before the widget`);
        }
    }

    const offer: ExchangeOffer = {
        kind: kind as ExchangeOffer['kind'],
        item,
        owner,
        description: b.description ?? '',
        resultSet: b.resultSet ?? {},
        offeredBy: 'appUser',
        offeredAt: Date.now(),
        dataset,
    };
    store.put(SERVER_SPACE, EXCHANGE_OFFERS_COLL, `${owner}~${kind}~${item}`, offer);
    return json(offer);
}

function refresh(store: MockStore, b: OfferBody): ReturnType<typeof json> {
    const bad = validate(store, { ...b, kind: 'dataset' });
    if (bad) return bad;
    const { owner, item } = b as Required<Pick<OfferBody, 'owner' | 'item'>>;
    if (!store.has(SERVER_SPACE, EXCHANGE_OFFERS_COLL, `${owner}~dataset~${item}`)) {
        return error(404, `no offered dataset ${owner}/${item}`);
    }
    const id = `${owner}~${item}`;
    const prev = store.get<ExchangeFreshness>(SERVER_SPACE, EXCHANGE_SNAPSHOTS_COLL, id);
    const version = `v${(prev ? parseInt(prev.version.slice(1), 10) : 0) + 1}`;
    const meta: ExchangeFreshness = {
        version,
        rows: prev?.rows ?? 1000 + Math.floor(Math.random() * 9000),
        refreshedAt: new Date().toISOString(),
        columns: prev?.columns ?? [],
    };
    return json(store.put(SERVER_SPACE, EXCHANGE_SNAPSHOTS_COLL, id, meta));
}

function withFreshness(store: MockStore, offer: ExchangeOffer): ExchangeOffer {
    const meta = store.get<ExchangeFreshness>(
        SERVER_SPACE,
        EXCHANGE_SNAPSHOTS_COLL,
        `${offer.owner}~${offer.item}`,
    );
    return meta ? { ...offer, freshness: meta } : offer;
}

// ── grant lifecycle ────────────────────────────────────────────────────────────

function requestGrant(store: MockStore, b: RequestBody): ReturnType<typeof json> {
    const bad = validate(store, b);
    if (bad) return bad;
    const { kind, owner, item } = b as Required<Pick<RequestBody, 'kind' | 'owner' | 'item'>>;
    const consumer = b.consumer ?? '';
    if (!spaceExists(store, consumer)) return error(404, `no such space '${consumer}'`);
    if (owner === consumer) return error(400, 'a space cannot request a share from itself');

    const offer = store.get<ExchangeOffer>(SERVER_SPACE, EXCHANGE_OFFERS_COLL, `${owner}~${kind}~${item}`);
    if (!offer) return error(404, `no offer for ${kind} ${owner}/${item}`);

    const id = `${consumer}~${owner}~${kind}~${item}`;
    const existing = store.get<ExchangeGrant>(SERVER_SPACE, EXCHANGE_GRANTS_COLL, id);
    if (existing?.status === 'active') return error(409, `an active grant already exists for ${id}`);
    if (existing?.status === 'requested') return json(existing); // idempotent re-request

    const grant = newGrant(id, kind, item, owner, consumer, b);
    store.put(SERVER_SPACE, EXCHANGE_GRANTS_COLL, id, grant);

    // Widget grant closure (§3.5): ensure a pending Dataset grant travels with the widget request.
    if (kind === 'widget' && offer.dataset) {
        const dgid = `${consumer}~${owner}~dataset~${offer.dataset}`;
        const pair = store.get<ExchangeGrant>(SERVER_SPACE, EXCHANGE_GRANTS_COLL, dgid);
        if (pair?.status !== 'active' && pair?.status !== 'requested') {
            store.put(SERVER_SPACE, EXCHANGE_GRANTS_COLL, dgid, newGrant(dgid, 'dataset', offer.dataset, owner, consumer, b));
        }
    }
    return json(grant);
}

function newGrant(id: string, kind: string, item: string, owner: string, consumer: string, b: RequestBody): ExchangeGrant {
    return {
        id,
        kind: kind as ExchangeGrant['kind'],
        item,
        owner,
        consumer,
        mode: b.mode === 'live' ? 'live' : 'snapshot',
        status: 'requested',
        requestedBy: 'appUser',
        requestedAt: Date.now(),
        purpose: b.purpose ?? '',
        approvedBy: null,
        approvedAt: 0,
        pin: null,
        expiresAt: null,
    };
}

function actOnGrant(store: MockStore, id: string, action: string): ReturnType<typeof json> {
    const from = action === 'revoke' ? 'active' : 'requested';
    const to = action === 'approve' ? 'active' : action === 'deny' ? 'denied' : 'revoked';
    const result = transition(store, id, from, to, action !== 'revoke');
    if ('error' in result) return result.error;
    const g = result.grant;

    if (action === 'approve' && g.kind === 'widget') {
        // Approving a widget grant activates its paired (still-pending) Dataset grant atomically.
        const ds = boundDataset(store, g.owner, g.item);
        if (ds) transition(store, `${g.consumer}~${g.owner}~dataset~${ds}`, 'requested', 'active', true);
    }
    if (action === 'revoke' && g.kind === 'dataset') {
        // Revoking a Dataset grant cascades to every active widget grant that depends on it (fail-closed).
        for (const w of store.list<ExchangeGrant>(SERVER_SPACE, EXCHANGE_GRANTS_COLL)) {
            if (
                w.kind === 'widget' && w.status === 'active' &&
                w.owner === g.owner && w.consumer === g.consumer &&
                boundDataset(store, w.owner, w.item) === g.item
            ) {
                transition(store, w.id, 'active', 'revoked', false);
            }
        }
    }
    return json(g);
}

function transition(
    store: MockStore,
    id: string,
    from: string,
    to: string,
    stampApproval: boolean,
): { grant: ExchangeGrant } | { error: ReturnType<typeof error> } {
    const g = store.get<ExchangeGrant>(SERVER_SPACE, EXCHANGE_GRANTS_COLL, id);
    if (!g) return { error: error(404, `no such grant '${id}'`) };
    if (g.status !== from) {
        return { error: error(409, `cannot move grant '${id}' from ${g.status} to ${to} (expected ${from})`) };
    }
    const next: ExchangeGrant = {
        ...g,
        status: to as ExchangeGrant['status'],
        approvedBy: stampApproval ? 'appUser' : g.approvedBy,
        approvedAt: stampApproval ? Date.now() : g.approvedAt,
    };
    return { grant: store.put(SERVER_SPACE, EXCHANGE_GRANTS_COLL, id, next) };
}

function mutateGrant(
    store: MockStore,
    id: string,
    fn: (g: ExchangeGrant) => ExchangeGrant,
): ReturnType<typeof json> {
    const g = store.get<ExchangeGrant>(SERVER_SPACE, EXCHANGE_GRANTS_COLL, id);
    if (!g) return error(404, `no such grant '${id}'`);
    return json(store.put(SERVER_SPACE, EXCHANGE_GRANTS_COLL, id, fn(g)));
}

// ── reads ──────────────────────────────────────────────────────────────────────

function datasetMeta(store: MockStore, owner: string, item: string, consumer?: string): ReturnType<typeof json> {
    const offer = store.get<ExchangeOffer>(SERVER_SPACE, EXCHANGE_OFFERS_COLL, `${owner}~dataset~${item}`);
    if (!offer) return error(404, `no offered dataset ${owner}/${item}`);
    const out: Record<string, unknown> = { ...withFreshness(store, offer) };
    if (consumer) {
        const g = store.get<ExchangeGrant>(SERVER_SPACE, EXCHANGE_GRANTS_COLL, `${consumer}~${owner}~dataset~${item}`);
        if (g) out['grant'] = g;
    }
    return json(out);
}

function widgetRender(store: MockStore, owner: string, item: string, consumer?: string): ReturnType<typeof json> {
    if (!consumer) return error(400, `'consumer' query param is required`);
    const ds = boundDataset(store, owner, item);
    const widgetOk = effectivelyActive(store, `${consumer}~${owner}~widget~${item}`);
    const datasetOk = ds !== null && effectivelyActive(store, `${consumer}~${owner}~dataset~${ds}`);
    if (!widgetOk || !datasetOk) return error(403, `no active grant to render widget ${owner}/${item}`);
    const component = store.get<{ content?: Record<string, unknown> }>(owner, componentCollection('widget'), item);
    if (!component) return error(404, `no widget '${item}' in space '${owner}'`);
    return json({
        owner,
        item,
        content: component.content ?? {},
        readOnly: true,
        ...(ds ? { dataset: `shared/${owner}/${ds}` } : {}),
    });
}

/** Active and not past expiry — the single fail-closed gate (mirrors `Exchange.effectivelyActive`). */
function effectivelyActive(store: MockStore, id: string): boolean {
    const g = store.get<ExchangeGrant>(SERVER_SPACE, EXCHANGE_GRANTS_COLL, id);
    return g?.status === 'active' && (g.expiresAt === null || g.expiresAt > Date.now());
}

/** The Dataset a widget offer binds, from the stored offer (mirrors `Exchange.boundDataset`). */
function boundDataset(store: MockStore, owner: string, item: string): string | null {
    const offer = store.get<ExchangeOffer>(SERVER_SPACE, EXCHANGE_OFFERS_COLL, `${owner}~widget~${item}`);
    return offer?.dataset || null;
}

// ── validation / helpers ───────────────────────────────────────────────────────

const ITEM_ID = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

function validate(store: MockStore, b: OfferBody): ReturnType<typeof error> | null {
    if (b.kind !== 'dataset' && b.kind !== 'widget') return error(400, `'kind' must be 'dataset' or 'widget'`);
    if (!b.owner) return error(400, `'owner' must be a valid space id`);
    if (!spaceExists(store, b.owner)) return error(404, `no such space '${b.owner}'`);
    if (!b.item || b.item.includes('..') || !ITEM_ID.test(b.item)) return error(400, `'item' must be a valid component id`);
    return null;
}

function spaceExists(store: MockStore, id: string): boolean {
    return store.has(SERVER_SPACE, SPACES_COLL, id);
}

/** First value of one of `keys` anywhere in a widget's content tree (mirrors `ExchangeRoutes.findKey`). */
function findKey(node: unknown, keys: string[]): string | null {
    if (node && typeof node === 'object' && !Array.isArray(node)) {
        const m = node as Record<string, unknown>;
        for (const k of keys) {
            const v = m[k];
            if (v !== null && v !== undefined && String(v).trim() !== '') return String(v);
        }
        for (const v of Object.values(m)) {
            const r = findKey(v, keys);
            if (r !== null) return r;
        }
    } else if (Array.isArray(node)) {
        for (const v of node) {
            const r = findKey(v, keys);
            if (r !== null) return r;
        }
    }
    return null;
}

// ── seed ───────────────────────────────────────────────────────────────────────

/**
 * Seed the Exchange ledgers once: a partner space ("analytics-hub") offering two datasets — one with a
 * published snapshot and an active grant to `default` (a working "Shared with me"), one un-granted (a
 * requestable catalog entry) — plus a pending inbound request against a `default`-owned offer, so the
 * owner-side approve/deny flow is demoable immediately.
 */
function ensureSeed(store: MockStore): void {
    if (store.list(SERVER_SPACE, EXCHANGE_OFFERS_COLL).length > 0) return;
    const now = Date.now();

    if (!store.has(SERVER_SPACE, SPACES_COLL, 'analytics-hub')) {
        store.put<Space>(SERVER_SPACE, SPACES_COLL, 'analytics-hub', {
            id: 'analytics-hub',
            displayName: 'Analytics Hub',
            description: 'Partner space publishing curated reference datasets.',
            createdAt: new Date(now - 90 * 86_400_000).toISOString(),
        });
    }

    const offers: ExchangeOffer[] = [
        {
            kind: 'dataset', item: 'fx_rates_daily', owner: 'analytics-hub',
            description: 'Daily FX reference rates, one row per currency pair per day.',
            resultSet: {}, offeredBy: 'analyst', offeredAt: now - 30 * 86_400_000, dataset: null,
        },
        {
            kind: 'dataset', item: 'customer_segments', owner: 'analytics-hub',
            description: 'Curated customer segmentation (refreshed weekly).',
            resultSet: {}, offeredBy: 'analyst', offeredAt: now - 14 * 86_400_000, dataset: null,
        },
        {
            kind: 'dataset', item: 'billing_summary', owner: 'default',
            description: 'Monthly billing rollup by account.',
            resultSet: {}, offeredBy: 'appUser', offeredAt: now - 7 * 86_400_000, dataset: null,
        },
    ];
    for (const o of offers) store.put(SERVER_SPACE, EXCHANGE_OFFERS_COLL, `${o.owner}~${o.kind}~${o.item}`, o);

    store.put<ExchangeFreshness>(SERVER_SPACE, EXCHANGE_SNAPSHOTS_COLL, 'analytics-hub~fx_rates_daily', {
        version: 'v3', rows: 48210, refreshedAt: new Date(now - 86_400_000).toISOString(), columns: [],
    });

    const grants: ExchangeGrant[] = [
        {
            id: 'default~analytics-hub~dataset~fx_rates_daily',
            kind: 'dataset', item: 'fx_rates_daily', owner: 'analytics-hub', consumer: 'default',
            mode: 'snapshot', status: 'active',
            requestedBy: 'appUser', requestedAt: now - 20 * 86_400_000,
            purpose: 'Currency normalization in billing pipelines.',
            approvedBy: 'analyst', approvedAt: now - 19 * 86_400_000, pin: null, expiresAt: null,
        },
        {
            id: 'analytics-hub~default~dataset~billing_summary',
            kind: 'dataset', item: 'billing_summary', owner: 'default', consumer: 'analytics-hub',
            mode: 'snapshot', status: 'requested',
            requestedBy: 'analyst', requestedAt: now - 2 * 86_400_000,
            purpose: 'Cross-checking revenue attribution.',
            approvedBy: null, approvedAt: 0, pin: null, expiresAt: null,
        },
    ];
    for (const g of grants) store.put(SERVER_SPACE, EXCHANGE_GRANTS_COLL, g.id, g);
}
