package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.SecretResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 * A <b>native Google Cloud Storage</b> {@link CollectorConnector} (ACQ-4) speaking the GCS <b>JSON API</b>
 * ({@code /storage/v1/…}) directly over the JDK {@link HttpClient}, authenticated by a service-account
 * OAuth 2.0 bearer token ({@link GcpServiceAccountToken}) — <b>no Google SDK</b>, so the air-gapped build and
 * the small SBOM are preserved (the same discipline as {@link S3Connector}/{@link AzureBlobConnector}).
 *
 * <p>This is distinct from the already-shipped S3-<em>interoperability</em> path ({@link S3Connector}, which
 * also reaches GCS but via the S3-compatible XML API + HMAC keys): a native GCS deployment issues a
 * service-account JSON key rather than interop HMAC credentials, and this connector consumes exactly that.
 *
 * <p><b>Profile mapping</b> ({@code *_connection.toon}, {@code connector: gcs}): {@code base_path} =
 * {@code bucket[/prefix]}; {@code password} = the service-account key file content (a {@link SecretResolver}
 * reference, typically {@code ${FILE:/secure/gcs-sa.json}}, resolved once at construction, never logged);
 * {@code options.scope} (default {@link GcpServiceAccountToken#DEFAULT_SCOPE}). {@code host}/{@code port}/
 * {@code options.protocol} override the endpoint (default {@code https://storage.googleapis.com}) — used by
 * tests and any private-endpoint deployment; production leaves them unset.
 *
 * <p>Listings come from Objects: list (paginated via {@code nextPageToken}) and carry each object's {@code etag}
 * — populated onto {@link RemoteFile#etag()} so {@code source.duplicate.mode: etag} (ACQ-7) can skip unchanged
 * objects without downloading. Objects are atomic in GCS — a listed object is complete — so {@link #readiness}
 * is always {@code READY}. MOVE/RENAME are rewrite/copy + delete (object storage has no rename); TAG maps to
 * GCS custom object metadata (a metadata PATCH), the native equivalent of S3 object tags.
 */
public final class GcsConnector implements CollectorConnector {

    private static final int MAX_RESULTS_PAGE = 1000;

    private final String bucket;
    private final String prefix;      // object-name prefix under the bucket, "" when base_path is just the bucket
    private final URI endpoint;       // scheme://host[:port], no path — default https://storage.googleapis.com
    private final HttpClient http;
    private final GcpServiceAccountToken token;

    public GcsConnector(ConnectionProfile profile) {
        String bp = profile.basePath() == null ? "" : profile.basePath().trim();
        String stripped = bp.startsWith("/") ? bp.substring(1) : bp;
        if (stripped.isBlank())
            throw new IllegalArgumentException("gcs connection '" + profile.id() + "' needs base_path = bucket[/prefix]");
        int slash = stripped.indexOf('/');
        this.bucket = slash < 0 ? stripped : stripped.substring(0, slash);
        String p = slash < 0 ? "" : stripped.substring(slash + 1);
        this.prefix = p.isBlank() ? "" : (p.endsWith("/") ? p : p + "/");
        this.endpoint = resolveEndpoint(profile);
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

        String saJson = SecretResolver.resolve(profile.password());
        if (saJson == null || saJson.isBlank())
            throw new IllegalArgumentException("gcs connection '" + profile.id() + "' needs password = the"
                    + " service-account key file content (e.g. ${FILE:/secure/gcs-sa.json})");
        this.token = new GcpServiceAccountToken(saJson, profile.options().get("scope"), http);
    }

    /** Endpoint override for tests / private deployments; default is public GCS. */
    private static URI resolveEndpoint(ConnectionProfile profile) {
        if (profile.host() == null || profile.host().isBlank())
            return URI.create("https://storage.googleapis.com");
        String protocol = profile.options().getOrDefault("protocol", "https");
        int port = profile.port();
        return URI.create(protocol + "://" + profile.host() + (port > 0 ? ":" + port : ""));
    }

    @Override
    public String scheme() {
        return "gcs";
    }

    @Override
    public EnumSet<Capability> capabilities() {
        return EnumSet.of(STREAM, RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, RENAME, TAG, ETAG);
    }

    @Override
    public List<RemoteFile> discover(DiscoveryContext ctx) throws AcquisitionException {
        PatternFilter filter = new PatternFilter(ctx.includes(), ctx.excludes());
        List<RemoteFile> out = new ArrayList<>();
        String pageToken = null;
        do {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("maxResults", String.valueOf(MAX_RESULTS_PAGE));
            if (!prefix.isEmpty()) query.put("prefix", prefix);
            if (pageToken != null) query.put("pageToken", pageToken);
            HttpResponse<byte[]> resp = execute("GET", "/storage/v1/b/" + enc(bucket) + "/o", query,
                    Map.of(), null, "list objects");
            JsonObject doc = parseJson(resp.body());
            pageToken = str(doc, "nextPageToken");
            collectItems(doc, filter, ctx, out);
        } while (pageToken != null);
        return out;
    }

    private void collectItems(JsonObject doc, PatternFilter filter, DiscoveryContext ctx, List<RemoteFile> out) {
        if (!(doc.get("items") instanceof JsonArray items)) return;
        for (int i = 0; i < items.size(); i++) {
            JsonObject o = items.get(i).getAsJsonObject();
            String name = str(o, "name");
            if (name == null || name.endsWith("/")) continue;   // zero-byte "directory" placeholder objects
            String rel = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
            if (rel.isBlank() || !filter.accepts(rel)) continue;
            // Depth semantics mirror the local walk: bucket[/prefix] root is depth 0, "a.csv" 1, "d/a.csv" 2.
            if (ctx.bounded() && rel.split("/").length > ctx.maxDepth()) continue;
            long size = parseLong(str(o, "size"), RemoteFile.SIZE_UNKNOWN);   // GCS returns size as a JSON string
            Instant mtime = parseInstant(str(o, "updated"));
            out.add(new RemoteFile(nameOf(rel), rel, size, mtime, str(o, "etag"), str(o, "generation"), null));
        }
    }

    /** A listed GCS object is a complete object — GCS writes are atomic, so no stabilization needed. */
    @Override
    public Readiness readiness(RemoteFile file) {
        return Readiness.READY;
    }

    @Override
    public InputStream open(RemoteFile file) throws AcquisitionException {
        HttpResponse<InputStream> resp = executeStreaming(objectPath(file), Map.of("alt", "media"),
                Map.of(), "open " + file.relativePath());
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
            HttpResponse<InputStream> resp = executeStreaming(objectPath(file), Map.of("alt", "media"),
                    headers, "fetch " + file.relativePath());
            boolean append = offset > 0 && resp.statusCode() == 206;   // server honoured the range
            try (InputStream in = resp.body();
                 var out = append
                         ? Files.newOutputStream(dest, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                         : Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                in.transferTo(out);
            }
            return dest;
        } catch (IOException e) {
            throw new AcquisitionException("GCS fetch failed for " + file.relativePath() + " → " + dest, e);
        }
    }

    @Override
    public void post(RemoteFile file, PostAction action) throws AcquisitionException {
        switch (action.kind()) {
            case RETAIN -> { /* leave it */ }
            case DELETE -> execute("DELETE", objectPath(file), Map.of(), Map.of(), null, "delete " + file.relativePath());
            case MOVE -> copyThenDelete(file, join(action.archiveTemplate(), file.relativePath()));
            case RENAME -> {
                String rel = file.relativePath();
                int i = rel.lastIndexOf('/');
                copyThenDelete(file, (i < 0 ? "" : rel.substring(0, i + 1)) + "processed_" + file.name());
            }
            case TAG -> putMetadata(file, action.tags());
        }
    }

    /** Object storage has no rename: copyTo the new object, then delete the old. */
    private void copyThenDelete(RemoteFile file, String newRel) throws AcquisitionException {
        String copyTo = "/storage/v1/b/" + enc(bucket) + "/o/" + enc(prefix + file.relativePath())
                + "/copyTo/b/" + enc(bucket) + "/o/" + enc(prefix + newRel);
        execute("POST", copyTo, Map.of(), Map.of(), null, "copy " + file.relativePath() + " → " + newRel);
        execute("DELETE", objectPath(file), Map.of(), Map.of(), null, "delete " + file.relativePath());
    }

    /** TAG maps to GCS custom object metadata (a metadata PATCH), the native equivalent of S3 object tags. */
    private void putMetadata(RemoteFile file, Map<String, String> tags) throws AcquisitionException {
        StringBuilder json = new StringBuilder("{\"metadata\":{");
        boolean first = true;
        for (Map.Entry<String, String> t : tags.entrySet()) {
            if (!first) json.append(',');
            json.append('"').append(escapeJson(t.getKey())).append("\":\"").append(escapeJson(t.getValue())).append('"');
            first = false;
        }
        json.append("}}");
        execute("PATCH", objectPath(file), Map.of(), Map.of("Content-Type", "application/json"),
                json.toString().getBytes(StandardCharsets.UTF_8), "tag " + file.relativePath());
    }

    // ── HTTP + auth ───────────────────────────────────────────────────────────

    /** Execute a bearer-authenticated request whose response is small (listing JSON, copy/delete acks). */
    private HttpResponse<byte[]> execute(String method, String path, Map<String, String> query,
                                         Map<String, String> headers, byte[] body, String what)
            throws AcquisitionException {
        try {
            HttpRequest req = authed(method, path, query, headers, body);
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2)
                throw new AcquisitionException("GCS " + what + " failed: HTTP " + resp.statusCode()
                        + errorDetail(resp.body()));
            return resp;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AcquisitionException("GCS " + what + " failed on " + endpoint.getHost() + ": " + e.getMessage(), e);
        }
    }

    /** Execute a bearer-authenticated GET whose body should stream (object reads). */
    private HttpResponse<InputStream> executeStreaming(String path, Map<String, String> query,
                                                       Map<String, String> headers, String what)
            throws AcquisitionException {
        try {
            HttpRequest req = authed("GET", path, query, headers, null);
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                byte[] err;
                try (InputStream in = resp.body()) { err = in.readNBytes(2048); }
                throw new AcquisitionException("GCS " + what + " failed: HTTP " + resp.statusCode() + errorDetail(err));
            }
            return resp;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AcquisitionException("GCS " + what + " failed on " + endpoint.getHost() + ": " + e.getMessage(), e);
        }
    }

    private HttpRequest authed(String method, String encodedPath, Map<String, String> query,
                               Map<String, String> headers, byte[] body) throws IOException {
        StringBuilder qs = new StringBuilder();
        query.forEach((k, v) -> {
            if (!qs.isEmpty()) qs.append('&');
            qs.append(enc(k)).append('=').append(enc(v));
        });
        URI uri = URI.create(endpoint + encodedPath + (qs.isEmpty() ? "" : "?" + qs));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token.bearer())
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(b::header);
        return b.build();
    }

    /** The JSON-API request path for an object: {@code /storage/v1/b/{bucket}/o/{prefix+rel}}, name encoded once. */
    private String objectPath(RemoteFile file) {
        return "/storage/v1/b/" + enc(bucket) + "/o/" + enc(prefix + file.relativePath());
    }

    // ── JSON + small helpers ──────────────────────────────────────────────────

    /** GCS JSON-API object/query names are path segments: encode fully, including {@code /} → {@code %2F}. */
    private static String enc(String s) {
        return AwsSigV4.uriEncode(s, true);
    }

    private static JsonObject parseJson(byte[] body) throws AcquisitionException {
        try {
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new AcquisitionException("Cannot parse GCS listing JSON: " + e.getMessage(), e);
        }
    }

    private static String str(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
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

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b;
        String left = a.endsWith("/") ? a.substring(0, a.length() - 1) : a;
        return left + "/" + (b.startsWith("/") ? b.substring(1) : b);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
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

    /** The {@link ConnectionWorkbench} view of a GCS profile — contributed via {@link GcsConnectorFactory}. */
    static ConnectionWorkbench workbench(ConnectionProfile profile) {
        return new Workbench(new GcsConnector(profile));
    }

    private static final class Workbench extends AbstractObjectStoreWorkbench {
        private final GcsConnector conn;

        Workbench(GcsConnector conn) { this.conn = conn; }

        @Override
        String authProbe() throws AcquisitionException {
            conn.execute("GET", "/storage/v1/b/" + enc(conn.bucket) + "/o", Map.of("maxResults", "1"),
                    Map.of(), null, "probe bucket");
            return "bucket listable";
        }

        @Override
        List<Entry> list(String relPrefix) throws AcquisitionException {
            String fullPrefix = conn.prefix + (relPrefix.isEmpty() ? "" : relPrefix + "/");
            List<Entry> out = new ArrayList<>();
            String pageToken = null;
            do {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("maxResults", String.valueOf(MAX_RESULTS_PAGE));
                query.put("delimiter", "/");
                if (!fullPrefix.isEmpty()) query.put("prefix", fullPrefix);
                if (pageToken != null) query.put("pageToken", pageToken);
                HttpResponse<byte[]> resp = conn.execute("GET", "/storage/v1/b/" + enc(conn.bucket) + "/o", query,
                        Map.of(), null, "list objects");
                JsonObject doc = parseJson(resp.body());
                pageToken = str(doc, "nextPageToken");

                if (doc.get("prefixes") instanceof JsonArray prefixes) {
                    for (int i = 0; i < prefixes.size(); i++) {
                        String p = prefixes.get(i).getAsString();
                        String rel = p.startsWith(fullPrefix) ? p.substring(fullPrefix.length()) : p;
                        rel = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
                        if (!rel.isBlank()) out.add(new Entry(rel, true, null, null));
                    }
                }
                if (doc.get("items") instanceof JsonArray items) {
                    for (int i = 0; i < items.size(); i++) {
                        JsonObject o = items.get(i).getAsJsonObject();
                        String name = str(o, "name");
                        if (name == null || name.equals(fullPrefix)) continue;
                        String rel = name.startsWith(fullPrefix) ? name.substring(fullPrefix.length()) : name;
                        if (rel.isBlank() || rel.contains("/")) continue;   // delimiter should already exclude these
                        long size = parseLong(str(o, "size"), RemoteFile.SIZE_UNKNOWN);
                        out.add(new Entry(rel, false, size, str(o, "updated")));
                    }
                }
            } while (pageToken != null);
            return out;
        }

        @Override
        InputStream openObject(String relKey, Long size) throws AcquisitionException {
            String path = "/storage/v1/b/" + enc(conn.bucket) + "/o/" + enc(conn.prefix + relKey);
            return conn.executeStreaming(path, Map.of("alt", "media"), Map.of(), "open " + relKey).body();
        }
    }
}
