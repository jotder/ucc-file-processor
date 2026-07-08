package com.gamma.acquire.connectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 request signing (ACQ-4), implemented directly on JDK crypto — no AWS SDK, keeping
 * the connector module's dependency surface at zero for object storage. The algorithm is the documented
 * canonical-request → string-to-sign → HMAC-SHA256 key-derivation chain
 * (<a href="https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html">AWS SigV4 spec</a>);
 * {@link AwsSigV4Test} pins it to AWS's published test vectors.
 *
 * <p>S3 notes: the payload hash travels in {@code x-amz-content-sha256} (mandatory for S3, unlike other
 * services), and S3 requires the canonical URI to be encoded <em>once</em> (no double-encoding), which is what
 * {@link #uriEncode(String, boolean)} over the raw path produces. Only header-based auth is implemented — no
 * presigned URLs, no chunked upload signing (the connector reads; its only writes are small copy/tag calls).
 */
final class AwsSigV4 {

    /** SHA-256 of the empty payload — the constant for all bodyless requests (GET/DELETE/HEAD). */
    static final String EMPTY_PAYLOAD_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AwsSigV4() {}

    /**
     * Sign one request. Returns the complete header set to send: the caller's {@code headers} plus
     * {@code host}, {@code x-amz-date}, {@code x-amz-content-sha256} and {@code Authorization}. Every header
     * present in the returned map is included in the signature (S3 only mandates host/date/content-sha256,
     * and signing all of them is always valid).
     *
     * @param method        HTTP method, upper-case
     * @param uri           the full request URI (scheme/host/port/path/query)
     * @param headers       extra headers to send and sign (e.g. {@code x-amz-copy-source}); may be empty
     * @param payloadSha256 lower-case hex SHA-256 of the request body ({@link #EMPTY_PAYLOAD_SHA256} for none).
     *                      S3 mandates sending it as {@code x-amz-content-sha256}; a {@code null} hashes the
     *                      empty payload into the canonical request <em>without</em> sending/signing that
     *                      header (plain-SigV4 semantics — what AWS's published test vectors exercise).
     */
    static Map<String, String> sign(String method, URI uri, Map<String, String> headers, String payloadSha256,
                                    Instant when, String region, String service,
                                    String accessKey, String secretKey) {
        String amzDate = AMZ_DATE.format(when);
        String dateStamp = DATE_STAMP.format(when);
        String payloadHash = payloadSha256 != null ? payloadSha256 : EMPTY_PAYLOAD_SHA256;

        // Canonical headers: everything we send, lower-cased, sorted, values trimmed.
        TreeMap<String, String> canonical = new TreeMap<>();
        headers.forEach((k, v) -> canonical.put(k.toLowerCase(java.util.Locale.ROOT), v.trim()));
        canonical.put("host", hostHeader(uri));
        canonical.put("x-amz-date", amzDate);
        if (payloadSha256 != null) canonical.put("x-amz-content-sha256", payloadSha256);

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> e : canonical.entrySet()) {
            canonicalHeaders.append(e.getKey()).append(':').append(e.getValue()).append('\n');
            if (!signedHeaders.isEmpty()) signedHeaders.append(';');
            signedHeaders.append(e.getKey());
        }

        String canonicalRequest = method + '\n'
                + canonicalUri(uri) + '\n'
                + canonicalQuery(uri) + '\n'
                + canonicalHeaders + '\n'
                + signedHeaders + '\n'
                + payloadHash;

        String scope = dateStamp + '/' + region + '/' + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + '\n' + scope + '\n' + sha256Hex(canonicalRequest);

        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = hex(hmac(kSigning, stringToSign));

        Map<String, String> out = new LinkedHashMap<>(headers);
        out.put("x-amz-date", amzDate);
        if (payloadSha256 != null) out.put("x-amz-content-sha256", payloadSha256);
        out.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + '/' + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return out;
    }

    /** The Host header value: no port when it is the scheme default, else {@code host:port}. */
    private static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)
                || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80);
        return defaultPort ? uri.getHost() : uri.getHost() + ':' + port;
    }

    /** Canonical URI: the raw path, each segment URI-encoded once, '/' preserved; empty path ⇒ "/". */
    private static String canonicalUri(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) return "/";
        // The raw path is already percent-encoded by the caller (we build URIs from encoded keys);
        // pass it through so S3's single-encoding rule holds.
        return path;
    }

    /** Canonical query: params sorted by name (then value), strictly RFC 3986 encoded. */
    private static String canonicalQuery(URI uri) {
        String q = uri.getRawQuery();
        if (q == null || q.isEmpty()) return "";
        TreeMap<String, String> params = new TreeMap<>();
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            // Decode then strictly re-encode, so a caller-encoded query canonicalizes identically.
            params.put(uriEncode(urlDecode(k), true), uriEncode(urlDecode(v), true));
        }
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    /**
     * The strict RFC 3986 percent-encoding SigV4 requires: unreserved characters pass through; when
     * {@code encodeSlash} is false a {@code /} is preserved (object-key path encoding), otherwise it becomes
     * {@code %2F} (query encoding). Space is {@code %20}, never {@code +}.
     */
    static String uriEncode(String s, boolean encodeSlash) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved || (c == '/' && !encodeSlash)) sb.append(c);
            else sb.append('%').append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    static String sha256Hex(String s) {
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK without SHA-256", e);   // cannot happen
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);   // cannot happen on a JDK
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
}
