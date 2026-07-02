import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { of, throwError, timer } from 'rxjs';
import { delay, mergeMap } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { SpacesService } from '../api/spaces.service';
import { componentsHandler } from './handlers/components.handler';
import { pipelinesHandler } from './handlers/pipelines.handler';
import { registerIntegrityRules } from './integrity';
import { MockFlags } from './mock-flags';
import { MockHandler, MockRequest } from './mock-http';
import { MockStore } from './mock-store';
import { seedDefaultSpace } from './seeds/default-space.seed';
import { localStorageAdapter } from './storage';

/**
 * THE mock backend interceptor (Wave 0, W1) — the single Angular adapter over the framework-free
 * mock layer (`MockStore` + domain handlers). Replaces the per-feature mock interceptors one domain
 * at a time; `studio-mock` and `pipeline-mock` are absorbed (the remaining feature mocks migrate
 * next, then this is the only mock registration left).
 *
 * Runs BEFORE `spaceInterceptor` (handlers see un-prefixed URLs and get the active space id passed
 * explicitly) and before `errorInterceptor` (mock 4xx replies surface through the normal error
 * channel as `HttpErrorResponse`, exactly like the real backend).
 */
const LATENCY_MS = 150;

/** The one shared, localStorage-persisted store. Exported so admin UX can offer "reset demo data". */
export const mockStore = new MockStore(localStorageAdapter());
registerIntegrityRules(mockStore);

const flags = environment as MockFlags;
const HANDLERS: MockHandler[] = [componentsHandler(flags), pipelinesHandler(flags)];

export const mockApiInterceptor: HttpInterceptorFn = (req, next) => {
    const space = inject(SpacesService).currentSpaceId() ?? 'default';
    mockStore.ensureSeeded(space, seedDefaultSpace);

    const params: Record<string, string> = {};
    for (const k of req.params.keys()) params[k] = req.params.get(k) ?? '';
    const mockReq: MockRequest = { method: req.method, url: req.url, body: req.body, params, space };

    for (const handle of HANDLERS) {
        const res = handle(mockReq, mockStore);
        if (!res) continue;
        const status = res.status ?? 200;
        if (status >= 400) {
            // Non-2xx must travel the error channel, as a real backend reply would.
            return timer(LATENCY_MS).pipe(
                mergeMap(() => throwError(() => new HttpErrorResponse({ status, error: res.body, url: req.url }))),
            );
        }
        return of(new HttpResponse({ status, body: res.body })).pipe(delay(LATENCY_MS));
    }
    return next(req);
};
