package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.SecretResolver;
import com.gamma.acquire.CollectorConnector;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.acquire.CollectorConnector.Capability.*;

/**
 * An <b>Azure Blob Storage</b> {@link CollectorConnector} (ACQ-4) speaking the Blob REST API directly over the
 * JDK {@link HttpClient} with {@link AzureSharedKey} signing — <b>no Azure SDK</b>, the same discipline as
 * {@link S3Connector}. Covers real Azure and Azurite/emulator endpoints.
 *
 * <p><b>Profile mapping</b> ({@code *_connection.toon}, {@code connector: azure}): {@code host}/{@code port} =
 * the blob endpoint (e.g. {@code myacct.blob.core.windows.net}, or an Azurite host); {@code username} = the
 * storage account name; {@code password} = the account access key (base64; a {@link SecretResolver} reference,
 * resolved per request-signing, never logged); {@code base_path} = {@code container[/prefix]};
 * {@code options.protocol} {@code https} (default) | {@code http} (a LAN emulator).
 *
 * <p>Listings come from List Blobs (paginated via {@code NextMarker}) and carry each blob's {@code Etag} —
 * populated onto {@link RemoteFile#etag()} so {@code source.duplicate.mode: etag} (ACQ-7) can skip unchanged
 * blobs without downloading. Blob writes are atomic — a listed blob is complete — so {@link #readiness} is
 * always {@code READY}. MOVE/RENAME are Copy Blob + Delete Blob (the copy must complete synchronously — the
 * same-account fast path; a {@code pending} copy fails the post-action rather than risking the source);
 * TAG is Set Blob Tags.
 */
public final class AzureBlobConnector implements CollectorConnector {

    private static final int MAX_RESULTS_PAGE = 5000;

    private final ConnectionProfile profile;
    private final String container;
    private final String prefix;      // blob-name prefix under the container, "" when base_path is just the container
    private final String account;
    private final URI endpoint;       // scheme://host[:port], no path
    private final HttpClient http;

    public AzureBlobConnector(ConnectionProfile profile) {
        this.profile = profile;
        String bp = profile.basePath() == null ? "" : profile.basePath().trim();
        String stripped = bp.startsWith("/") ? bp.substring(1) : bp;
        if (stripped.isBlank())
            throw new IllegalArgumentException("azure connection '" + profile.id() + "' needs base_path = container[/prefix]");
        int slash = stripped.indexOf('/');
        this.container = slash < 0 ? stripped : stripped.substring(0, slash);
        String p = slash < 0 ? "" : stripped.substring(slash + 1);
        this.prefix = p.isBlank() ? "" : (p.endsWith("/") ? p : p + "/");
        this.account = profile.username();
        if (account == null || account.isBlank())
            throw new IllegalArgumentException("azure connection '" + profile.id() + "' needs username = storage account name");
        String protocol = profile.options().getOrDefault("protocol", "https");
        int port = profile.port();
        this.endpoint = URI.create(protocol + "://" + profile.host() + (port > 0 ? ":" + port : ""));
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Override
    public String scheme() {
        return "azure";
    }

    @Override
    public EnumSet<Capability> capabilities() {
        return EnumSet.of(STREAM, RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, RENAME, TAG, ETAG);
    }

    @Override
    public List<RemoteFile> discover(DiscoveryContext ctx) throws AcquisitionException {
        PatternFilter filter = new PatternFilter(ctx.includes(), ctx.excludes());
        List<RemoteFile> out = new ArrayList<>();
        String marker = null;
        do {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("restype", "container");
            query.put("comp", "list");
            query.put("maxresults", String.valueOf(MAX_RESULTS_PAGE));
            if (!prefix.isEmpty()) query.put("prefix", prefix);
            if (marker != null) query.put("marker", marker);
            HttpResponse<byte[]> resp = execute("GET", "/" + container, query, Map.of(), null, "list blobs");
            Document doc = parseXml(resp.body());
            marker = text(doc.getDocumentElement(), "NextMarker");
            if (marker != null && marker.isBlank()) marker = null;
            collectBlobs(doc, filter, ctx, out);
        } while (marker != null);
        return out;
    }

    private void collectBlobs(Document doc, PatternFilter filter, DiscoveryContext ctx, List<RemoteFile> out) {
        NodeList blobs = doc.getElementsByTagName("Blob");
        for (int i = 0; i < blobs.getLength(); i++) {
            Element b = (Element) blobs.item(i);
            String name = text(b, "Name");
            if (name == null || name.endsWith("/")) continue;   // zero-byte "directory" placeholder blobs
            String rel = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
            if (rel.isBlank() || !filter.accepts(rel)) continue;
            // Depth semantics mirror the local walk: container[/prefix] root is depth 0, "a.csv" 1, "d/a.csv" 2.
            if (ctx.bounded() && rel.split("/").length > ctx.maxDepth()) continue;
            long size = parseLong(text(b, "Content-Length"), RemoteFile.SIZE_UNKNOWN);
            Instant mtime = parseRfc1123(text(b, "Last-Modified"));
            String etag = unquote(text(b, "Etag"));
            out.add(new RemoteFile(nameOf(rel), rel, size, mtime, etag, null, null));
        }
    }

    /** A blob visible in a listing is a complete object — blob commits are atomic, so no stabilization needed. */
    @Override
    public Readiness readiness(RemoteFile file) {
        return Readiness.READY;
    }

    @Override
    public InputStream open(RemoteFile file) throws AcquisitionException {
        HttpResponse<InputStream> resp = executeStreaming(blobPath(file), Map.of(), "open " + file.relativePath());
        return resp.body();
    }

    @Override
    public Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException {
        try {
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            long offset = 0;
            if (Files.exists(dest)) {
                long have = Files.size(dest);
                if (file.hasSize() && have == file.size() && have > 0) return dest;     // already complete
                if (have > 0 && (!file.hasSize() || have < file.size())) offset = have; // resume (RESUMABLE)
            }
            Map<String, String> headers = offset > 0 ? Map.of("Range", "bytes=" + offset + "-") : Map.of();
            HttpResponse<InputStream> resp = executeStreaming(blobPath(file), headers, "fetch " + file.relativePath());
            boolean append = offset > 0 && resp.statusCode() == 206;   // server honoured the range
            try (InputStream in = resp.body();
                 var out = append
                         ? Files.newOutputStream(dest, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                         : Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                in.transferTo(out);
            }
            return dest;
        } catch (IOException e) {
            throw new AcquisitionException("Azure fetch failed for " + file.relativePath() + " → " + dest, e);
        }
    }

    @Override
    public void post(RemoteFile file, PostAction action) throws AcquisitionException {
        switch (action.kind()) {
            case RETAIN -> { /* leave it */ }
            case DELETE -> execute("DELETE", blobPath(file), Map.of(), Map.of(), null, "delete " + file.relativePath());
            case MOVE -> copyThenDelete(file, join(action.archiveTemplate(), file.relativePath()));
            case RENAME -> {
                String rel = file.relativePath();
                int i = rel.lastIndexOf('/');
                copyThenDelete(file, (i < 0 ? "" : rel.substring(0, i + 1)) + "processed_" + file.name());
            }
            case TAG -> putTags(file, action.tags());
        }
    }

    /**
     * Blob storage has no rename: Copy Blob to the new name, then Delete Blob on the old. A same-account copy
     * completes synchronously; if the service reports the copy {@code pending} the source is NOT deleted —
     * the post-action fails (upstream logs and continues; the bytes are already staged locally).
     */
    private void copyThenDelete(RemoteFile file, String newRel) throws AcquisitionException {
        String sourceUrl = endpoint + "/" + container + "/" + AwsSigV4.uriEncode(prefix + file.relativePath(), false);
        String destPath = "/" + container + "/" + AwsSigV4.uriEncode(prefix + newRel, false);
        HttpResponse<byte[]> resp = execute("PUT", destPath, Map.of(), Map.of("x-ms-copy-source", sourceUrl), null,
                "copy " + file.relativePath() + " → " + newRel);
        String copyStatus = resp.headers().firstValue("x-ms-copy-status").orElse("success");
        if (!"success".equalsIgnoreCase(copyStatus))
            throw new AcquisitionException("Azure copy of " + file.relativePath() + " is '" + copyStatus
                    + "' (not synchronous) — source retained");
        execute("DELETE", blobPath(file), Map.of(), Map.of(), null, "delete " + file.relativePath());
    }

    private void putTags(RemoteFile file, Map<String, String> tags) throws AcquisitionException {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><Tags><TagSet>");
        tags.forEach((k, v) -> xml.append("<Tag><Key>").append(escapeXml(k)).append("</Key><Value>")
                .append(escapeXml(v)).append("</Value></Tag>"));
        xml.append("</TagSet></Tags>");
        execute("PUT", blobPath(file), Map.of("comp", "tags"), Map.of("Content-Type", "application/xml"),
                xml.toString().getBytes(StandardCharsets.UTF_8), "tag " + file.relativePath());
    }

    // ── HTTP + signing ────────────────────────────────────────────────────────

    /** Execute a signed request whose response is small (listing XML, copy/delete acks). */
    private HttpResponse<byte[]> execute(String method, String path, Map<String, String> query,
                                         Map<String, String> headers, byte[] body, String what)
            throws AcquisitionException {
        try {
            HttpRequest req = signed(method, path, query, headers, body);
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2)
                throw new AcquisitionException("Azure " + what + " failed: HTTP " + resp.statusCode()
                        + errorDetail(resp.body()));
            return resp;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AcquisitionException("Azure " + what + " failed on " + endpoint.getHost() + ": " + e.getMessage(), e);
        }
    }

    /** Execute a signed GET whose body should stream (blob reads). */
    private HttpResponse<InputStream> executeStreaming(String path, Map<String, String> headers, String what)
            throws AcquisitionException {
        try {
            HttpRequest req = signed("GET", path, Map.of(), headers, null);
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                byte[] err;
                try (InputStream in = resp.body()) { err = in.readNBytes(2048); }
                throw new AcquisitionException("Azure " + what + " failed: HTTP " + resp.statusCode() + errorDetail(err));
            }
            return resp;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AcquisitionException("Azure " + what + " failed on " + endpoint.getHost() + ": " + e.getMessage(), e);
        }
    }

    private HttpRequest signed(String method, String encodedPath, Map<String, String> query,
                               Map<String, String> headers, byte[] body) throws IOException {
        StringBuilder qs = new StringBuilder();
        query.forEach((k, v) -> {
            if (!qs.isEmpty()) qs.append('&');
            qs.append(AwsSigV4.uriEncode(k, true)).append('=').append(AwsSigV4.uriEncode(v, true));
        });
        URI uri = URI.create(endpoint + encodedPath + (qs.isEmpty() ? "" : "?" + qs));

        String key = SecretResolver.resolve(profile.password());
        if (key == null)
            throw new IOException("no usable account key for azure connection '" + profile.id() + "'");
        long contentLength = body == null ? 0 : body.length;
        Map<String, String> signedHeaders = AzureSharedKey.sign(method, uri, headers, contentLength,
                Instant.now(), account, key);

        HttpRequest.Builder b = HttpRequest.newBuilder(uri).method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        signedHeaders.forEach(b::header);
        return b.build();
    }

    /** The request path for a blob: {@code /container/prefix+rel}, name encoded once, '/' preserved. */
    private String blobPath(RemoteFile file) {
        return "/" + container + "/" + AwsSigV4.uriEncode(prefix + file.relativePath(), false);
    }

    // ── XML + small helpers ───────────────────────────────────────────────────

    private static Document parseXml(byte[] body) throws AcquisitionException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // Listing XML is attacker-adjacent input from a remote endpoint: no DTDs, no external entities.
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(body));
        } catch (Exception e) {
            throw new AcquisitionException("Cannot parse Azure listing XML: " + e.getMessage(), e);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList n = parent.getElementsByTagName(tag);
        return n.getLength() > 0 ? n.item(0).getTextContent() : null;
    }

    private static String errorDetail(byte[] body) {
        if (body == null || body.length == 0) return "";
        String s = new String(body, 0, Math.min(body.length, 500), StandardCharsets.UTF_8);
        return " — " + s.replaceAll("\\s+", " ").trim();
    }

    private static String nameOf(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? rel : rel.substring(i + 1);
    }

    private static String unquote(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b;
        String left = a.endsWith("/") ? a.substring(0, a.length() - 1) : a;
        return left + "/" + (b.startsWith("/") ? b.substring(1) : b);
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static long parseLong(String s, long dflt) {
        if (s == null || s.isBlank()) return dflt;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return dflt; }
    }

    /** Blob listing timestamps are RFC 1123 ({@code Tue, 07 Jul 2026 10:00:00 GMT}), unlike S3's ISO instant. */
    private static Instant parseRfc1123(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(s.trim()));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    // ── connection workbench (probe · explore · sample) ─────────────────────────

    /** The {@link ConnectionWorkbench} view of an Azure Blob profile — contributed via {@link AzureBlobConnectorFactory}. */
    static ConnectionWorkbench workbench(ConnectionProfile profile) {
        return new Workbench(new AzureBlobConnector(profile));
    }

    private static final class Workbench extends AbstractObjectStoreWorkbench {
        private final AzureBlobConnector conn;

        Workbench(AzureBlobConnector conn) { this.conn = conn; }

        @Override
        String authProbe() throws AcquisitionException {
            conn.execute("GET", "/" + conn.container,
                    Map.of("restype", "container", "comp", "list", "maxresults", "1"), Map.of(), null,
                    "probe container");
            return "container listable";
        }

        @Override
        List<Entry> list(String relPrefix) throws AcquisitionException {
            String fullPrefix = conn.prefix + (relPrefix.isEmpty() ? "" : relPrefix + "/");
            List<Entry> out = new ArrayList<>();
            String marker = null;
            do {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("restype", "container");
                query.put("comp", "list");
                query.put("maxresults", String.valueOf(MAX_RESULTS_PAGE));
                query.put("delimiter", "/");
                if (!fullPrefix.isEmpty()) query.put("prefix", fullPrefix);
                if (marker != null) query.put("marker", marker);
                HttpResponse<byte[]> resp = conn.execute("GET", "/" + conn.container, query, Map.of(), null, "list blobs");
                Document doc = parseXml(resp.body());
                marker = text(doc.getDocumentElement(), "NextMarker");
                if (marker != null && marker.isBlank()) marker = null;

                NodeList bps = doc.getElementsByTagName("BlobPrefix");
                for (int i = 0; i < bps.getLength(); i++) {
                    String p = text((Element) bps.item(i), "Name");
                    if (p == null) continue;
                    String rel = p.startsWith(fullPrefix) ? p.substring(fullPrefix.length()) : p;
                    rel = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
                    if (!rel.isBlank()) out.add(new Entry(rel, true, null, null));
                }
                NodeList blobs = doc.getElementsByTagName("Blob");
                for (int i = 0; i < blobs.getLength(); i++) {
                    Element b = (Element) blobs.item(i);
                    String name = text(b, "Name");
                    if (name == null || name.equals(fullPrefix)) continue;
                    String rel = name.startsWith(fullPrefix) ? name.substring(fullPrefix.length()) : name;
                    if (rel.isBlank() || rel.contains("/")) continue;   // delimiter should already exclude these
                    long size = parseLong(text(b, "Content-Length"), RemoteFile.SIZE_UNKNOWN);
                    out.add(new Entry(rel, false, size, text(b, "Last-Modified")));
                }
            } while (marker != null);
            return out;
        }

        @Override
        InputStream openObject(String relKey, Long size) throws AcquisitionException {
            String path = "/" + conn.container + "/" + AwsSigV4.uriEncode(conn.prefix + relKey, false);
            return conn.executeStreaming(path, Map.of(), "open " + relKey).body();
        }
    }
}
