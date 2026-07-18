package com.gamma.acquire;

import com.gamma.config.io.ConfigCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A reusable remote-system connection profile (Data Acquisition — connection profiles). One profile describes
 * <em>how to reach a system</em> (host/port/credentials/database/base path, optional SSH tunnel, optional
 * HTTP/SOCKS proxy) independently of any pipeline, so many pipelines can reference it by {@link #id} via a
 * {@code source.connection: <id>}.
 *
 * <p>Authored as an {@code *_connection.toon} (a {@code connection { … }} block), parsed with the same
 * {@link ConfigCodec} as the rest of the platform and loaded into a by-id registry on the service, exactly like
 * {@code *_rca.toon} templates.
 *
 * <h3>Secrets are references, never values</h3>
 * {@link #password} (and {@link Tunnel#password}, {@link Proxy#password}, and secret-ish {@link #options}) hold a
 * {@link SecretResolver} reference such as {@code ${ENV:CDR_PW}} — resolved only at connect time, never stored
 * or logged. {@link #toMap()} (the API/UI view) shows a {@code ${…}} reference verbatim but masks any non-ref
 * value as {@code ***}, so the profile can be displayed without leaking a credential a user may have inlined.
 */
@com.gamma.api.PublicApi(since = "4.2.0")
public record ConnectionProfile(String id, String connector, String host, int port, String database,
                                String basePath, String username, String password,
                                Map<String, String> options, Tunnel tunnel, Proxy proxy) {

    /** "Unset" port sentinel. */
    public static final int NO_PORT = 0;

    /** An optional SSH/bastion hop in front of the target system. */
    public record Tunnel(String host, int port, String username, String password) {
        public String endpoint() { return host + ":" + port; }
    }

    /**
     * An optional HTTP/SOCKS proxy in front of the target system ({@code type} = {@code HTTP} | {@code SOCKS5}).
     * Probed by the workbench's {@code target=proxy} test; it does <em>not</em> change {@link #testEndpoint()}
     * (the saved-profile test still prioritises the tunnel hop, else the target) — connectors don't dial
     * through it yet.
     */
    public record Proxy(String type, String host, int port, String username, String password) {
        public String endpoint() { return host + ":" + port; }
    }

    /** Pre-proxy shape, kept for {@code @PublicApi} source/binary compatibility; proxy = none. */
    public ConnectionProfile(String id, String connector, String host, int port, String database,
                             String basePath, String username, String password,
                             Map<String, String> options, Tunnel tunnel) {
        this(id, connector, host, port, database, basePath, username, password, options, tunnel, null);
    }

    public ConnectionProfile {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("connection profile id is required");
        if (connector == null || connector.isBlank())
            throw new IllegalArgumentException("connection '" + id + "' needs a connector scheme");
        id = id.trim();
        connector = connector.trim().toLowerCase();
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /** Whether this profile reaches a remote system over the network (vs. the in-process {@code local} source). */
    public boolean isRemote() {
        return !connector.isBlank() && !connector.equals("local");
    }

    /** The {@code host:port} a reachability test should probe: the tunnel/bastion hop when present, else the target. */
    public String testEndpoint() {
        if (tunnel != null && tunnel.host() != null && !tunnel.host().isBlank()) return tunnel.endpoint();
        if (host == null || host.isBlank()) return null;
        return host + ":" + (port > 0 ? port : "?");
    }

    /** Load an {@code *_connection.toon} (a {@code connection { … }} block). */
    @SuppressWarnings("unchecked")
    public static ConnectionProfile load(Path path) throws IOException {
        Map<String, Object> root = ConfigCodec.toMap(Files.readString(path));
        Object conn = root.get("connection");
        if (!(conn instanceof Map)) throw new IllegalArgumentException(path + " has no 'connection' block");
        return fromMap((Map<String, Object>) conn);
    }

    /** Parse + validate from a decoded {@code connection { … }} map. */
    @SuppressWarnings("unchecked")
    public static ConnectionProfile fromMap(Map<String, Object> c) {
        if (c == null) throw new IllegalArgumentException("missing 'connection' block");
        Map<String, String> options = new LinkedHashMap<>();
        if (c.get("options") instanceof Map<?, ?> opts)
            opts.forEach((k, v) -> { if (v != null) options.put(String.valueOf(k), String.valueOf(v)); });
        Tunnel tunnel = null;
        if (c.get("tunnel") instanceof Map<?, ?> t) {
            Map<String, Object> tm = (Map<String, Object>) t;
            String th = str(tm.get("host"));
            if (th != null) tunnel = new Tunnel(th, toInt(tm.get("port")), str(tm.get("username")), str(tm.get("password")));
        }
        Proxy proxy = null;
        if (c.get("proxy") instanceof Map<?, ?> pr) {
            Map<String, Object> pm = (Map<String, Object>) pr;
            String ph = str(pm.get("host"));
            if (ph != null) proxy = new Proxy(str(pm.get("type")), ph, toInt(pm.get("port")),
                    str(pm.get("username")), str(pm.get("password")));
        }
        return new ConnectionProfile(str(c.get("id")), str(c.get("connector")), str(c.get("host")),
                toInt(c.get("port")), str(c.get("database")), str(c.get("base_path")),
                str(c.get("username")), str(c.get("password")), options, tunnel, proxy);
    }

    /** JSON-ready, <b>secret-masked</b> view (stable key order) for the {@code /connections} API + UI. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("connector", connector);
        if (host != null) m.put("host", host);
        if (port > 0) m.put("port", port);
        if (database != null) m.put("database", database);
        if (basePath != null) m.put("basePath", basePath);
        if (username != null) m.put("username", username);
        if (password != null) m.put("password", mask(password));
        if (!options.isEmpty()) {
            Map<String, Object> masked = new TreeMap<>();
            options.forEach((k, v) -> masked.put(k, looksSecret(k) ? mask(v) : v));
            m.put("options", masked);
        }
        if (tunnel != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("host", tunnel.host());
            if (tunnel.port() > 0) t.put("port", tunnel.port());
            if (tunnel.username() != null) t.put("username", tunnel.username());
            if (tunnel.password() != null) t.put("password", mask(tunnel.password()));
            m.put("tunnel", t);
        }
        if (proxy != null) {
            Map<String, Object> pr = new LinkedHashMap<>();
            if (proxy.type() != null) pr.put("type", proxy.type());
            pr.put("host", proxy.host());
            if (proxy.port() > 0) pr.put("port", proxy.port());
            if (proxy.username() != null) pr.put("username", proxy.username());
            if (proxy.password() != null) pr.put("password", mask(proxy.password()));
            m.put("proxy", pr);
        }
        return m;
    }

    /** A {@code ${…}} reference is shown as-is (it is not a secret); any other value is masked. */
    private static String mask(String v) {
        return v == null ? null : (SecretResolver.isReference(v) ? v : "***");
    }

    private static boolean looksSecret(String key) {
        String k = key.toLowerCase();
        return k.contains("pass") || k.contains("secret") || k.contains("token") || k.contains("key");
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static int toInt(Object v) {
        if (v == null) return NO_PORT;
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { return NO_PORT; }
    }
}
