package com.gamma.acquire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Connection profile parse from {@code *_connection.toon}, secret masking, and the test endpoint. */
class ConnectionProfileTest {

    @Test
    void loadsAllFieldsIncludingTunnelAndOptions(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("cdr_connection.toon");
        Files.writeString(p, """
            connection:
              id: CDR_SFTP_PROD
              connector: SFTP
              host: sftp.example.com
              port: 22
              base_path: /cdr/outbox
              username: cdruser
              password: "${ENV:CDR_PW}"
              options:
                auth_method: key
                region: eu-west-1
              tunnel:
                host: bastion.example.com
                port: 2222
                username: jump
                password: "${SYS:bastion.pw}"
            """);
        ConnectionProfile c = ConnectionProfile.load(p);
        assertEquals("CDR_SFTP_PROD", c.id());
        assertEquals("sftp", c.connector(), "scheme is lower-cased");
        assertEquals("sftp.example.com", c.host());
        assertEquals(22, c.port());
        assertEquals("/cdr/outbox", c.basePath());
        assertEquals("cdruser", c.username());
        assertEquals("${ENV:CDR_PW}", c.password(), "the raw reference is stored, never a value");
        assertEquals("key", c.options().get("auth_method"));
        assertNotNull(c.tunnel());
        assertEquals("bastion.example.com:2222", c.tunnel().endpoint());
        assertTrue(c.isRemote());
        assertEquals("bastion.example.com:2222", c.testEndpoint(), "the tunnel hop is what a test probes");
    }

    @Test
    void testEndpointIsTheTargetWhenNoTunnel() {
        ConnectionProfile c = ConnectionProfile.fromMap(Map.of(
                "id", "S3FEED", "connector", "sftp", "host", "feeds.example.com", "port", "2200"));
        assertEquals("feeds.example.com:2200", c.testEndpoint());
        assertNull(c.tunnel());
    }

    @Test
    void toMapMasksSecretsButShowsReferences() {
        ConnectionProfile ref = ConnectionProfile.fromMap(Map.of(
                "id", "A", "connector", "sftp", "host", "h", "port", "22", "password", "${ENV:PW}"));
        assertEquals("${ENV:PW}", ref.toMap().get("password"), "a ${…} ref is shown (it is not itself secret)");

        ConnectionProfile inline = ConnectionProfile.fromMap(Map.of(
                "id", "B", "connector", "sftp", "host", "h", "port", "22", "password", "hunter2"));
        assertEquals("***", inline.toMap().get("password"), "an inlined literal secret is masked");
        assertFalse(inline.toMap().toString().contains("hunter2"), "the value never appears in the API view");
    }

    @Test
    void proxyBlockParsesAndMasksItsSecret() {
        ConnectionProfile c = ConnectionProfile.fromMap(Map.of(
                "id", "P", "connector", "sftp", "host", "h", "port", "22",
                "proxy", Map.of("type", "SOCKS5", "host", "proxy.example.com", "port", "1080",
                        "username", "px", "password", "hunter2")));
        assertNotNull(c.proxy());
        assertEquals("proxy.example.com:1080", c.proxy().endpoint());
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) c.toMap().get("proxy");
        assertEquals("SOCKS5", view.get("type"));
        assertEquals("***", view.get("password"), "an inlined proxy secret is masked");
        assertEquals("h:22", c.testEndpoint(), "a proxy does not change the saved-test endpoint");
    }

    @Test
    void localProfileIsNotRemote() {
        ConnectionProfile c = ConnectionProfile.fromMap(Map.of("id", "L", "connector", "local"));
        assertFalse(c.isRemote());
    }

    @Test
    void idAndConnectorAreRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionProfile.fromMap(Map.of("connector", "sftp")));
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionProfile.fromMap(Map.of("id", "X")));
    }
}
