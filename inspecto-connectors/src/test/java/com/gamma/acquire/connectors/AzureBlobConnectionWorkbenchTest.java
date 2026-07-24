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
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Azure Blob {@link ConnectionWorkbench} (contributed by {@link AzureBlobConnectorFactory}) against an
 * in-process Blob-service stub (JDK {@link HttpServer}) — offline, no SDK: delimiter-based pseudo-directory
 * explore, bounded blob sampling, the graded probe (WRITE always skipped), honest 404s, and the ServiceLoader
 * factory wiring.
 */
class AzureBlobConnectionWorkbenchTest {

    private static final String OBJECT_BODY = "id,amt\n1,10\n2,20\n3,30\n";

    private HttpServer server;
    private ConnectionProfile profile;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        // A syntactically valid base64 account key — signature correctness isn't exercised by the stub.
        String key = Base64.getEncoder().encodeToString("test-key-material-32-bytes-long".getBytes(StandardCharsets.UTF_8));
        profile = new ConnectionProfile("azure-wb", "azure", "127.0.0.1", server.getAddress().getPort(),
                null, "container/in", "acct", key, Map.of("protocol", "http"), null);
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
        if ("GET".equals(ex.getRequestMethod()) && "/container".equals(path)) {
            boolean deeper = query != null && query.contains("prefix=in%2Fsub%2F");
            body = (deeper ? listingSub() : listingRoot()).getBytes(StandardCharsets.UTF_8);
        } else if ("GET".equals(ex.getRequestMethod()) && path.startsWith("/container/in/")) {
            body = OBJECT_BODY.getBytes(StandardCharsets.UTF_8);
        } else {
            body = "<Error><Code>BlobNotFound</Code></Error>".getBytes(StandardCharsets.UTF_8);
            status = 404;
        }
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    /** One level under "in/": one BlobPrefix "in/sub/" (a pseudo-dir) + one blob "in/a.csv". */
    private static String listingRoot() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <EnumerationResults>
                  <Blobs>
                    <BlobPrefix><Name>in/sub/</Name></BlobPrefix>
                    <Blob><Name>in/a.csv</Name><Properties><Content-Length>19</Content-Length>
                      <Last-Modified>Tue, 07 Jul 2026 10:00:00 GMT</Last-Modified><Etag>"etag-a"</Etag></Properties></Blob>
                  </Blobs>
                  <NextMarker/>
                </EnumerationResults>""";
    }

    private static String listingSub() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <EnumerationResults>
                  <Blobs>
                    <Blob><Name>in/sub/b.csv</Name><Properties><Content-Length>19</Content-Length>
                      <Last-Modified>Tue, 07 Jul 2026 11:00:00 GMT</Last-Modified><Etag>"etag-b"</Etag></Properties></Blob>
                  </Blobs>
                  <NextMarker/>
                </EnumerationResults>""";
    }

    @Test
    void exploreWalksPseudoDirectoriesViaDelimiter() throws Exception {
        try (ConnectionWorkbench wb = AzureBlobConnector.workbench(profile)) {
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
    void sampleParsesTheBlobWithTheProductionReader() throws Exception {
        try (ConnectionWorkbench wb = AzureBlobConnector.workbench(profile)) {
            SampleResult s = wb.sample("a.csv", 2);
            assertEquals(List.of("id", "amt"), s.columns());
            assertEquals(2, s.rows().size());
            assertTrue(s.truncated());
        }
    }

    @Test
    void probeGradesChecksAndSkipsWrite() throws Exception {
        try (ConnectionWorkbench wb = AzureBlobConnector.workbench(profile)) {
            assertTrue(wb.check(ProbeCheck.AUTHENTICATE, 25).ok());
            assertTrue(wb.check(ProbeCheck.READ, 25).ok());
            CheckOutcome write = wb.check(ProbeCheck.WRITE, 25);
            assertTrue(write.skipped(), "write must be skipped — the workbench never writes into a container");
            assertTrue(wb.check(ProbeCheck.LIST, 25).ok());
        }
    }

    @Test
    void proberResolvesAzureWorkbenchViaServiceLoader() throws Exception {
        try (ConnectionWorkbench wb = ConnectionProber.workbenchFor(profile)) {
            assertNotNull(wb, "the 'azure' factory contributes a workbench via ServiceLoader");
        }
    }
}
