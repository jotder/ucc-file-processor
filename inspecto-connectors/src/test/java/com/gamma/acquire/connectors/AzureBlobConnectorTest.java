package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link AzureBlobConnector} end-to-end against an in-process Blob-service stub (JDK
 * {@link HttpServer}) — offline, no SDK, no network: List Blobs pagination via {@code NextMarker}, blob GET
 * (open/fetchTo), SharedKey header presence, etag + RFC-1123 mtime propagation onto {@link RemoteFile}
 * (the ACQ-7 feed), and the copy+delete MOVE post-action with the copy-status guard.
 */
class AzureBlobConnectorTest {

    private static final String BLOB_BODY = "ID,AMT\nr1,10\n";
    /** Azurite's published well-known dev key — a valid base64 HMAC key for offline signing. */
    private static final String DEV_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    private HttpServer server;
    private AzureBlobConnector connector;
    /** Every request the stub saw, as "METHOD path?query", plus captured auth + copy-source headers. */
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final List<String> copySources = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        ConnectionProfile profile = new ConnectionProfile("az-test", "azure", "127.0.0.1",
                server.getAddress().getPort(), null, "container/in", "devstoreaccount1", DEV_KEY,
                Map.of("protocol", "http"), null);
        connector = new AzureBlobConnector(profile);
    }

    @AfterEach
    void stop() throws Exception {
        connector.close();
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        String path = ex.getRequestURI().getPath();
        requests.add(ex.getRequestMethod() + " " + path + (query != null ? "?" + query : ""));
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null) authHeaders.add(auth);
        String copySource = ex.getRequestHeaders().getFirst("x-ms-copy-source");
        if (copySource != null) copySources.add(copySource);
        ex.getRequestBody().readAllBytes();

        byte[] body;
        int status = 200;
        if ("GET".equals(ex.getRequestMethod()) && "/container".equals(path)) {
            body = listing(query != null && query.contains("marker=page2")).getBytes(StandardCharsets.UTF_8);
        } else if ("GET".equals(ex.getRequestMethod()) && path.startsWith("/container/in/")) {
            body = BLOB_BODY.getBytes(StandardCharsets.UTF_8);
        } else if ("PUT".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("x-ms-copy-status", "success");
            body = new byte[0];
            status = 201;
        } else if ("DELETE".equals(ex.getRequestMethod())) {
            body = new byte[0];
            status = 202;
        } else {
            body = "<Error><Code>BlobNotFound</Code></Error>".getBytes(StandardCharsets.UTF_8);
            status = 404;
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (var out = ex.getResponseBody()) { out.write(body); }
        else ex.close();
    }

    /** Page 1 carries a NextMarker → page 2; blob names sit under the profile's {@code in/} prefix. */
    private String listing(boolean page2) {
        if (!page2) return """
                <?xml version="1.0" encoding="utf-8"?>
                <EnumerationResults ServiceEndpoint="http://127.0.0.1/" ContainerName="container">
                  <Blobs>
                    <Blob><Name>in/2026/a.csv</Name><Properties>
                      <Last-Modified>Tue, 07 Jul 2026 10:00:00 GMT</Last-Modified>
                      <Content-Length>13</Content-Length><Etag>0x8D9ABCDEF012345</Etag>
                    </Properties></Blob>
                    <Blob><Name>in/skip.tmp</Name><Properties>
                      <Last-Modified>Tue, 07 Jul 2026 10:00:00 GMT</Last-Modified>
                      <Content-Length>1</Content-Length><Etag>0x1</Etag>
                    </Properties></Blob>
                    <Blob><Name>in/dir/</Name><Properties><Content-Length>0</Content-Length></Properties></Blob>
                  </Blobs>
                  <NextMarker>page2</NextMarker>
                </EnumerationResults>""";
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <EnumerationResults ServiceEndpoint="http://127.0.0.1/" ContainerName="container">
                  <Blobs>
                    <Blob><Name>in/b.csv</Name><Properties>
                      <Last-Modified>Tue, 07 Jul 2026 11:00:00 GMT</Last-Modified>
                      <Content-Length>13</Content-Length><Etag>0x8D9ABCDEF06789A</Etag>
                    </Properties></Blob>
                  </Blobs>
                  <NextMarker/>
                </EnumerationResults>""";
    }

    private static DiscoveryContext csvOnly() {
        return new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED);
    }

    @Test
    void discoverPaginatesFiltersAndCarriesEtagAndMtime() throws Exception {
        List<RemoteFile> found = connector.discover(csvOnly());
        assertEquals(List.of("2026/a.csv", "b.csv"), found.stream().map(RemoteFile::relativePath).toList(),
                "prefix stripped; .tmp filtered; directory placeholder skipped; both pages walked");
        RemoteFile a = found.getFirst();
        assertEquals(13, a.size());
        assertEquals("0x8D9ABCDEF012345", a.etag(), "listing Etag feeds ACQ-7 dedup");
        assertEquals("2026-07-07T10:00:00Z", a.lastModified().toString(), "RFC-1123 Last-Modified parsed");
        assertEquals(2, requests.stream().filter(r -> r.contains("comp=list")).count(), "NextMarker → second page");
        assertFalse(authHeaders.isEmpty());
        assertTrue(authHeaders.getFirst().startsWith("SharedKey devstoreaccount1:"), authHeaders.getFirst());
    }

    @Test
    void fetchToWritesBytesOnceAtDestination(@TempDir Path dir) throws Exception {
        RemoteFile file = connector.discover(csvOnly()).getFirst();
        Path dest = dir.resolve(file.relativePath());
        assertEquals(dest, connector.fetchTo(file, dest));
        assertEquals(BLOB_BODY, Files.readString(dest));
        assertTrue(requests.contains("GET /container/in/2026/a.csv"), requests.toString());
    }

    @Test
    void openStreamsWithoutLocalCopy() throws Exception {
        RemoteFile file = connector.discover(csvOnly()).getFirst();
        try (InputStream in = connector.open(file)) {
            assertEquals(BLOB_BODY, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void movePostActionCopiesThenDeletes() throws Exception {
        RemoteFile file = connector.discover(csvOnly()).getFirst();
        connector.post(file, new PostAction(PostAction.Kind.MOVE, "archive/2026", Map.of()));
        assertTrue(requests.contains("PUT /container/in/archive/2026/2026/a.csv"),
                "copy lands under the prefix like SFTP archive semantics: " + requests);
        assertTrue(requests.contains("DELETE /container/in/2026/a.csv"), requests.toString());
        assertEquals(1, copySources.size());
        assertTrue(copySources.getFirst().endsWith("/container/in/2026/a.csv"),
                "x-ms-copy-source is the absolute source URL: " + copySources.getFirst());
    }

    @Test
    void readinessAlwaysReadyAndRetainDoesNothing() throws Exception {
        RemoteFile file = connector.discover(csvOnly()).getFirst();
        assertEquals(AzureBlobConnector.Readiness.READY, connector.readiness(file));
        int before = requests.size();
        connector.post(file, new PostAction(PostAction.Kind.RETAIN, null, Map.of()));
        assertEquals(before, requests.size(), "RETAIN issues no requests");
    }
}
