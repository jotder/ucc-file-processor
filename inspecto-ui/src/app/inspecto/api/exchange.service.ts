import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/**
 * The Exchange — cross-Space Dataset/Widget sharing (backend: `ExchangeRoutes`, design
 * `docs/superpower/storage-layout-and-sharing-plan.md` §3). All routes are installation-scope and
 * un-prefixed (like `/spaces`): the {@link spaceInterceptor} exempts `/exchange` so calls are never
 * rewritten into a space. The whole surface only exists on a multi-space runtime — gate UI on
 * `SessionService.exchangeEnabled` (from `bootstrap.features.exchange`); every route 409s otherwise.
 */

/** A published snapshot's freshness (`SnapshotMeta.toMap()`), merged into offers when one exists. */
export interface ExchangeFreshness {
    version: string;
    rows: number;
    refreshedAt: string;
    columns: Array<Record<string, unknown>>;
}

/** One shareable-catalog entry (`Offer.toMap()` + optional freshness). */
export interface ExchangeOffer {
    kind: 'dataset' | 'widget';
    item: string;
    owner: string;
    description: string;
    /** Column/shape metadata the owner published with the offer (never rows). */
    resultSet: Record<string, unknown>;
    offeredBy: string;
    offeredAt: number;
    /** Widget offers only: the bound dataset whose grant travels with the widget. */
    dataset: string | null;
    freshness?: ExchangeFreshness;
}

/** One grant of read-only use, owner→consumer (`ShareGrant.toMap()`). */
export interface ExchangeGrant {
    /** Deterministic: `<consumer>~<owner>~<kind>~<item>`. */
    id: string;
    kind: 'dataset' | 'widget';
    item: string;
    owner: string;
    consumer: string;
    mode: 'snapshot' | 'live';
    status: 'requested' | 'active' | 'denied' | 'revoked' | 'expired';
    requestedBy: string;
    requestedAt: number;
    purpose: string;
    approvedBy: string | null;
    approvedAt: number;
    /** Version pin ("v12"); null = track current. */
    pin: string | null;
    /** Epoch millis; null = no expiry. */
    expiresAt: number | null;
}

/** GET /exchange/datasets/{owner}/{item} — the offer + freshness + (if `consumer` given) its grant. */
export interface ExchangeDatasetMeta extends ExchangeOffer {
    grant?: ExchangeGrant;
}

/** GET /exchange/widgets/{owner}/{item}?consumer= — render-only view of a shared Widget. */
export interface SharedWidgetRender {
    owner: string;
    item: string;
    content: Record<string, unknown>;
    readOnly: true;
    /** The `shared/<owner>/<dataset>` ref the consumer binds through. */
    dataset?: string;
}

/** Wraps the `/exchange/*` routes. Stateless — grant/offer lists are per-view, not app-global. */
@Injectable({ providedIn: 'root' })
export class ExchangeService {
    private http = inject(HttpClient);

    /** The shareable catalog (metadata only), optionally filtered to one owner Space. */
    offers(owner?: string): Observable<ExchangeOffer[]> {
        return this.http.get<ExchangeOffer[]>(apiUrl('/exchange/offers'), {
            params: toParams({ owner: owner ?? '' }),
        });
    }

    /** Owner lists (or re-describes) a Dataset/Widget offer. 404 unknown item, 409/422 widget pairing. */
    offer(req: {
        kind: 'dataset' | 'widget';
        owner: string;
        item: string;
        description?: string;
        resultSet?: Record<string, unknown>;
    }): Observable<ExchangeOffer> {
        return this.http.post<ExchangeOffer>(apiUrl('/exchange/offers'), req);
    }

    /** Owner republishes an offered Dataset's Exchange snapshot from its current data. */
    refresh(owner: string, item: string): Observable<ExchangeFreshness> {
        return this.http.post<ExchangeFreshness>(apiUrl('/exchange/refresh'), { owner, item });
    }

    /** Consumer requests use of an offered item. 400 self-request, 404 no offer, 409 conflict. */
    request(req: {
        kind: 'dataset' | 'widget';
        owner: string;
        consumer: string;
        item: string;
        purpose?: string;
        mode?: 'snapshot' | 'live';
    }): Observable<ExchangeGrant> {
        return this.http.post<ExchangeGrant>(apiUrl('/exchange/requests'), req);
    }

    /** The grant ledger — all grants, or those where `space` is owner or consumer. */
    grants(space?: string): Observable<ExchangeGrant[]> {
        return this.http.get<ExchangeGrant[]>(apiUrl('/exchange/grants'), {
            params: toParams({ space: space ?? '' }),
        });
    }

    /** Owner acts on a grant. 404 unknown, 409 wrong state. */
    actOnGrant(id: string, action: 'approve' | 'deny' | 'revoke'): Observable<ExchangeGrant> {
        return this.http.post<ExchangeGrant>(
            apiUrl(`/exchange/grants/${encodeURIComponent(id)}/${action}`),
            {},
        );
    }

    /** Consumer pins its grant to a snapshot version (null/'' clears — back to tracking current). */
    pin(id: string, version: string | null): Observable<ExchangeGrant> {
        return this.http.post<ExchangeGrant>(apiUrl(`/exchange/grants/${encodeURIComponent(id)}/pin`), {
            version,
        });
    }

    /** Owner sets/clears a grant's expiry (epoch millis; null clears). */
    expiry(id: string, expiresAt: number | null): Observable<ExchangeGrant> {
        return this.http.post<ExchangeGrant>(
            apiUrl(`/exchange/grants/${encodeURIComponent(id)}/expiry`),
            { expiresAt },
        );
    }

    /** One offered dataset's metadata (+ the caller's grant when `consumer` is given). */
    dataset(owner: string, item: string, consumer?: string): Observable<ExchangeDatasetMeta> {
        return this.http.get<ExchangeDatasetMeta>(
            apiUrl(`/exchange/datasets/${encodeURIComponent(owner)}/${encodeURIComponent(item)}`),
            { params: toParams({ consumer: consumer ?? '' }) },
        );
    }

    /** Render-only view of a shared Widget for a consumer. 403 = grant revoked/expired (fail-closed). */
    widget(owner: string, item: string, consumer: string): Observable<SharedWidgetRender> {
        return this.http.get<SharedWidgetRender>(
            apiUrl(`/exchange/widgets/${encodeURIComponent(owner)}/${encodeURIComponent(item)}`),
            { params: toParams({ consumer }) },
        );
    }
}
