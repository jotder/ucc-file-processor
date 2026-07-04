import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { of, throwError, timer } from 'rxjs';
import { delay, mergeMap } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { SpacesService } from '../api/spaces.service';
import { componentsHandler } from './handlers/components.handler';
import { connectionsHandler } from './handlers/connections.handler';
import { demoHandler } from './handlers/demo.handler';
import { decisionRulesHandler } from './handlers/decision-rules.handler';
import { expectationsHandler } from './handlers/expectations.handler';
import { jobsHandler } from './handlers/jobs.handler';
import { opsHandler } from './handlers/ops.handler';
import { pipelinesHandler } from './handlers/pipelines.handler';
import { spacesHandler } from './handlers/spaces.handler';
import { registerIntegrityRules } from './integrity';
import { MockFlags } from './mock-flags';
import { MockHandler, MockRequest } from './mock-http';
import { MockStore } from './mock-store';
import { seedDefaultSpace } from './seeds/default-space.seed';
import { maybeTick } from './simulator';
import { localStorageAdapter } from './storage';

/**
 * THE mock backend interceptor (Wave 0→1, W1) — the single Angular adapter over the framework-free
 * mock layer (`MockStore` + domain handlers). All six feature mocks are absorbed (studio, pipeline,
 * demo, connection, ops, jobs); this is the only mock registration left besides `spaceInterceptor`.
 *
 * Runs BEFORE `spaceInterceptor` (handlers see un-prefixed URLs and get the active space id passed
 * explicitly) and before `errorInterceptor` (mock 4xx replies surface through the normal error
 * channel as `HttpErrorResponse`, exactly like the real backend). Every intercepted request also
 * gives the liveness simulator a chance to tick (rate-limited), so Runs/Events/Alerts keep moving.
 */
const LATENCY_MS = 150;

/** The one shared, localStorage-persisted store. Exported so admin UX can offer "reset demo data". */
export const mockStore = new MockStore(localStorageAdapter());
registerIntegrityRules(mockStore);

const flags = environment as MockFlags;
// Order preserves the old interceptor-chain precedence: demo → connections → components/pipelines → ops → jobs.
const HANDLERS: MockHandler[] = [
    spacesHandler(flags), // server-global /spaces — ahead of the per-space domains
    demoHandler(flags),
    connectionsHandler(flags),
    componentsHandler(flags),
    pipelinesHandler(flags),
    opsHandler(flags),
    expectationsHandler(flags),
    decisionRulesHandler(flags),
    jobsHandler(flags),
];

export const mockApiInterceptor: HttpInterceptorFn = (req, next) => {
    const space = inject(SpacesService).currentSpaceId() ?? 'default';
    mockStore.ensureSeeded(space, seedDefaultSpace);
    maybeTick(mockStore, space, flags);

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
