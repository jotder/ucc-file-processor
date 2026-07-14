import { MockFlags } from '../mock-flags';
import { json, match, MockHandler, MockRequest } from '../mock-http';

const SHARE = /\/dashboards\/([^/]+)\/share$/;

/**
 * Mint-side mock for dashboard sharing (BI-6, `POST /dashboards/{id}/share`). The real backend issues an
 * HMAC-signed token server-side; offline we cannot sign a token the public *resolve* side would accept
 * (that stays 501 — see `public-dashboards.handler.ts`), but the Share dialog only needs a token to
 * display + copy, so we mint a plausible fake here so the authoring UX is demoable with no backend.
 * Gated on `mockStudio` (the dashboards' flag) so the real HMAC-signed mint is used when mocks are off.
 */
export function dashboardShareHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest) => {
        if (!flags.mockStudio) return undefined;
        let m: string[] | null;
        if (req.method === 'POST' && (m = match(req.url, SHARE))) {
            const id = m[1];
            const ttlHours = (req.body as { ttl_hours?: number })?.ttl_hours ?? 24 * 7;
            const token = `mock-${id}-${Date.now().toString(36)}`;
            const expiresAt = new Date(Date.now() + ttlHours * 3_600_000).toISOString();
            return json({ token, url: `/public/dashboards/${token}`, dashboard: id, expiresAt });
        }
        return undefined;
    };
}
