package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProber;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.ConnectionWorkbench.CheckOutcome;
import com.gamma.acquire.ConnectionWorkbench.ProbeCheck;
import com.gamma.acquire.ConnectionWorkbench.ResourceNode;
import com.gamma.acquire.ConnectionWorkbench.SampleResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The GCS {@link ConnectionWorkbench} (contributed by {@link GcsConnectorFactory}) against an in-process GCS
 * stub (JDK {@link HttpServer}) — offline, no SDK: delimiter-based pseudo-directory explore, bounded object
 * sampling, the graded probe (WRITE always skipped), honest 404s, and the ServiceLoader factory wiring.
 */
class GcsConnectionWorkbenchTest {

    private static final String OBJECT_BODY = "id,amt\n1,10\n2,20\n3,30\n";

    private HttpServer server;
    private ConnectionProfile profile;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        int port = server.getAddress().getPort();
        String saJson = serviceAccountKey("http://127.0.0.1:" + port + "/token");
        profile = new ConnectionProfile("gcs-wb", "gcs", "127.0.0.1", port,
                null, "bucket/in", null, saJson, Map.of("protocol", "http"), null);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String serviceAccountKey(String tokenUri) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        PrivateKey key = kpg.generateKeyPair().getPrivate();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String pemJson = pem.replace("\n", "\\n");
        return "{\"type\":\"service_account\",\"client_email\":\"svc@proj.iam.gserviceaccount.com\","
                + "\"token_uri\":\"" + tokenUri + "\",\"private_key\":\"" + pemJson + "\"}";
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        String method = ex.getRequestMethod();
        ex.getRequestBody().readAllBytes();
        byte[] body;
        int status = 200;
        if ("POST".equals(method) && "/token".equals(path)) {
            body = "{\"access_token\":\"test-token\",\"expires_in\":3599,\"token_type\":\"Bearer\"}"
                    .getBytes(StandardCharsets.UTF_8);
        } else if ("GET".equals(method) && path.endsWith("/o")) {
            boolean deeper = query != null && query.contains("prefix=in%2Fsub%2F");
            body = (deeper ? listingSub() : listingRoot()).getBytes(StandardCharsets.UTF_8);
        } else if ("GET".equals(method) && query != null && query.contains("alt=media")) {
            body = OBJECT_BODY.getBytes(StandardCharsets.UTF_8);
        } else {
            body = "{\"error\":{\"code\":404,\"message\":\"Not Found\"}}".getBytes(StandardCharsets.UTF_8);
            status = 404;
        }
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    /** One level under "in/": one prefix "in/sub/" (a pseudo-dir) + one object "in/a.csv". */
    private static String listingRoot() {
        return "{\"kind\":\"storage#objects\",\"prefixes\":[\"in/sub/\"],\"items\":["
                + "{\"name\":\"in/a.csv\",\"size\":\"19\",\"updated\":\"2026-07-08T10:00:00.000Z\",\"etag\":\"etag-a\"}"
                + "]}";
    }

    private static String listingSub() {
        return "{\"kind\":\"storage#objects\",\"items\":["
                + "{\"name\":\"in/sub/b.csv\",\"size\":\"19\",\"updated\":\"2026-07-08T11:00:00.000Z\",\"etag\":\"etag-b\"}"
                + "]}";
    }

    @Test
    void exploreWalksPseudoDirectoriesViaDelimiter() throws Exception {
        try (ConnectionWorkbench wb = GcsConnector.workbench(profile)) {
            List<ResourceNode> root = wb.explore("");
            ResourceNode dir = root.stream().filter(n -> n.name().equals("sub")).findFirst().orElseThrow();
            assertEquals(ResourceNode.Kind.DIR, dir.kind());
            assertTrue(dir.hasChildren());
            ResourceNode file = root.stream().filter(n -> n.name().equals("a.csv")).findFirst().orElseThrow();
            assertEquals(ResourceNode.Kind.FILE, file.kind());
            assertEquals(19L, file.sizeBytes());

            List<ResourceNode> nested = wb.explore("sub");
            assertEquals(1, nested.size());
            assertEquals("b.csv", nested.get(0).name());
        }
    }

    @Test
    void sampleParsesTheObjectWithTheProductionReader() throws Exception {
        try (ConnectionWorkbench wb = GcsConnector.workbench(profile)) {
            SampleResult s = wb.sample("a.csv", 2);
            assertEquals(List.of("id", "amt"), s.columns());
            assertEquals(2, s.rows().size());
            assertTrue(s.truncated());
        }
    }

    @Test
    void probeGradesChecksAndSkipsWrite() throws Exception {
        try (ConnectionWorkbench wb = GcsConnector.workbench(profile)) {
            assertTrue(wb.check(ProbeCheck.AUTHENTICATE, 25).ok());
            assertTrue(wb.check(ProbeCheck.READ, 25).ok());
            CheckOutcome write = wb.check(ProbeCheck.WRITE, 25);
            assertTrue(write.skipped(), "write must be skipped — the workbench never writes into a bucket");
            assertTrue(wb.check(ProbeCheck.LIST, 25).ok());
        }
    }

    @Test
    void proberResolvesGcsWorkbenchViaServiceLoader() throws Exception {
        try (ConnectionWorkbench wb = ConnectionProber.workbenchFor(profile)) {
            assertNotNull(wb, "the 'gcs' factory contributes a workbench via ServiceLoader");
        }
    }
}
