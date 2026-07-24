import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { of, throwError, timer } from 'rxjs';
import { delay, mergeMap } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { SpacesService } from '../api/spaces.service';
import { accessHandler } from './handlers/access.handler';
import { assistHandler } from './handlers/assist.handler';
import { authHandler } from './handlers/auth.handler';
import { componentsHandler } from './handlers/components.handler';
import { connectionsHandler } from './handlers/connections.handler';
import { dbBrowserHandler } from './handlers/db-browser.handler';
import { demoHandler } from './handlers/demo.handler';
import { decisionRulesHandler } from './handlers/decision-rules.handler';
import { exchangeHandler } from './handlers/exchange.handler';
import { expectationsHandler } from './handlers/expectations.handler';
import { geoHandler } from './handlers/geo.handler';
import { healthHandler } from './handlers/health.handler';
import { invHandler } from './handlers/inv.handler';
import { jobsHandler } from './handlers/jobs.handler';
import { navHandler } from './handlers/nav.handler';
import { onboardingHandler } from './handlers/onboarding.handler';
import { opsHandler } from './handlers/ops.handler';
import { pipelinesHandler } from './handlers/pipelines.handler';
import { requirementsHandler } from './handlers/requirements.handler';
import { settingsHandler } from './handlers/settings.handler';
import { biTemplatesHandler } from './handlers/bi-templates.handler';
import { dashboardShareHandler } from './handlers/dashboard-share.handler';
import { publicDashboardsHandler } from './handlers/public-dashboards.handler';
import { spacesHandler } from './handlers/spaces.handler';
import { registerIntegrityRules } from './integrity';
import { MockFlags } from './mock-flags';
import { MockHandler, MockRequest, v1ErrorBody, v1SuccessBody } from './mock-http';
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
    authHandler(flags), // server-global /bootstrap + /auth/* (W6d edition switch) — ahead of everything
    spacesHandler(flags), // server-global /spaces — ahead of the per-space domains
    exchangeHandler(flags), // installation-scope /exchange/* (cross-space sharing) — also un-prefixed
    dbBrowserHandler(flags), // /db/* raw table browser — ahead of demoHandler (its /catalog$ regex also matches /db/catalog)
    accessHandler(flags), // /access/* lens access config — same /catalog$ collision, so also ahead of demoHandler
    demoHandler(flags),
    onboardingHandler(flags), // /config/write|preview|pipeline/{name} + POST /runs — after demo's /config/spec + /validate
    connectionsHandler(flags),
    componentsHandler(flags),
    pipelinesHandler(flags),
    opsHandler(flags),
    expectationsHandler(flags),
    invHandler(flags),
    geoHandler(flags),
    dashboardShareHandler(flags), // mint side of BI-6 share (POST /dashboards/{id}/share)
    publicDashboardsHandler(flags),
    biTemplatesHandler(flags),
    requirementsHandler(flags),
    decisionRulesHandler(flags),
    healthHandler(flags), // /health/details only — the bare /health probe stays real (connectivity banner)
    jobsHandler(flags),
    assistHandler(flags),
    settingsHandler(flags),
    navHandler(flags), // /nav/menus singleton — Menu Builder + custom sidebar offline (mockDemo)
];

export const mockApiInterceptor: HttpInterceptorFn = (req, next) => {
    const space = inject(SpacesService).currentSpaceId() ?? 'default';
    mockStore.ensureSeeded(space, seedDefaultSpace);
    maybeTick(mockStore, space, flags);

    const params: Record<string, string> = {};
    for (const k of req.params.keys()) params[k] = req.params.get(k) ?? '';
    const mockReq: MockRequest = { method: req.method, url: req.url, body: req.body, params, space };

    // Mirror of the backend's dispatch seam (W7): a `/api/v1` request gets its handler result shaped
    // into the v1 envelope / ErrorObject at this response edge — handlers keep returning raw DTOs,
    // exactly like backend route handlers do under `ApiContext.respondJson`.
    const isV1 = req.url.startsWith(`${environment.apiBaseUrl}/v1/`);
    for (const handle of HANDLERS) {
        const res = handle(mockReq, mockStore);
        if (!res) continue;
        const status = res.status ?? 200;
        if (status >= 400) {
            // Non-2xx must travel the error channel, as a real backend reply would.
            const error = isV1 ? v1ErrorBody(status, res.body) : res.body;
            return timer(LATENCY_MS).pipe(
                mergeMap(() => throwError(() => new HttpErrorResponse({ status, error, url: req.url }))),
            );
        }
        const body = isV1 ? v1SuccessBody(res.body) : res.body;
        return of(new HttpResponse({ status, body })).pipe(delay(LATENCY_MS));
    }
    return next(req);
};
