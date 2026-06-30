import { HttpEvent, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { ComponentDef } from './components.service';

/**
 * PROTOTYPE-ONLY mock for **Studio** component kinds (`dataset`/`chart`/`dashboard`). The backend
 * `ComponentStore.WRITABLE_TYPES` enum is still closed (grammar/schema/transform/sink → unknown kinds 400),
 * so Studio's new kinds are served from an in-memory store here, the same pattern as the flow / rule mocks.
 *
 * Gated on {@code environment.mockStudio}. **Registered before `flowMockInterceptor`** so it claims the
 * Studio kinds' `/components/{dataset|chart|dashboard}` routes (flow-mock's generic `/components/*` CRUD would
 * otherwise swallow them, but unseeded); grammar/schema/transform/sink fall through to flow-mock unchanged.
 * Flip the flag / remove the interceptor once the backend storage enum is widened.
 */

/** Only the Studio kinds — everything else falls through to the flow mock (which owns the registry kinds + tests). */
const STUDIO_TYPES = 'dataset|chart|dashboard';
const COMPONENT_ONE = new RegExp(`/components/(${STUDIO_TYPES})/([^/]+)$`);
const COMPONENTS = new RegExp(`/components/(${STUDIO_TYPES})$`);

const LATENCY_MS = 150;

/** In-memory Studio component store, keyed by kind, seeded with a demo dataset so `/studio/datasets` isn't empty. */
const STORE: Record<string, ComponentDef[]> = {
    dataset: [
        {
            type: 'dataset',
            name: 'cdr_sample',
            ref: 'dataset/cdr_sample',
            content: {
                name: 'cdr_sample',
                kind: 'virtual',
                sourceName: 'cdr',
                query: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: null },
                physicalRef: null,
                columns: [
                    { name: 'id', type: 'number', role: 'dimension' },
                    { name: 'msisdn', type: 'string', role: 'dimension' },
                    { name: 'duration_s', type: 'number', role: 'measure' },
                    { name: 'tariff', type: 'string', role: 'dimension' },
                    { name: 'event_time', type: 'date', role: 'temporal' },
                ],
                measures: [{ id: 'total_duration', label: 'Total duration', expression: 'sum(duration_s)' }],
                viz: null,
            },
        },
    ],
    chart: [],
    dashboard: [],
};

export const studioMockInterceptor: HttpInterceptorFn = (req, next) => {
    if (!(environment as { mockStudio?: boolean }).mockStudio) return next(req);
    const url = req.url;
    let m: RegExpMatchArray | null;

    if (req.method === 'GET' && (m = url.match(COMPONENT_ONE))) return reply(componentGet(dec(m[1]), dec(m[2])));
    if (req.method === 'PUT' && (m = url.match(COMPONENT_ONE))) return reply(componentSave(dec(m[1]), req.body, dec(m[2])));
    if (req.method === 'DELETE' && (m = url.match(COMPONENT_ONE))) return reply(componentDelete(dec(m[1]), dec(m[2])));
    if (req.method === 'GET' && (m = url.match(COMPONENTS))) return reply(componentList(dec(m[1])));
    if (req.method === 'POST' && (m = url.match(COMPONENTS))) return reply(componentSave(dec(m[1]), req.body));

    return next(req);
};

function reply<T>(body: T): Observable<HttpEvent<unknown>> {
    return of(new HttpResponse({ status: 200, body })).pipe(delay(LATENCY_MS));
}

function dec(s: string): string {
    return decodeURIComponent(s);
}

function componentList(type: string): ComponentDef[] {
    return STORE[type] ?? [];
}

function componentGet(type: string, id: string): ComponentDef | null {
    return (STORE[type] ?? []).find((d) => d.name === id) ?? null;
}

/** Create (POST, id in body) or replace (PUT, id in URL) — mirrors the real id→name split. */
function componentSave(type: string, body: unknown, idFromUrl?: string): ComponentDef {
    const content = { ...((body as Record<string, unknown>) ?? {}) };
    const name = String(idFromUrl ?? content['id'] ?? 'unnamed');
    delete content['id'];
    const def: ComponentDef = { type, name, ref: `${type}/${name}`, content };
    const list = STORE[type] ?? (STORE[type] = []);
    const i = list.findIndex((d) => d.name === name);
    if (i >= 0) list[i] = def;
    else list.push(def);
    return def;
}

function componentDelete(type: string, id: string): { deleted: boolean } {
    const list = STORE[type] ?? [];
    const i = list.findIndex((d) => d.name === id);
    if (i >= 0) list.splice(i, 1);
    return { deleted: true };
}
