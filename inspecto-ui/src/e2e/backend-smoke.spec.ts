import { describe, expect, it } from 'vitest';

/**
 * Thin end-to-end smoke against a *running* ControlApi backend serving the built SPA. It verifies the
 * real HTTP contract the UI depends on — public vs scoped routes, the bearer-token gate, the new
 * write/register routes, and that the SPA shell is served — without a browser, so there are no new
 * dependencies and CI stays fast.
 *
 * Disabled by default: it only runs when {@code E2E_BASE_URL} is set, otherwise the whole block is
 * skipped (CI is green with no backend). To run it locally, start the backend and point this at it:
 *
 *   # build the SPA, then launch ControlApi serving it (see SESSION_STATUS "Live run")
 *   E2E_BASE_URL=http://localhost:8080 E2E_TOKEN=dev npm run test:ci
 *
 * Optional env: {@code E2E_TOKEN} (a CONTROL or assist token; enables the authorized checks).
 */
const env =
  (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {};
const RAW_BASE = env['E2E_BASE_URL'];
const BASE = RAW_BASE?.replace(/\/$/, '');
const TOKEN = env['E2E_TOKEN'];

describe.skipIf(!BASE)('backend smoke (e2e)', () => {
  const url = (p: string) => `${BASE}${p}`;
  const auth = (t?: string): HeadersInit => (t ? { Authorization: `Bearer ${t}` } : {});

  it('GET /health is public and reports a status', async () => {
    const res = await fetch(url('/health'));
    expect(res.status).toBe(200);
    const body = (await res.json()) as { status?: string };
    expect(body.status).toBeTruthy();
  });

  it('GET /pipelines is locked without a token (401)', async () => {
    const res = await fetch(url('/pipelines'));
    expect(res.status).toBe(401);
  });

  it('POST /config/write is scope-gated (401 without a token)', async () => {
    const res = await fetch(url('/config/write'), { method: 'POST', body: '{}' });
    expect(res.status).toBe(401);
  });

  it('serves the SPA shell at / (text/html)', async () => {
    const res = await fetch(url('/'));
    expect(res.status).toBe(200);
    expect((res.headers.get('content-type') ?? '')).toContain('text/html');
  });

  it.skipIf(!TOKEN)('GET /pipelines returns a list with a valid token', async () => {
    const res = await fetch(url('/pipelines'), { headers: auth(TOKEN) });
    expect(res.status).toBe(200);
    expect(Array.isArray(await res.json())).toBe(true);
  });
});
