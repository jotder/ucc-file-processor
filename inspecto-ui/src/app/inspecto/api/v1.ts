/**
 * TS mirror of the `/api/v1` envelope contract (`docs/api/openapi-v1.json`, design
 * `docs/superpower/api-contract-design.md` §4–5). The `v1Interceptor` unwraps success envelopes at
 * the HttpClient seam, so feature services keep their plain DTO signatures — these types surface
 * only at the interceptor, `apiErrorMessage`, and the mock layer's response edge.
 */

/** Machine-readable error codes — kept in lockstep with `com.gamma.control.ErrorCodes` (backend-pinned by ApiContractTest). */
export type V1ErrorCode =
    | 'MALFORMED_REQUEST'
    | 'NOT_FOUND'
    | 'METHOD_NOT_ALLOWED'
    | 'PATH_JAIL_VIOLATION'
    | 'CONFLICT'
    | 'CONFLICT_STALE_VERSION'
    | 'CONFIG_VALIDATION_FAILED'
    | 'INTERNAL'
    | 'CONTROL_PLANE_READ_ONLY'
    | 'CAPABILITY_UNAVAILABLE'
    | 'UNAUTHENTICATED'
    | 'PERMISSION_DENIED';

export interface V1EnvelopeMetadata {
    timestamp: string;
    apiVersion: 'v1';
    durationMs?: number;
    etag?: string;
    pagination?: { cursor?: string; nextCursor?: string; pageSize?: number; total?: number };
    warnings?: { code: string; message: string; sunset?: string }[];
}

/** The v1 success envelope. `permissions` is present only when the security module attached a Subject (Standard). */
export interface V1Envelope<T = unknown> {
    data: T;
    metadata: V1EnvelopeMetadata;
    links?: { self?: string; related?: Record<string, string> };
    permissions?: string[];
    diagnostics: { correlationId: string };
}

/** The structured v1 error, carried as `{ error: V1ErrorObject }` in non-2xx bodies. */
export interface V1ErrorObject {
    errorCode: V1ErrorCode;
    message: string;
    technicalMessage?: string;
    recoverable: boolean;
    suggestedAction?: string;
    documentation?: string;
    correlationId: string;
    details?: Record<string, unknown>;
}

/**
 * Shape guard for the unwrap seam: only bodies that are unmistakably a v1 success envelope are
 * unwrapped, so text (Prometheus /metrics), blobs, 304 empty bodies, and legacy JSON pass through.
 */
export function isV1Envelope(body: unknown): body is V1Envelope {
    if (body === null || typeof body !== 'object') return false;
    const b = body as Record<string, unknown>;
    return 'data' in b && (b['metadata'] as V1EnvelopeMetadata | undefined)?.apiVersion === 'v1';
}
