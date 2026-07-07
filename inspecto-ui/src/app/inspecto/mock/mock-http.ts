import { V1Envelope, V1ErrorCode, V1ErrorObject } from '../api/v1';
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

/** The legacy `{error: msg}` body handlers return for 4xx — lifted into the v1 ErrorObject at the
 *  interceptor's response edge ({@link v1ErrorBody}), exactly like the backend's `Envelope.error()`. */
export function error(status: number, message: string): MockResponse {
    return { status, body: { error: message } };
}

/** Port of the backend `ErrorCodes.defaultFor(status)` map (403 = the core's structural PATH_JAIL_VIOLATION). */
function defaultErrorCode(status: number): V1ErrorCode {
    switch (status) {
        case 400: return 'MALFORMED_REQUEST';
        case 401: return 'UNAUTHENTICATED';
        case 403: return 'PATH_JAIL_VIOLATION';
        case 404: return 'NOT_FOUND';
        case 405: return 'METHOD_NOT_ALLOWED';
        case 409: return 'CONFLICT';
        case 422: return 'CONFIG_VALIDATION_FAILED';
        case 503: return 'CAPABILITY_UNAVAILABLE';
        default: return 'INTERNAL';
    }
}

let mockCorrelationSeq = 0;

/** Wrap a handler's raw DTO in the v1 success envelope — the mock mirror of `Envelope.success()`. */
export function v1SuccessBody(body: unknown): V1Envelope {
    return {
        data: body,
        metadata: { timestamp: new Date().toISOString(), apiVersion: 'v1' },
        diagnostics: { correlationId: `mock-${++mockCorrelationSeq}` },
    };
}

/** Lift a handler's legacy `{error: msg, …extras}` body into `{error: V1ErrorObject}` — the mock
 *  mirror of `Envelope.error()`; extra keys (e.g. 422 findings) are preserved under `details`. */
export function v1ErrorBody(status: number, body: unknown): { error: V1ErrorObject } {
    let message: string;
    let details: Record<string, unknown> | undefined;
    if (body !== null && typeof body === 'object') {
        const { error: msg, ...rest } = body as Record<string, unknown>;
        message = msg !== undefined ? String(msg) : String(body);
        if (Object.keys(rest).length) details = rest;
    } else {
        message = String(body);
    }
    return {
        error: {
            errorCode: defaultErrorCode(status),
            message,
            recoverable: status !== 500,
            correlationId: `mock-${++mockCorrelationSeq}`,
            ...(details ? { details } : {}),
        },
    };
}

/** Match `url` against `re`, returning DECODED capture groups (or null). Group 0 is the full match. */
export function match(url: string, re: RegExp): string[] | null {
    const m = url.match(re);
    return m ? m.map((g) => (g === undefined ? g : decodeURIComponent(g))) : null;
}
