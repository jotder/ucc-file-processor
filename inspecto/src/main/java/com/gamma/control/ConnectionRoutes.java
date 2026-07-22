package com.gamma.control;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.ConnectionProber;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionTester;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.ConnectionWorkbench.ProbeCheck;
import com.gamma.config.io.ConfigCodec;
import com.gamma.util.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Connection-profile CRUD + reachability test ({@code /connections*}). Extracted verbatim from
 * {@link ControlApi}: identical routes, HTTP statuses and secret-masking behaviour — only the home
 * changed. Persistence mirrors the flow/component/view stores (atomic write under the write root).
 */
final class ConnectionRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRoutes.class);

    @Override
    public void register(ApiContext api) {
        api.get("/connections", (e, m) -> connectionList(api));
        // Writes require canOnboardConnections — its own Admin-only grant (rbac-groundwork §3/§4.1 Q1,
        // product sign-off 2026-07-22), NOT canAuthorWorkbench: Connections are the credential/egress
        // surface, so onboarding them is distinct from authoring pipelines/components. A no-op on
        // Personal — no Subject is ever attached there.
        api.post("/connections", ApiContext.withCapability("canOnboardConnections", (e, m) -> createConnection(api, api.body(e))));
        api.post("/connections/([^/]+)/test", (e, m) -> testConnection(api, ApiContext.name(m)));
        // The connection-workbench verbs (graded probe · explore · sample) — read-only network probes with
        // no persistence, so no capability gate, same as /test. Backed by ConnectionWorkbench implementations;
        // connectors without one probe as "skipped" and 501 on explore/sample (fail honest, not fabricated).
        api.post("/connections/([^/]+)/probe", (e, m) -> probeConnection(api, ApiContext.name(m), api.body(e)));
        api.get("/connections/([^/]+)/explore", (e, m) -> exploreConnection(api, ApiContext.name(m), ApiContext.query(e, "path")));
        api.get("/connections/([^/]+)/sample", (e, m) -> sampleConnection(api, ApiContext.name(m),
                ApiContext.query(e, "path"), ApiContext.query(e, "limit")));
        // Test an UNSAVED profile straight from the create/edit form — no persistence, no capability gate
        // (a read-only network probe, same as the saved-profile test above). ?target= connection|tunnel|proxy
        // selects which hop to probe.
        api.post("/connections/test", (e, m) -> testUnsavedProfile(api.body(e), ApiContext.query(e, "target")));
        api.put("/connections/([^/]+)", ApiContext.withCapability("canOnboardConnections", (e, m) -> updateConnection(api, ApiContext.name(m), api.body(e))));
        api.delete("/connections/([^/]+)", ApiContext.withCapability("canOnboardConnections", (e, m) -> deleteConnection(api, ApiContext.name(m))));
        api.get("/connections/([^/]+)", (e, m) -> connectionById(api, ApiContext.name(m)));
    }

    /** {@code GET /connections} — all connection profiles, secret-masked. */
    private Object connectionList(ApiContext api) {
        return api.service().connections().values().stream().map(ConnectionProfile::toMap).toList();
    }

    /** {@code GET /connections/{id}} — one connection profile (secret-masked); 404 if unknown. */
    private Object connectionById(ApiContext api, String id) {
        return api.service().connection(id)
                .map(ConnectionProfile::toMap)
                .orElseThrow(() -> new ApiException(404, "no connection profile '" + id + "'"));
    }

    /** {@code POST /connections/{id}/test} — TCP-reachability + secret-resolution test; 404 if unknown. */
    private Object testConnection(ApiContext api, String id) {
        ConnectionProfile p = api.service().connection(id)
                .orElseThrow(() -> new ApiException(404, "no connection profile '" + id + "'"));
        return ConnectionTester.test(p).toMap();
    }

    /**
     * {@code POST /connections/test?target=connection|tunnel|proxy} — TCP-reachability + secret-resolution test
     * of a profile that hasn't been saved yet (the create/edit form's "Test connection/tunnel/proxy" actions).
     * {@code target=connection} always probes the target host directly, even when a tunnel is configured
     * (unlike the saved-profile test, which prioritises the tunnel hop — {@link ConnectionProfile#testEndpoint()});
     * {@code target=tunnel} requires a tunnel block and probes its host; {@code target=proxy} requires a proxy
     * block and probes its host (reachability of the proxy hop itself — connectors don't dial through it yet).
     */
    private Object testUnsavedProfile(Map<String, Object> body, String target) {
        ConnectionProfile p = connectionFromBody(
                ApiContext.str(body, "id") == null || ApiContext.str(body, "id").isBlank()
                        ? "unsaved-test" : ApiContext.str(body, "id"), body, null);
        String t = target == null || target.isBlank() ? "connection" : target;
        return switch (t) {
            case "connection" -> ConnectionTester.test(new ConnectionProfile(p.id(), p.connector(), p.host(),
                    p.port(), p.database(), p.basePath(), p.username(), p.password(), p.options(), null)).toMap();
            case "tunnel" -> {
                if (p.tunnel() == null || p.tunnel().host() == null || p.tunnel().host().isBlank())
                    throw new ApiException(422, "no tunnel configured to test");
                yield ConnectionTester.test(p).toMap();
            }
            case "proxy" -> {
                if (p.proxy() == null || p.proxy().host() == null || p.proxy().host().isBlank())
                    throw new ApiException(422, "no proxy configured to test");
                yield ConnectionTester.test(new ConnectionProfile(p.id(), p.connector(), p.proxy().host(),
                        p.proxy().port(), null, null, p.proxy().username(), p.proxy().password(),
                        p.options(), null)).toMap();
            }
            default -> throw new ApiException(422, "unsupported test target '" + t + "' (connection|tunnel|proxy)");
        };
    }

    /**
     * {@code POST /connections/{id}/probe} — graded probe (reachability/authenticate/read/write/list) of a
     * saved profile. Body: {@code {checks?: string[], sampleLimit?: number}}; omitted checks = all five.
     * 404 unknown id, 422 unknown check name. Checks a connector cannot answer come back {@code skipped}.
     */
    private Object probeConnection(ApiContext api, String id, Map<String, Object> body) {
        ConnectionProfile p = api.service().connection(id)
                .orElseThrow(() -> new ApiException(404, "no connection profile '" + id + "'"));
        EnumSet<ProbeCheck> checks = EnumSet.noneOf(ProbeCheck.class);
        if (body != null && body.get("checks") instanceof List<?> names) {
            for (Object n : names) {
                try {
                    checks.add(ProbeCheck.fromWire(String.valueOf(n)));
                } catch (IllegalArgumentException ex) {
                    throw new ApiException(422, "unknown probe check '" + n
                            + "' (reachability|authenticate|read|write|list)");
                }
            }
        }
        return ConnectionProber.probe(p, checks, intOf(body == null ? null : body.get("sampleLimit"), 25));
    }

    /** {@code GET /connections/{id}/explore?path=} — children of {@code path} (the root when omitted). */
    private Object exploreConnection(ApiContext api, String id, String path) {
        try (ConnectionWorkbench wb = workbench(api, id)) {
            return ConnectionWorkbench.ResourceNode.toMaps(wb.explore(path));
        } catch (AcquisitionException e) {
            throw new ApiException(502, "explore failed: " + e.getMessage());
        } catch (ConnectionWorkbench.PathEscape e) {
            throw new ApiException(403, e.getMessage());
        } catch (ConnectionWorkbench.NoSuchPath e) {
            throw new ApiException(404, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    /** {@code GET /connections/{id}/sample?path=&limit=} — bounded preview of the file at {@code path}. */
    private Object sampleConnection(ApiContext api, String id, String path, String limit) {
        if (path == null || path.isBlank()) throw new ApiException(400, "query param 'path' is required");
        try (ConnectionWorkbench wb = workbench(api, id)) {
            return wb.sample(path, intOf(limit, 50)).toMap();
        } catch (AcquisitionException e) {
            throw new ApiException(502, "sample failed: " + e.getMessage());
        } catch (ConnectionWorkbench.PathEscape e) {
            throw new ApiException(403, e.getMessage());
        } catch (ConnectionWorkbench.NoSuchPath e) {
            throw new ApiException(404, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    /** Resolve the workbench for a saved profile: 404 unknown id, 501 connector without workbench support. */
    private ConnectionWorkbench workbench(ApiContext api, String id) {
        ConnectionProfile p = api.service().connection(id)
                .orElseThrow(() -> new ApiException(404, "no connection profile '" + id + "'"));
        ConnectionWorkbench wb = ConnectionProber.workbenchFor(p);
        if (wb == null) throw new ApiException(501,
                "explore/sample not supported for connector '" + p.connector() + "' yet");
        return wb;
    }

    /** Clamped int of a query/body value ({@code [1,500]}), or {@code dflt} when absent/garbled. */
    private static int intOf(Object v, int dflt) {
        if (v == null) return dflt;
        try {
            int n = v instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(v).trim());
            return Math.max(1, Math.min(500, n));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** {@code POST /connections} — create a new connection profile (write-root gated); 409 if the id exists. */
    private Object createConnection(ApiContext api, Map<String, Object> body) throws IOException {
        WriteGates.requireWriteRoot(api, "connection write");
        String id = ApiContext.str(body, "id");
        if (id == null) throw new ApiException(400, "body must include 'id'");
        WriteGates.conflictIf(api.service().connection(id).isPresent(),
                "connection '" + id + "' already exists (use PUT to update)");
        ConnectionProfile p = connectionFromBody(id, body, null);
        persistConnection(api, p);
        return p.toMap();
    }

    /** {@code PUT /connections/{id}} — replace a profile (masked secrets preserved); 404 if unknown. */
    private Object updateConnection(ApiContext api, String id, Map<String, Object> body) throws IOException {
        WriteGates.requireWriteRoot(api, "connection write");
        ConnectionProfile existing = api.service().connection(id)
                .orElseThrow(() -> new ApiException(404, "no connection profile '" + id + "'"));
        ConnectionProfile p = connectionFromBody(id, body, existing);
        persistConnection(api, p);
        return p.toMap();
    }

    /** {@code DELETE /connections/{id}} — remove a profile; 404 if unknown, 409 if a pipeline source uses it. */
    private Object deleteConnection(ApiContext api, String id) throws IOException {
        WriteGates.requireWriteRoot(api, "connection write");
        if (api.service().connection(id).isEmpty())
            throw new ApiException(404, "no connection profile '" + id + "'");
        WriteGates.conflictIf(api.service().connectionInUse(id),
                "connection '" + id + "' is in use by a pipeline source");
        boolean removed = Files.deleteIfExists(connectionFile(api, id));
        api.service().unregisterConnection(id);
        return Map.of("id", id, "deleted", true, "fileRemoved", removed);
    }

    /** The jailed {@code <id>_connection.toon} path under the write root; 422 on an unsafe id, 403 on escape. */
    private Path connectionFile(ApiContext api, String id) {
        String safe = WriteGates.safeName(id, "connection id");
        Path root = api.writeRoot();
        return WriteGates.jail(root, root.resolve(safe + "_connection.toon"), "resolved path");
    }

    /**
     * Build a validated {@link ConnectionProfile} from a request body (the secret-masked API shape). Masked secret
     * values ({@code ***}) are replaced with the stored reference from {@code existing} so an unchanged secret is
     * never clobbered; secrets must otherwise be {@code ${ENV:…}} references (never raw values).
     */
    @SuppressWarnings("unchecked")
    private ConnectionProfile connectionFromBody(String id, Map<String, Object> body, ConnectionProfile existing) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("connector", ApiContext.str(body, "connector"));
        c.put("host", ApiContext.str(body, "host"));
        if (body.get("port") != null) c.put("port", body.get("port"));
        c.put("database", ApiContext.str(body, "database"));
        String basePath = ApiContext.str(body, "basePath");
        if (basePath == null) basePath = ApiContext.str(body, "base_path");
        c.put("base_path", basePath);
        c.put("username", ApiContext.str(body, "username"));
        c.put("password", keepSecret(ApiContext.str(body, "password"), existing == null ? null : existing.password()));
        if (body.get("options") instanceof Map<?, ?> opts) {
            Map<String, String> priorOpts = existing == null ? Map.of() : existing.options();
            Map<String, Object> merged = new LinkedHashMap<>();
            opts.forEach((k, v) -> {
                String key = String.valueOf(k);
                merged.put(key, keepSecret(v == null ? null : String.valueOf(v), priorOpts.get(key)));
            });
            c.put("options", merged);
        }
        if (body.get("tunnel") instanceof Map<?, ?> t) {
            Map<String, Object> tun = new LinkedHashMap<>((Map<String, Object>) t);
            String priorPw = (existing != null && existing.tunnel() != null) ? existing.tunnel().password() : null;
            Object pw = tun.get("password");
            tun.put("password", keepSecret(pw == null ? null : String.valueOf(pw), priorPw));
            c.put("tunnel", tun);
        }
        if (body.get("proxy") instanceof Map<?, ?> pr) {
            Map<String, Object> px = new LinkedHashMap<>((Map<String, Object>) pr);
            String priorPw = (existing != null && existing.proxy() != null) ? existing.proxy().password() : null;
            Object pw = px.get("password");
            px.put("password", keepSecret(pw == null ? null : String.valueOf(pw), priorPw));
            c.put("proxy", px);
        }
        try {
            return ConnectionProfile.fromMap(c);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, ex.getMessage());
        }
    }

    /** Preserve a stored secret reference when the UI re-submits the mask sentinel ({@code ***}); else take new. */
    private static String keepSecret(String incoming, String existing) {
        return "***".equals(incoming) ? existing : incoming;
    }

    /** Encode the profile as a {@code connection { … }} TOON doc and write it atomically under the write root. */
    private void persistConnection(ApiContext api, ConnectionProfile p) throws IOException {
        Path target = connectionFile(api, p.id());
        byte[] bytes = ConfigCodec.toToon(Map.of("connection", connectionDoc(p))).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, ".conn-");
        api.service().registerConnection(p);
        log.info("[CONNECTION-WRITE] wrote {} ({} bytes)",
                api.writeRoot().relativize(target).toString().replace('\\', '/'), bytes.length);
    }

    /** The on-disk (unmasked, references preserved) {@code connection} block map for {@link ConfigCodec#toToon}. */
    private static Map<String, Object> connectionDoc(ConnectionProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("connector", p.connector());
        if (p.host() != null) m.put("host", p.host());
        if (p.port() > 0) m.put("port", p.port());
        if (p.database() != null) m.put("database", p.database());
        if (p.basePath() != null) m.put("base_path", p.basePath());
        if (p.username() != null) m.put("username", p.username());
        if (p.password() != null) m.put("password", p.password());
        if (!p.options().isEmpty()) m.put("options", new LinkedHashMap<>(p.options()));
        if (p.tunnel() != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("host", p.tunnel().host());
            if (p.tunnel().port() > 0) t.put("port", p.tunnel().port());
            if (p.tunnel().username() != null) t.put("username", p.tunnel().username());
            if (p.tunnel().password() != null) t.put("password", p.tunnel().password());
            m.put("tunnel", t);
        }
        if (p.proxy() != null) {
            Map<String, Object> pr = new LinkedHashMap<>();
            if (p.proxy().type() != null) pr.put("type", p.proxy().type());
            pr.put("host", p.proxy().host());
            if (p.proxy().port() > 0) pr.put("port", p.proxy().port());
            if (p.proxy().username() != null) pr.put("username", p.proxy().username());
            if (p.proxy().password() != null) pr.put("password", p.proxy().password());
            m.put("proxy", pr);
        }
        return m;
    }
}
