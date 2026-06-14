package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The reachability test: a real TCP connect (zero-dep) + secret-resolution reporting, no external network. */
class ConnectionTesterTest {

    private static ConnectionProfile remote(String host, int port, String password) {
        Map<String, Object> m = new java.util.HashMap<>(Map.of(
                "id", "T", "connector", "sftp", "host", host, "port", String.valueOf(port),
                "options", Map.of("test_timeout_ms", "500")));
        if (password != null) m.put("password", password);
        return ConnectionProfile.fromMap(m);
    }

    @Test
    void reachableEndpointReportsLatency() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {        // listening ⇒ TCP connect succeeds
            ConnectionTester.Result r = ConnectionTester.test(remote("127.0.0.1", ss.getLocalPort(), null));
            assertTrue(r.reachable(), r.detail());
            assertNotNull(r.latencyMs());
            assertEquals("127.0.0.1:" + ss.getLocalPort(), r.endpoint());
        }
    }

    @Test
    void closedPortIsUnreachable() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) { port = ss.getLocalPort(); }   // grab then release ⇒ closed
        ConnectionTester.Result r = ConnectionTester.test(remote("127.0.0.1", port, null));
        assertFalse(r.reachable());
        assertNull(r.latencyMs());
        assertTrue(r.detail().startsWith("unreachable"), r.detail());
    }

    @Test
    void localProfileNeedsNoNetwork() {
        ConnectionTester.Result r = ConnectionTester.test(ConnectionProfile.fromMap(
                Map.of("id", "L", "connector", "local")));
        assertTrue(r.reachable());
        assertEquals("local", r.endpoint());
    }

    @Test
    void missingHostOrPortFailsCleanly() {
        assertFalse(ConnectionTester.test(ConnectionProfile.fromMap(
                Map.of("id", "X", "connector", "sftp", "port", "22"))).reachable());   // no host
        assertFalse(ConnectionTester.test(ConnectionProfile.fromMap(
                Map.of("id", "Y", "connector", "sftp", "host", "h"))).reachable());     // no port
    }

    @Test
    void secretsResolvedReflectsTheEnvironment() throws Exception {
        String key = "test.conn.pw." + System.nanoTime();
        try (ServerSocket ss = new ServerSocket(0)) {
            // an unresolved ${SYS:…} reference ⇒ secretsResolved=false even though the endpoint is reachable
            ConnectionTester.Result missing = ConnectionTester.test(
                    remote("127.0.0.1", ss.getLocalPort(), "${SYS:" + key + "}"));
            assertTrue(missing.reachable());
            assertFalse(missing.secretsResolved());

            System.setProperty(key, "secret");
            try {
                assertTrue(ConnectionTester.test(
                        remote("127.0.0.1", ss.getLocalPort(), "${SYS:" + key + "}")).secretsResolved());
            } finally {
                System.clearProperty(key);
            }
        }
    }
}
