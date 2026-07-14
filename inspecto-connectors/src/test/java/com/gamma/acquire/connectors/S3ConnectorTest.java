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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link S3Connector} end-to-end against an in-process S3 stub (JDK {@link HttpServer}) — offline, no
 * SDK, no network: ListObjectsV2 pagination, object GET (open/fetchTo), SigV4 header presence, etag propagation
 * onto {@link RemoteFile} (the ACQ-7 feed), and the copy+delete MOVE post-action.
 */
class S3ConnectorTest {

    private static final String OBJECT_BODY = "ID,AMT\nr1,10\n";

    private HttpServer server;
    private S3Connector connector;
    /** Every request the stub saw, as "METHOD path?query" plus captured auth headers. */
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        ConnectionProfile profile = new ConnectionProfile("s3-test", "s3", "127.0.0.1", server.getAddress().getPort(),
                null, "bucket/in", "AKIDEXAMPLE", "test-secret-key",
                Map.of("region", "us-east-1", "protocol", "http"), null);
        connector = new S3Connector(profile);
    }

    @AfterEach
    void stop() throws Exception {
        connector.close();
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        String key = ex.getRequestURI().getPath();
        requests.add(ex.getRequestMethod() + " " + key + (query != null ? "?" + query : ""));
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null) authHeaders.add(auth);
        ex.getRequestBody().readAllBytes();

        byte[] body;
        int status = 200;
        if ("GET".equals(ex.getRequestMethod()) && "/bucket".equals(key)) {
            body = listing(query != null && query.contains("continuation-token")).getBytes(StandardCharsets.UTF_8);
        } else if ("GET".equals(ex.getRequestMethod()) && key.startsWith("/bucket/in/")) {
            body = OBJECT_BODY.getBytes(StandardCharsets.UTF_8);
        } else if ("PUT".equals(ex.getRequestMethod()) || "DELETE".equals(ex.getRequestMethod())) {
            body = "<ok/>".getBytes(StandardCharsets.UTF_8);
            if ("DELETE".equals(ex.getRequestMethod())) status = 204;
        } else {
            body = "<Error><Code>NoSuchKey</Code></Error>".getBytes(StandardCharsets.UTF_8);
            status = 404;
        }
        if (status == 204) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
        }
        ex.close();
    }

    /** Two-page ListObjectsV2: page 1 is truncated with a token; page 2 completes. */
    private static String listing(boolean secondPage) {
        if (!secondPage) {
            return """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                      <Name>bucket</Name><Prefix>in/</Prefix><IsTruncated>true</IsTruncated>
                      <NextContinuationToken>token-2</NextContinuationToken>
                      <Contents><Key>in/a.csv</Key><Size>13</Size>
                        <LastModified>2026-07-08T10:00:00Z</LastModified><ETag>"etag-a"</ETag></Contents>
                      <Contents><Key>in/dir/</Key><Size>0</Size>
                        <LastModified>2026-07-08T10:00:00Z</LastModified><ETag>"d"</ETag></Contents>
                      <Contents><Key>in/skip.tmp</Key><Size>1</Size>
                        <LastModified>2026-07-08T10:00:00Z</LastModified><ETag>"t"</ETag></Contents>
                    </ListBucketResult>""";
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Name>bucket</Name><Prefix>in/</Prefix><IsTruncated>false</IsTruncated>
                  <Contents><Key>in/sub/b.csv</Key><Size>13</Size>
                    <LastModified>2026-07-08T11:00:00Z</LastModified><ETag>"etag-b"</ETag></Contents>
                </ListBucketResult>""";
    }

    @Test
    void discoverPaginatesFiltersAndCarriesEtag() throws Exception {
        List<RemoteFile> found = connector.discover(
                new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED));

        List<String> rels = new ArrayList<>(found.stream().map(RemoteFile::relativePath).toList());
        assertEquals(List.of("a.csv", "sub/b.csv"), rels, "csv objects from both pages; .tmp + dir placeholder dropped");
        RemoteFile a = found.get(0);
        assertEquals("etag-a", a.etag(), "listing ETag lands on RemoteFile (feeds ACQ-7 etag dedup)");
        assertEquals(13, a.size());
        assertNotNull(a.lastModified());
        assertEquals(CollectorConnector.Readiness.READY, connector.readiness(a), "listed S3 objects are complete");

        // Both listing pages requested, second with the continuation token.
        List<String> lists = requests.stream().filter(r -> r.startsWith("GET /bucket?")).toList();
        assertEquals(2, lists.size());
        assertTrue(lists.get(1).contains("continuation-token=token-2"));
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

        // Every request went out SigV4-signed with the S3 payload-hash header semantics.
        assertFalse(authHeaders.isEmpty());
        for (String auth : authHeaders) {
            assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"), auth);
            assertTrue(auth.contains("/us-east-1/s3/aws4_request"), auth);
            assertTrue(auth.contains("x-amz-content-sha256"), "S3 signing includes the payload hash header");
        }
    }

    @Test
    void movePostActionIsCopyThenDelete() throws Exception {
        RemoteFile a = new RemoteFile("a.csv", "a.csv", OBJECT_BODY.length(), null, "etag-a", null, null);
        connector.post(a, PostAction.move("archive/2026"));

        List<String> writes = requests.stream().filter(r -> !r.startsWith("GET")).toList();
        assertEquals(2, writes.size());
        assertEquals("PUT /bucket/in/archive/2026/a.csv", writes.get(0),
                "CopyObject to the archive key (under base_path, matching the SFTP MOVE semantics)");
        assertEquals("DELETE /bucket/in/a.csv", writes.get(1), "then the source object is deleted");
    }

    @Test
    void deletePostAction() throws Exception {
        RemoteFile a = new RemoteFile("a.csv", "a.csv", OBJECT_BODY.length(), null, "etag-a", null, null);
        connector.post(a, new PostAction(PostAction.Kind.DELETE, null, Map.of()));
        assertTrue(requests.contains("DELETE /bucket/in/a.csv"));
    }
}
