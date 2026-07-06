package com.gamma.control;

/**
 * Machine-readable error codes for the v1 error object (docs/superpower/api-contract-design.md §5).
 * A throwing site may pick a specific code via {@link ApiException}; every other error gets the
 * status-derived default. Codes are part of the v1 contract: additive only, never renamed.
 */
final class ErrorCodes {
    private ErrorCodes() {}

    static final String MALFORMED_REQUEST        = "MALFORMED_REQUEST";
    static final String NOT_FOUND                = "NOT_FOUND";
    static final String METHOD_NOT_ALLOWED       = "METHOD_NOT_ALLOWED";
    static final String PATH_JAIL_VIOLATION      = "PATH_JAIL_VIOLATION";
    static final String CONFLICT                 = "CONFLICT";
    /** 409 — an {@code If-Match} write precondition failed (optimistic-lock, W3). */
    static final String CONFLICT_STALE_VERSION   = "CONFLICT_STALE_VERSION";
    static final String CONFIG_VALIDATION_FAILED = "CONFIG_VALIDATION_FAILED";
    static final String INTERNAL                 = "INTERNAL";
    /** 503 — config writes are disabled ({@code -Dassist.write.root} unset). */
    static final String CONTROL_PLANE_READ_ONLY  = "CONTROL_PLANE_READ_ONLY";
    /** 503 — an optional module (e.g. the assist agent) is not on the classpath. */
    static final String CAPABILITY_UNAVAILABLE   = "CAPABILITY_UNAVAILABLE";

    /** The contract's default code for a status ({@code errorCode} is never absent on a v1 error). */
    static String defaultFor(int status) {
        return switch (status) {
            case 400 -> MALFORMED_REQUEST;
            case 403 -> PATH_JAIL_VIOLATION;   // the core's only 403; the security module adds PERMISSION_DENIED
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 422 -> CONFIG_VALIDATION_FAILED;
            case 503 -> CAPABILITY_UNAVAILABLE;
            default  -> INTERNAL;
        };
    }
}
