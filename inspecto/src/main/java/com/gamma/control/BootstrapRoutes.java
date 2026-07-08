package com.gamma.control;

import com.gamma.config.spec.ConfigSpecs;
import com.gamma.service.SpaceContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform bootstrap ({@code GET /bootstrap}; W3, design §6.1): the one cached, ETag'd call that
 * hands the UI everything the <b>backend authoritatively owns</b> to start operating — edition +
 * feature flags, every config spec (folding in {@code GET /config/spec/{type}}), the canonical
 * platform enumerations, the Space list, and a session stub. Replaces today's N× config-spec +
 * {@code /spaces} + {@code /spaces/_meta} round-trips with one (guidelines 3/5).
 *
 * <p><b>Boundary (stated deviation from §6.1).</b> The ComponentKind registry, the Visualization
 * Type registry, the {@code $}-parameter definitions and theme/icons are compile-time constants in
 * the SPA, <em>not</em> backend config — the backend has no authority over them and does not ship
 * them (that would invent a sync problem); the UI merges them client-side. Session
 * {@code capabilities} come from the authenticated {@link Subject}'s resolved grants (W6); the
 * auth-free core has no {@code Subject} (no {@link Authenticator} is ever present there), so
 * {@code authenticated} stays {@code false} and {@code capabilities} empty — Personal edition,
 * unchanged. {@code /bootstrap} itself stays public even on Standard (it is how the SPA discovers it
 * needs to start the OIDC redirect). The response is space-agnostic; a per-space bootstrap (dataset
 * descriptors, lookup values, per-Lens navigation) is a follow-on once those become backend-owned.
 */
final class BootstrapRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/bootstrap", (e, m) -> bootstrap(api, e));
    }

    private Object bootstrap(ApiContext api, HttpExchange ex) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("edition", edition());
        data.put("features", features(api));
        data.put("configSpecs", configSpecs());
        data.put("enumerations", enumerations());
        data.put("spaces", spaces(api));
        data.put("session", session(ex));

        String etag = ETags.of(ContentHash.of(data));
        if (ETags.isFresh(ex, etag)) return ETags.notModified(ex, etag);
        ETags.set(ex, etag);
        return data;
    }

    /** Auth-free core = Personal; the Standard build (security module + {@code -Dauth.mode=oidc}) reports itself. */
    private static String edition() {
        return "none".equalsIgnoreCase(System.getProperty("auth.mode", "none")) ? "personal" : "standard";
    }

    private static Map<String, Object> features(ApiContext api) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("authoring", api.writeRoot() != null);      // write-root set ⇒ config authoring enabled
        f.put("multiSpace", api.spaces().supportsCrud());
        f.put("exchange", api.spaces().containerRoot() != null);   // cross-space sharing needs -Dspaces.root
        f.put("authMode", System.getProperty("auth.mode", "none"));
        return f;
    }

    /** Every config spec in one payload (the {@code GET /config/spec/{type}} calls folded together). */
    private static Map<String, Object> configSpecs() {
        Map<String, Object> specs = new LinkedHashMap<>();
        for (String type : ConfigSpecs.TYPES) specs.put(type, ConfigSpecs.forType(type));
        return specs;
    }

    /** Canonical platform enumerations (mirror the binding GLOSSARY lists; the R4 severity ladder). */
    private static Map<String, Object> enumerations() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("severities", List.of("trace", "debug", "info", "warn", "error", "critical"));
        e.put("attributeTypes", List.of("string", "integer", "decimal", "boolean", "date",
                "datetime", "time", "currency", "enum", "array", "object"));
        e.put("outputFormats", List.of("CSV", "PARQUET"));
        return e;
    }

    private Object spaces(ApiContext api) {
        return api.spaces().all().stream()
                .sorted(Comparator.comparing(c -> c.id().value()))
                .map(BootstrapRoutes::spaceEntry)
                .toList();
    }

    private static Map<String, Object> spaceEntry(SpaceContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ctx.id().value());
        m.put("displayName", ctx.manifest().displayName());
        return m;
    }

    /** {@code authenticated}/{@code capabilities} reflect the {@link Subject} the security module resolved
     *  for this request (W6); absent on Personal edition, where this is still the honor-system stub. */
    private static Map<String, Object> session(HttpExchange ex) {
        Map<String, Object> s = new LinkedHashMap<>();
        var subject = ApiContext.subject(ex);
        s.put("authenticated", subject.isPresent());
        s.put("actor", ApiContext.actor(ex));
        s.put("capabilities", subject.map(sub -> List.copyOf(sub.capabilities())).orElse(List.of()));
        return s;
    }
}
