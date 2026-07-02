import { MockStore } from './mock-store';

/**
 * Framework-free request/response shapes for mock domain handlers. The single Angular-side
 * `mockApiInterceptor` adapts HttpRequest → {@link MockRequest}, walks the registered handlers, and
 * adapts the first {@link MockResponse} back to an HttpResponse (with simulated latency). Handlers
 * therefore stay pure TS — portable, vitest-testable, and free of `@angular/*` imports.
 */
export interface MockRequest {
    method: string;
    /** URL as the interceptor sees it — BEFORE the space rewrite (mocks run ahead of spaceInterceptor). */
    url: string;
    body: unknown;
    /** Query params flattened to single values. */
    params: Record<string, string>;
    /** Active space id ('default' when single-tenant / none selected). */
    space: string;
}

export interface MockResponse {
    status?: number; // default 200
    body: unknown;
}

/** Return a response to short-circuit the request, or `undefined` to let it fall through. */
export type MockHandler = (req: MockRequest, store: MockStore) => MockResponse | undefined;

export function json(body: unknown, status = 200): MockResponse {
    return { status, body };
}

/** The real ControlApi's error envelope for mock 4xx replies. */
export function error(status: number, message: string): MockResponse {
    return { status, body: { error: message } };
}

/** Match `url` against `re`, returning DECODED capture groups (or null). Group 0 is the full match. */
export function match(url: string, re: RegExp): string[] | null {
    const m = url.match(re);
    return m ? m.map((g) => (g === undefined ? g : decodeURIComponent(g))) : null;
}
