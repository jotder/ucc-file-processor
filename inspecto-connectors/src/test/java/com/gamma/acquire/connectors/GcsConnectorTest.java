package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.CollectorConnector;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link GcsConnector} end-to-end against an in-process GCS stub (JDK {@link HttpServer}) — offline, no
 * SDK, no network: the service-account OAuth2 JWT→bearer token exchange, Objects:list pagination, object GET
 * (open/fetchTo), bearer-auth header presence, etag/generation propagation onto {@link RemoteFile} (the ACQ-7
 * feed), and the copyTo+delete MOVE post-action. A real RSA key is generated in-test so the JWT is genuinely
 * RS256-signed.
 */
class GcsConnectorTest {

    private static final String OBJECT_BODY = "ID,AMT\nr1,10\n";

    private HttpServer server;
    private GcsConnector connector;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private int tokenMints = 0;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        int port = server.getAddress().getPort();
        String serviceAccountJson = serviceAccountKey("http://127.0.0.1:" + port + "/token");
        ConnectionProfile profile = new ConnectionProfile("gcs-test", "gcs", "127.0.0.1", port,
                null, "bucket/in", null, serviceAccountJson, Map.of("protocol", "http"), null);
        connector = new GcsConnector(profile);
    }

    @AfterEach
    void stop() throws Exception {
        connector.close();
        server.stop(0);
    }

    /** A minimal, valid service-account key JSON with a freshly generated RSA private key (PKCS#8 PEM). */
    private static String serviceAccountKey(String tokenUri) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        PrivateKey key = kpg.generateKeyPair().getPrivate();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        // A JSON string embedding another PEM: escape newlines, exactly as a real key file stores private_key.
        String pemJson = pem.replace("\n", "\\n");
        return "{\"type\":\"service_account\",\"client_email\":\"svc@proj.iam.gserviceaccount.com\","
                + "\"token_uri\":\"" + tokenUri + "\",\"private_key\":\"" + pemJson + "\"}";
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();          // decoded (in%2Fa.csv → in/a.csv)
        String query = ex.getRequestURI().getRawQuery();
        String method = ex.getRequestMethod();
        requests.add(method + " " + path + (query != null ? "?" + query : ""));
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null) authHeaders.add(auth);
        ex.getRequestBody().readAllBytes();

        byte[] body;
        int status = 200;
        if ("POST".equals(method) && "/token".equals(path)) {
            tokenMints++;
            body = "{\"access_token\":\"test-token\",\"expires_in\":3599,\"token_type\":\"Bearer\"}"
                    .getBytes(StandardCharsets.UTF_8);
        } else if ("GET".equals(method) && path.endsWith("/o")) {          // Objects: list
            body = listing(query != null && query.contains("pageToken")).getBytes(StandardCharsets.UTF_8);
        } else if ("GET".equals(method) && query != null && query.contains("alt=media")) {   // object read
            body = OBJECT_BODY.getBytes(StandardCharsets.UTF_8);
        } else if ("POST".equals(method) && path.contains("/copyTo/")) {
            body = "{\"kind\":\"storage#object\"}".getBytes(StandardCharsets.UTF_8);
        } else if ("PATCH".equals(method)) {
            body = "{\"kind\":\"storage#object\"}".getBytes(StandardCharsets.UTF_8);
        } else if ("DELETE".equals(method)) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        } else {
            body = "{\"error\":{\"code\":404,\"message\":\"Not Found\"}}".getBytes(StandardCharsets.UTF_8);
            status = 404;
        }
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    /** Two-page Objects:list — page 1 truncated with a nextPageToken; page 2 completes. */
    private static String listing(boolean secondPage) {
        if (!secondPage) {
            return "{\"kind\":\"storage#objects\",\"nextPageToken\":\"token-2\",\"items\":["
                    + "{\"name\":\"in/a.csv\",\"size\":\"13\",\"updated\":\"2026-07-08T10:00:00.000Z\","
                    + "\"etag\":\"etag-a\",\"generation\":\"1700000000000001\"},"
                    + "{\"name\":\"in/dir/\",\"size\":\"0\",\"updated\":\"2026-07-08T10:00:00.000Z\",\"etag\":\"d\"},"
                    + "{\"name\":\"in/skip.tmp\",\"size\":\"1\",\"updated\":\"2026-07-08T10:00:00.000Z\",\"etag\":\"t\"}"
                    + "]}";
        }
        return "{\"kind\":\"storage#objects\",\"items\":["
                + "{\"name\":\"in/sub/b.csv\",\"size\":\"13\",\"updated\":\"2026-07-08T11:00:00.000Z\","
                + "\"etag\":\"etag-b\",\"generation\":\"1700000000000002\"}"
                + "]}";
    }

    @Test
    void discoverPaginatesFiltersAndCarriesEtag() throws Exception {
        List<RemoteFile> found = connector.discover(
                new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED));

        List<String> rels = new ArrayList<>(found.stream().map(RemoteFile::relativePath).toList());
        assertEquals(List.of("a.csv", "sub/b.csv"), rels, "csv objects from both pages; .tmp + dir placeholder dropped");
        RemoteFile a = found.get(0);
        assertEquals("etag-a", a.etag(), "listing etag lands on RemoteFile (feeds ACQ-7 etag dedup)");
        assertEquals("1700000000000001", a.version(), "GCS generation → RemoteFile.version");
        assertEquals(13, a.size());
        assertNotNull(a.lastModified());
        assertEquals(CollectorConnector.Readiness.READY, connector.readiness(a), "listed GCS objects are complete");

        List<String> lists = requests.stream().filter(r -> r.contains("/o?")).toList();
        assertEquals(2, lists.size());
        assertTrue(lists.get(1).contains("pageToken=token-2"));
    }

    @Test
    void discoverHonoursDepthBound() throws Exception {
        List<RemoteFile> found = connector.discover(new DiscoveryContext(List.of("*.csv"), List.of(), 1));
        assertEquals(List.of("a.csv"), found.stream().map(RemoteFile::relativePath).toList(),
                "depth 1 keeps root-level objects only (sub/b.csv is depth 2)");
    }

    @Test
    void openStreamsAndFetchToMaterialises(@TempDir Path dir) throws Exception {
        RemoteFile a = new RemoteFile("a.csv", "a.csv", OBJECT_BODY.length(), null, "etag-a", null, null);

        try (InputStream in = connector.open(a)) {
            assertEquals(OBJECT_BODY, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        Path dest = dir.resolve("staged/a.csv");
        assertEquals(dest, connector.fetchTo(a, dest));
        assertEquals(OBJECT_BODY, Files.readString(dest));

        // Every storage request carried the minted bearer token; the token was minted once and reused.
        assertFalse(authHeaders.isEmpty());
        assertTrue(authHeaders.stream().allMatch(h -> h.equals("Bearer test-token")), authHeaders.toString());
        assertEquals(1, tokenMints, "the OAuth2 token is minted once and cached across requests");
    }

    @Test
    void movePostActionIsCopyThenDelete() throws Exception {
        RemoteFile a = new RemoteFile("a.csv", "a.csv", OBJECT_BODY.length(), null, "etag-a", null, null);
        connector.post(a, PostAction.move("archive/2026"));

        List<String> writes = requests.stream()
                .filter(r -> r.startsWith("POST") || r.startsWith("DELETE"))
                .filter(r -> !r.contains("/token"))
                .toList();
        assertEquals(2, writes.size());
        assertTrue(writes.get(0).startsWith("POST /storage/v1/b/bucket/o/in/a.csv/copyTo/b/bucket/o/in/archive/2026/a.csv"),
                "copyTo the archive object (under base_path, matching the S3 MOVE semantics): " + writes.get(0));
        assertEquals("DELETE /storage/v1/b/bucket/o/in/a.csv", writes.get(1), "then the source object is deleted");
    }

    @Test
    void deletePostAction() throws Exception {
        RemoteFile a = new RemoteFile("a.csv", "a.csv", OBJECT_BODY.length(), null, "etag-a", null, null);
        connector.post(a, new PostAction(PostAction.Kind.DELETE, null, Map.of()));
        assertTrue(requests.contains("DELETE /storage/v1/b/bucket/o/in/a.csv"));
    }
}
