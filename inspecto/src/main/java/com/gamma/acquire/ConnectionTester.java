package com.gamma.acquire;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tests whether a {@link ConnectionProfile}'s endpoint is reachable (Data Acquisition — connection profiles),
 * backing the UI's "Test connection / tunnel" action. Zero dependencies: a plain TCP {@link Socket} connect
 * with a timeout — a genuine network/firewall/tunnel-reachability check that needs no protocol client.
 *
 * <p><b>Scope (honest):</b> this probes the effective {@link ConnectionProfile#testEndpoint()} — the
 * tunnel/bastion {@code host:port} when a {@code tunnel} is configured, otherwise the target {@code host:port}.
 * It confirms the endpoint is <em>reachable</em> and reports whether the profile's secret references
 * <em>resolve</em> in this environment (without revealing them). The protocol-level login (SFTP/JDBC auth,
 * full SSH-tunnel establishment) is performed by the connector itself and arrives with the Phase E connectors.
 */
public final class ConnectionTester {

    /** Default TCP connect timeout; override per-profile via {@code options.test_timeout_ms}. */
    public static final int DEFAULT_TIMEOUT_MS = 5_000;

    private ConnectionTester() {}

    /**
     * The result of testing one profile. {@code reachable} is the TCP outcome; {@code latencyMs} the connect
     * time when reachable; {@code secretsResolved} whether every {@code ${…}} credential reference resolves;
     * {@code detail} a human-readable note (and the failure reason when unreachable).
     */
    public record Result(String id, String connector, String endpoint, boolean reachable,
                         Long latencyMs, boolean secretsResolved, String detail) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("connector", connector);
            m.put("endpoint", endpoint);
            m.put("reachable", reachable);
            if (latencyMs != null) m.put("latencyMs", latencyMs);
            m.put("secretsResolved", secretsResolved);
            m.put("detail", detail);
            return m;
        }
    }

    /** Run the reachability + secret-resolution test for {@code p}. Never throws — failures land in the result. */
    public static Result test(ConnectionProfile p) {
        boolean secrets = secretsResolve(p);

        if (!p.isRemote()) {
            return new Result(p.id(), p.connector(), "local", true, 0L, secrets,
                    "local source — no remote connection to test");
        }
        String endpoint = p.testEndpoint();
        boolean viaTunnel = p.tunnel() != null && p.tunnel().host() != null && !p.tunnel().host().isBlank();
        String host = viaTunnel ? p.tunnel().host() : p.host();
        int port = viaTunnel ? p.tunnel().port() : p.port();
        String hop = viaTunnel ? " (tunnel/bastion hop; through-tunnel login is Phase E)" : "";

        if (host == null || host.isBlank())
            return new Result(p.id(), p.connector(), endpoint, false, null, secrets, "no host configured");
        if (port <= 0)
            return new Result(p.id(), p.connector(), endpoint, false, null, secrets, "no port configured");

        int timeout = timeoutMs(p);
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            return new Result(p.id(), p.connector(), endpoint, true, ms, secrets, "TCP connect ok" + hop);
        } catch (IOException | RuntimeException e) {
            String reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return new Result(p.id(), p.connector(), endpoint, false, null, secrets, "unreachable — " + reason + hop);
        }
    }

    private static int timeoutMs(ConnectionProfile p) {
        String v = p.options().get("test_timeout_ms");
        if (v != null) try { int t = Integer.parseInt(v.trim()); if (t > 0) return t; } catch (NumberFormatException ignore) {}
        return DEFAULT_TIMEOUT_MS;
    }

    /** Whether all of the profile's credential references resolve in this environment (no value is exposed). */
    private static boolean secretsResolve(ConnectionProfile p) {
        boolean ok = refResolves(p.password());
        if (p.tunnel() != null) ok &= refResolves(p.tunnel().password());
        for (Map.Entry<String, String> e : p.options().entrySet())
            if (SecretResolver.isReference(e.getValue())) ok &= SecretResolver.isResolvable(e.getValue());
        return ok;
    }

    /** A profile with no reference for a field is fine (true); a present reference must resolve. */
    private static boolean refResolves(String ref) {
        if (ref == null || ref.isBlank()) return true;        // nothing configured ⇒ nothing to fail
        if (!SecretResolver.isReference(ref)) return true;    // a literal is trivially "resolved"
        return SecretResolver.isResolvable(ref);
    }
}
