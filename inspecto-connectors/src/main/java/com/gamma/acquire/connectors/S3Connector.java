package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.SecretResolver;
import com.gamma.acquire.CollectorConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.acquire.CollectorConnector.Capability.*;

/**
 * An S3-compatible object-storage {@link CollectorConnector} (ACQ-4) speaking the S3 REST API directly over the
 * JDK {@link HttpClient} with {@link AwsSigV4} header signing — <b>no AWS SDK</b>, so the air-gapped build and
 * the small SBOM are preserved. One implementation covers AWS S3, MinIO, and any S3-compatible store
 * (GCS in interoperability mode included); requests use path-style addressing
 * ({@code https://endpoint/bucket/key}), which every S3-compatible implementation accepts.
 *
 * <p><b>Profile mapping</b> ({@code *_connection.toon}, {@code connector: s3}): {@code host}/{@code port} =
 * the endpoint; {@code username} = access key id; {@code password} = secret key (a {@link SecretResolver}
 * reference, resolved per request-signing, never logged); {@code base_path} = {@code bucket[/prefix]};
 * {@code options.region} (default {@code us-east-1}); {@code options.protocol} {@code https} (default) |
 * {@code http} (a LAN MinIO).
 *
 * <p>Listings come from ListObjectsV2 (paginated) and carry each object's {@code ETag} — populated onto
 * {@link RemoteFile#etag()}, so {@code source.duplicate.mode: etag} (ACQ-7) can skip unchanged objects without
 * downloading them. Objects are atomic in S3 — a listed key is complete — so {@link #readiness} is always
 * {@code READY} and the engine skips size/mtime stabilization. MOVE/RENAME are CopyObject+DeleteObject
 * (object storage has no rename); TAG is PutObjectTagging.
 */
public final class S3Connector implements CollectorConnector {

    private static final Logger log = LoggerFactory.getLogger(S3Connector.class);
    private static final int MAX_KEYS_PAGE = 1000;

    private final ConnectionProfile profile;
    private final String bucket;
    private final String prefix;      // key prefix under the bucket, "" when base_path is just the bucket
    private final String region;
    private final URI endpoint;       // scheme://host[:port], no path
    private final HttpClient http;

    public S3Connector(ConnectionProfile profile) {
        this.profile = profile;
        String bp = profile.basePath() == null ? "" : profile.basePath().trim();
        String stripped = bp.startsWith("/") ? bp.substring(1) : bp;
        if (stripped.isBlank())
            throw new IllegalArgumentException("s3 connection '" + profile.id() + "' needs base_path = bucket[/prefix]");
        int slash = stripped.indexOf('/');
        this.bucket = slash < 0 ? stripped : stripped.substring(0, slash);
        String p = slash < 0 ? "" : stripped.substring(slash + 1);
        this.prefix = p.isBlank() ? "" : (p.endsWith("/") ? p : p + "/");
        this.region = profile.options().getOrDefault("region", "us-east-1");
        String protocol = profile.options().getOrDefault("protocol", "https");
        int port = profile.port();
        this.endpoint = URI.create(protocol + "://" + profile.host() + (port > 0 ? ":" + port : ""));
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Override
    public String scheme() {
        return "s3";
    }

    @Override
    public EnumSet<Capability> capabilities() {
        return EnumSet.of(STREAM, RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, RENAME, TAG, ETAG);
    }

    @Override
    public List<RemoteFile> discover(DiscoveryContext ctx) throws AcquisitionException {
        PatternFilter filter = new PatternFilter(ctx.includes(), ctx.excludes());
        List<RemoteFile> out = new ArrayList<>();
        String continuation = null;
        do {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("list-type", "2");
            query.put("max-keys", String.valueOf(MAX_KEYS_PAGE));
            if (!prefix.isEmpty()) query.put("prefix", prefix);
            if (continuation != null) query.put("continuation-token", continuation);
            HttpResponse<byte[]> resp = execute("GET", "/" + bucket, query, Map.of(), null, "list objects");
            Document doc = parseXml(resp.body());
            continuation = text(doc.getDocumentElement(), "NextContinuationToken");
            if (!"true".equalsIgnoreCase(text(doc.getDocumentElement(), "IsTruncated"))) continuation = null;
            collectContents(doc, filter, ctx, out);
        } while (continuation != null);
        return out;
    }

    private void collectContents(Document doc, PatternFilter filter, DiscoveryContext ctx, List<RemoteFile> out) {
        NodeList contents = doc.getElementsByTagName("Contents");
        for (int i = 0; i < contents.getLength(); i++) {
            Element c = (Element) contents.item(i);
            String key = text(c, "Key");
            if (key == null || key.endsWith("/")) continue;   // zero-byte "directory" placeholder objects
            String rel = key.startsWith(prefix) ? key.substring(prefix.length()) : key;
            if (rel.isBlank() || !filter.accepts(rel)) continue;
            // Depth semantics mirror the local walk: bucket[/prefix] root is depth 0, "a.csv" is 1, "d/a.csv" 2.
            if (ctx.bounded() && rel.split("/").length > ctx.maxDepth()) continue;
            long size = parseLong(text(c, "Size"), RemoteFile.SIZE_UNKNOWN);
            Instant mtime = parseInstant(text(c, "LastModified"));
            String etag = unquote(text(c, "ETag"));
            out.add(new RemoteFile(nameOf(rel), rel, size, mtime, etag, null, null));
        }
    }

    /** A key visible in a listing is a complete object — S3 writes are atomic, so no stabilization needed. */
    @Override
    public Readiness readiness(RemoteFile file) {
        return Readiness.READY;
    }

    @Override
    public InputStream open(RemoteFile file) throws AcquisitionException {
        HttpResponse<InputStream> resp = executeStreaming(keyPath(file), Map.of(), "open " + file.relativePath());
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
            HttpResponse<InputStream> resp = executeStreaming(keyPath(file), headers, "fetch " + file.relativePath());
            boolean append = offset > 0 && resp.statusCode() == 206;   // server honoured the range
            try (InputStream in = resp.body();
                 var out = append
                         ? Files.newOutputStream(dest, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                         : Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                in.transferTo(out);
            }
            return dest;
        } catch (IOException e) {
            throw new AcquisitionException("S3 fetch failed for " + file.relativePath() + " → " + dest, e);
        }
    }

    @Override
    public void post(RemoteFile file, PostAction action) throws AcquisitionException {
        switch (action.kind()) {
            case RETAIN -> { /* leave it */ }
            case DELETE -> execute("DELETE", keyPath(file), Map.of(), Map.of(), null, "delete " + file.relativePath());
            case MOVE -> copyThenDelete(file, join(action.archiveTemplate(), file.relativePath()));
            case RENAME -> {
                String rel = file.relativePath();
                int i = rel.lastIndexOf('/');
                copyThenDelete(file, (i < 0 ? "" : rel.substring(0, i + 1)) + "processed_" + file.name());
            }
            case TAG -> putTags(file, action.tags());
        }
    }

    /** Object storage has no rename: MOVE/RENAME = CopyObject to the new key, then DeleteObject on the old. */
    private void copyThenDelete(RemoteFile file, String newRel) throws AcquisitionException {
        String sourceHeader = "/" + bucket + "/" + AwsSigV4.uriEncode(prefix + file.relativePath(), false);
        String destPath = "/" + bucket + "/" + AwsSigV4.uriEncode(prefix + newRel, false);
        execute("PUT", destPath, Map.of(), Map.of("x-amz-copy-source", sourceHeader), null,
                "copy " + file.relativePath() + " → " + newRel);
        execute("DELETE", keyPath(file), Map.of(), Map.of(), null, "delete " + file.relativePath());
    }

    private void putTags(RemoteFile file, Map<String, String> tags) throws AcquisitionException {
        StringBuilder xml = new StringBuilder("<Tagging><TagSet>");
        tags.forEach((k, v) -> xml.append("<Tag><Key>").append(escapeXml(k)).append("</Key><Value>")
                .append(escapeXml(v)).append("</Value></Tag>"));
        xml.append("</TagSet></Tagging>");
        execute("PUT", keyPath(file), Map.of("tagging", ""), Map.of(),
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
                throw new AcquisitionException("S3 " + what + " failed: HTTP " + resp.statusCode()
                        + errorDetail(resp.body()));
            return resp;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AcquisitionException("S3 " + what + " failed on " + endpoint.getHost() + ": " + e.getMessage(), e);
        }
    }

    /** Execute a signed GET whose body should stream (object reads). */
    private HttpResponse<InputStream> executeStreaming(String path, Map<String, String> headers, String what)
            throws AcquisitionException {
        try {
            HttpRequest req = signed("GET", path, Map.of(), headers, null);
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                byte[] err;
                try (InputStream in = resp.body()) { err = in.readNBytes(2048); }
                throw new AcquisitionException("S3 " + what + " failed: HTTP " + resp.statusCode() + errorDetail(err));
            }
            return resp;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AcquisitionException("S3 " + what + " failed on " + endpoint.getHost() + ": " + e.getMessage(), e);
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

        String secret = SecretResolver.resolve(profile.password());
        if (secret == null)
            throw new IOException("no usable secret key for s3 connection '" + profile.id() + "'");
        String payloadHash = body == null ? AwsSigV4.EMPTY_PAYLOAD_SHA256 : AwsSigV4.sha256Hex(body);
        Map<String, String> signedHeaders = AwsSigV4.sign(method, uri, headers, payloadHash,
                Instant.now(), region, "s3", profile.username(), secret);

        HttpRequest.Builder b = HttpRequest.newBuilder(uri).method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        signedHeaders.forEach(b::header);
        return b.build();
    }

    /** The request path for an object: {@code /bucket/prefix+rel}, key encoded once, '/' preserved. */
    private String keyPath(RemoteFile file) {
        return "/" + bucket + "/" + AwsSigV4.uriEncode(prefix + file.relativePath(), false);
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
            throw new AcquisitionException("Cannot parse S3 listing XML: " + e.getMessage(), e);
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

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s.trim()); } catch (java.time.format.DateTimeParseException e) { return null; }
    }

    // ── connection workbench (probe · explore · sample) ─────────────────────────

    /** The {@link ConnectionWorkbench} view of an S3 profile — contributed via {@link S3ConnectorFactory}. */
    static ConnectionWorkbench workbench(ConnectionProfile profile) {
        return new Workbench(new S3Connector(profile));
    }

    private static final class Workbench extends AbstractObjectStoreWorkbench {
        private final S3Connector conn;

        Workbench(S3Connector conn) { this.conn = conn; }

        @Override
        String authProbe() throws AcquisitionException {
            conn.execute("GET", "/" + conn.bucket, Map.of("list-type", "2", "max-keys", "1"), Map.of(), null,
                    "probe bucket");
            return "bucket listable";
        }

        @Override
        List<Entry> list(String relPrefix) throws AcquisitionException {
            String fullPrefix = conn.prefix + (relPrefix.isEmpty() ? "" : relPrefix + "/");
            List<Entry> out = new ArrayList<>();
            String continuation = null;
            do {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("list-type", "2");
                query.put("max-keys", String.valueOf(MAX_KEYS_PAGE));
                query.put("delimiter", "/");
                if (!fullPrefix.isEmpty()) query.put("prefix", fullPrefix);
                if (continuation != null) query.put("continuation-token", continuation);
                HttpResponse<byte[]> resp = conn.execute("GET", "/" + conn.bucket, query, Map.of(), null, "list objects");
                Document doc = parseXml(resp.body());
                continuation = text(doc.getDocumentElement(), "NextContinuationToken");
                if (!"true".equalsIgnoreCase(text(doc.getDocumentElement(), "IsTruncated"))) continuation = null;

                NodeList cps = doc.getElementsByTagName("CommonPrefixes");
                for (int i = 0; i < cps.getLength(); i++) {
                    String p = text((Element) cps.item(i), "Prefix");
                    if (p == null) continue;
                    String rel = p.startsWith(fullPrefix) ? p.substring(fullPrefix.length()) : p;
                    rel = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
                    if (!rel.isBlank()) out.add(new Entry(rel, true, null, null));
                }
                NodeList contents = doc.getElementsByTagName("Contents");
                for (int i = 0; i < contents.getLength(); i++) {
                    Element c = (Element) contents.item(i);
                    String key = text(c, "Key");
                    if (key == null || key.equals(fullPrefix)) continue;   // the placeholder key for the prefix itself
                    String rel = key.startsWith(fullPrefix) ? key.substring(fullPrefix.length()) : key;
                    if (rel.isBlank() || rel.contains("/")) continue;      // delimiter should already exclude these
                    long size = parseLong(text(c, "Size"), RemoteFile.SIZE_UNKNOWN);
                    out.add(new Entry(rel, false, size, text(c, "LastModified")));
                }
            } while (continuation != null);
            return out;
        }

        @Override
        InputStream openObject(String relKey, Long size) throws AcquisitionException {
            String path = "/" + conn.bucket + "/" + AwsSigV4.uriEncode(conn.prefix + relKey, false);
            return conn.executeStreaming(path, Map.of(), "open " + relKey).body();
        }
    }
}
