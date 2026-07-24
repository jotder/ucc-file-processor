package com.gamma.control;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * Conditional-request plumbing for versioned metadata resources (W3, design §4/§7): a strong ETag
 * carrying the resource's {@link ContentHash}, {@code If-None-Match} (→ 304 on a read) and
 * {@code If-Match} (→ 409 {@code CONFLICT_STALE_VERSION} on a write). HTTP caching + optimistic
 * locking off the one hash — no new versioning mechanism.
 */
final class ETags {

    private ETags() {}

    /** The strong ETag for a content hash: {@code "sha256:<hex>"}. */
    static String of(String contentHash) {
        return "\"sha256:" + contentHash + "\"";
    }

    /** Set the ETag response header (before the body is written — same ordering as CORS). */
    static void set(HttpExchange ex, String etag) {
        ex.getResponseHeaders().set("ETag", etag);
    }

    /** True when the caller's {@code If-None-Match} already holds {@code etag} (⇒ the handler should 304). */
    static boolean isFresh(HttpExchange ex, String etag) {
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        return inm != null && (inm.trim().equals("*") || matches(inm, etag));
    }

    /** Enforce {@code If-Match} on a write: a present, non-matching precondition is a stale-version 409. */
    static void requireMatch(HttpExchange ex, String currentEtag) {
        String im = ex.getRequestHeaders().getFirst("If-Match");
        if (im != null && !im.trim().equals("*") && !matches(im, currentEtag))
            throw new ApiException(409, ErrorCodes.CONFLICT_STALE_VERSION,
                    "resource changed since it was read (If-Match precondition failed); re-read and retry");
    }

    /** Write a bodiless {@code 304 Not Modified} carrying the ETag; returns {@link ApiContext#HANDLED}. */
    static Object notModified(HttpExchange ex, String etag) throws IOException {
        set(ex, etag);
        ex.sendResponseHeaders(304, -1);
        return ApiContext.HANDLED;
    }

    /**
     * Conditional-GET in one call: derive a strong {@link ContentHash} ETag for {@code body}, honour
     * {@code If-None-Match} (→ a bodiless 304 via {@link #notModified}), otherwise set the header and
     * return {@code body} unchanged for normal serialization. Collapses the three-line read-side idiom
     * ({@link #of}/{@link #isFresh}/{@link #set}) that {@code /bootstrap} and {@code GET
     * /components/{type}/{id}} spell out. Read-side only — writes still call {@link #requireMatch}.
     * The hash captures any body variance (e.g. a per-subject field), so a changed body can never 304.
     */
    static Object respond(HttpExchange ex, Object body) throws IOException {
        String etag = of(ContentHash.of(body));
        if (isFresh(ex, etag)) return notModified(ex, etag);
        set(ex, etag);
        return body;
    }

    /** An {@code If-*} header may be a comma-separated tag list; match any (a weak {@code W/} prefix is ignored). */
    private static boolean matches(String header, String etag) {
        for (String tag : header.split(",")) {
            String t = tag.trim();
            if (t.startsWith("W/")) t = t.substring(2);
            if (t.equals(etag)) return true;
        }
        return false;
    }
}
