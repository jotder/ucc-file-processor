import { MockFlags } from '../mock-flags';
import { error, json, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';

/**
 * The auth/session mock domain (W6d) — the offline half of the edition switch. It serves
 * `GET /bootstrap` so the SPA can read the edition's `authMode` with no backend, plus the
 * backend-mediated session routes (`/auth/exchange|refresh|logout`) with fabricated tokens.
 *
 * <p><b>The switch:</b> `authMode` comes from `environment.mockAuthMode` (default `'none'`), so a
 * normal offline run reports Personal → the SPA never shows a login and boots straight to the app,
 * byte-for-byte as before. Flip the dev flag (or `localStorage['inspecto.mockAuthMode']='oidc'`) to
 * `'oidc'` to exercise the whole Standard login UX offline: bootstrap advertises OIDC with
 * `auth.mock=true` (so {@link SessionService.beginLogin} grants a code locally instead of redirecting
 * to a real IAM), and the session routes below mint/clear a fake session persisted in the store.
 *
 * Gated on `mockDemo` (the master offline flag): with it on, `GET /bootstrap` always answers (Personal
 * by default), so the SPA's startup bootstrap read never errors offline.
 */

const AUTH_COLL = 'auth';
const SESSION_ID = 'session';
const MOCK_CAPABILITIES = ['canAuthorWorkbench', 'canOperateRuns', 'canTriageRequirements'];

interface MockSession {
    id: string;
    active: boolean;
}

/** Runtime override so a dev can flip auth mode without a rebuild (localStorage wins over the env flag). */
function resolveAuthMode(flags: MockFlags): 'none' | 'oidc' {
    if (typeof localStorage !== 'undefined') {
        const override = localStorage.getItem('inspecto.mockAuthMode');
        if (override === 'oidc' || override === 'none') return override;
    }
    return flags.mockAuthMode === 'oidc' ? 'oidc' : 'none';
}

export function authHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockDemo) return undefined;
        const { method, url, space } = req;
        const mode = resolveAuthMode(flags);

        if (method === 'GET' && /\/bootstrap$/.test(url)) {
            const active = mode === 'oidc' && (store.get<MockSession>(space, AUTH_COLL, SESSION_ID)?.active ?? false);
            const body: Record<string, unknown> = {
                edition: mode === 'oidc' ? 'standard' : 'personal',
                features: { authMode: mode, authoring: true, multiSpace: false },
                session: {
                    authenticated: active,
                    actor: active ? 'mock-user' : 'appUser',
                    capabilities: active ? MOCK_CAPABILITIES : [],
                },
            };
            if (mode === 'oidc') {
                body['auth'] = {
                    authorizeUrl: '',
                    clientId: 'inspecto-spa-mock',
                    scopes: 'openid profile roles',
                    mock: true, // beginLogin() grants a code locally — no real IAM offline
                };
            }
            return json(body);
        }

        if (mode !== 'oidc') return undefined; // the /auth/* routes only exist under OIDC

        if (method === 'POST' && /\/auth\/exchange$/.test(url)) {
            store.put<MockSession>(space, AUTH_COLL, SESSION_ID, { id: SESSION_ID, active: true });
            return json({ accessToken: 'mock-access-token', expiresIn: 300 });
        }
        if (method === 'POST' && /\/auth\/refresh$/.test(url)) {
            const active = store.get<MockSession>(space, AUTH_COLL, SESSION_ID)?.active ?? false;
            return active
                ? json({ accessToken: 'mock-access-token-refreshed', expiresIn: 300 })
                : error(401, 'no session');
        }
        if (method === 'POST' && /\/auth\/logout$/.test(url)) {
            store.put<MockSession>(space, AUTH_COLL, SESSION_ID, { id: SESSION_ID, active: false });
            return json({ loggedOut: true });
        }

        return undefined;
    };
}
