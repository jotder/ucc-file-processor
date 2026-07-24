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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The S3 {@link ConnectionWorkbench} (contributed by {@link S3ConnectorFactory}) against an in-process S3 stub
 * (JDK {@link HttpServer}) — offline, no SDK: delimiter-based pseudo-directory explore, bounded object sampling,
 * the graded probe (WRITE always skipped), honest 404s, and the ServiceLoader factory wiring.
 */
class S3ConnectionWorkbenchTest {

    private static final String OBJECT_BODY = "id,amt\n1,10\n2,20\n3,30\n";

    private HttpServer server;
    private ConnectionProfile profile;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        profile = new ConnectionProfile("s3-wb", "s3", "127.0.0.1", server.getAddress().getPort(),
                null, "bucket/in", "AKIDEXAMPLE", "test-secret-key",
                Map.of("region", "us-east-1", "protocol", "http"), null);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        String path = ex.getRequestURI().getPath();
        ex.getRequestBody().readAllBytes();
        byte[] body;
        int status = 200;
        if ("GET".equals(ex.getRequestMethod()) && "/bucket".equals(path)) {
            boolean deeper = query != null && query.contains("prefix=in%2Fsub%2F");
            body = (deeper ? listingSub() : listingRoot()).getBytes(StandardCharsets.UTF_8);
        } else if ("GET".equals(ex.getRequestMethod()) && path.startsWith("/bucket/in/")) {
            body = OBJECT_BODY.getBytes(StandardCharsets.UTF_8);
        } else {
            body = "<Error><Code>NoSuchKey</Code></Error>".getBytes(StandardCharsets.UTF_8);
            status = 404;
        }
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    /** One level under "in/": one CommonPrefix "in/sub/" (a pseudo-dir) + one object "in/a.csv". */
    private static String listingRoot() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Name>bucket</Name><Prefix>in/</Prefix><IsTruncated>false</IsTruncated>
                  <CommonPrefixes><Prefix>in/sub/</Prefix></CommonPrefixes>
                  <Contents><Key>in/a.csv</Key><Size>19</Size>
                    <LastModified>2026-07-08T10:00:00Z</LastModified><ETag>"etag-a"</ETag></Contents>
                </ListBucketResult>""";
    }

    /** One level under "in/sub/": one object "in/sub/b.csv". */
    private static String listingSub() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Name>bucket</Name><Prefix>in/sub/</Prefix><IsTruncated>false</IsTruncated>
                  <Contents><Key>in/sub/b.csv</Key><Size>19</Size>
                    <LastModified>2026-07-08T10:00:00Z</LastModified><ETag>"etag-b"</ETag></Contents>
                </ListBucketResult>""";
    }

    @Test
    void exploreWalksPseudoDirectoriesViaDelimiter() throws Exception {
        try (ConnectionWorkbench wb = S3Connector.workbench(profile)) {
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
            assertEquals("sub/b.csv", nested.get(0).path());
        }
    }

    @Test
    void sampleParsesTheObjectWithTheProductionReader() throws Exception {
        try (ConnectionWorkbench wb = S3Connector.workbench(profile)) {
            SampleResult s = wb.sample("a.csv", 2);
            assertEquals(List.of("id", "amt"), s.columns());
            assertEquals(2, s.rows().size());
            assertTrue(s.truncated());
        }
    }

    @Test
    void sampleOfAPseudoDirectoryRefusesHonestly() throws Exception {
        try (ConnectionWorkbench wb = S3Connector.workbench(profile)) {
            assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.sample("sub", 10));
            assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.sample("nope.csv", 10));
        }
    }

    @Test
    void probeGradesChecksAndSkipsWrite() throws Exception {
        try (ConnectionWorkbench wb = S3Connector.workbench(profile)) {
            assertTrue(wb.check(ProbeCheck.AUTHENTICATE, 25).ok());
            assertTrue(wb.check(ProbeCheck.READ, 25).ok());
            CheckOutcome write = wb.check(ProbeCheck.WRITE, 25);
            assertTrue(write.skipped(), "write must be skipped — the workbench never writes into a bucket");
            assertFalse(write.ok());
            assertTrue(wb.check(ProbeCheck.LIST, 25).ok());
        }
    }

    @Test
    void proberResolvesS3WorkbenchViaServiceLoader() throws Exception {
        try (ConnectionWorkbench wb = ConnectionProber.workbenchFor(profile)) {
            assertNotNull(wb, "the 's3' factory contributes a workbench via ServiceLoader");
        }
    }
}
