package com.gamma.acquire.connectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Azure Storage <b>Shared Key</b> request signing (ACQ-4) — the Blob-service authorization scheme, hand-rolled
 * on JDK crypto exactly like {@link AwsSigV4}: canonicalized headers + canonicalized resource → string-to-sign →
 * {@code HMAC-SHA256} with the base64-decoded account key → {@code Authorization: SharedKey account:signature}.
 * No Azure SDK; the air-gapped build and small SBOM are preserved.
 *
 * <p>Implements the 2009-09-19+ Blob/Queue/File format (the current one) with {@code x-ms-date} carrying the
 * request time (the {@code Date} element in the string-to-sign is left empty, per the spec) and the
 * ≥2015-02-21 rule that a zero {@code Content-Length} signs as the <em>empty string</em>.
 *
 * <p>Stateless and pure: everything derives from the arguments, so signing is unit-testable offline.
 */
final class AzureSharedKey {

    /** The {@code x-ms-version} sent + signed with every request — a stable, widely-supported service version. */
    static final String SERVICE_VERSION = "2021-08-06";

    private static final DateTimeFormatter RFC1123_GMT =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneId.of("GMT"));

    private AzureSharedKey() {}

    /**
     * Sign one request. Returns the full header map to put on the wire: the caller's {@code headers} plus
     * {@code x-ms-date}, {@code x-ms-version} and {@code Authorization}.
     *
     * @param method        HTTP verb (upper-cased into the string-to-sign)
     * @param uri           the exact request URI (path + query are canonicalized from it)
     * @param headers       request headers to send and sign (standard ones like {@code Range}/{@code Content-Type}
     *                      sign positionally; {@code x-ms-*} ones via the canonicalized-headers block)
     * @param contentLength the body length (0 signs as the empty string, per ≥2015-02-21)
     * @param when          request time → {@code x-ms-date} (RFC 1123, GMT)
     * @param account       the storage account name
     * @param base64Key     the account access key (base64, as the portal hands it out)
     */
    static Map<String, String> sign(String method, URI uri, Map<String, String> headers, long contentLength,
                                    Instant when, String account, String base64Key) {
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.put("x-ms-date", RFC1123_GMT.format(when));
        all.put("x-ms-version", SERVICE_VERSION);
        String stringToSign = stringToSign(method, uri, all, contentLength, account);
        all.put("Authorization", "SharedKey " + account + ":" + hmacBase64(base64Key, stringToSign));
        return all;
    }

    /** The exact 2009-09-19-format string-to-sign (package-visible so tests pin it byte-for-byte). */
    static String stringToSign(String method, URI uri, Map<String, String> headers, long contentLength,
                               String account) {
        return method.toUpperCase(Locale.ROOT) + "\n"
                + header(headers, "Content-Encoding") + "\n"
                + header(headers, "Content-Language") + "\n"
                + (contentLength > 0 ? Long.toString(contentLength) : "") + "\n"
                + header(headers, "Content-MD5") + "\n"
                + header(headers, "Content-Type") + "\n"
                + "\n"                                   // Date — empty: x-ms-date carries the time
                + header(headers, "If-Modified-Since") + "\n"
                + header(headers, "If-Match") + "\n"
                + header(headers, "If-None-Match") + "\n"
                + header(headers, "If-Unmodified-Since") + "\n"
                + header(headers, "Range") + "\n"
                + canonicalizedHeaders(headers)
                + canonicalizedResource(account, uri);
    }

    /** The {@code x-ms-*} headers: names lower-cased, sorted, {@code name:value\n} each (2009-09-19 format). */
    static String canonicalizedHeaders(Map<String, String> headers) {
        TreeMap<String, String> xms = new TreeMap<>();
        headers.forEach((k, v) -> {
            String lk = k.toLowerCase(Locale.ROOT);
            if (lk.startsWith("x-ms-")) xms.put(lk, v == null ? "" : v.trim());
        });
        StringBuilder b = new StringBuilder();
        xms.forEach((k, v) -> b.append(k).append(':').append(v).append('\n'));
        return b.toString();
    }

    /**
     * {@code /account} + the encoded URI path, then every query parameter — names lower-cased, sorted,
     * values decoded (multi-values comma-joined) — appended as {@code \nname:value} (2009-09-19 format).
     */
    static String canonicalizedResource(String account, URI uri) {
        String path = uri.getRawPath();
        StringBuilder b = new StringBuilder("/").append(account).append(path == null || path.isEmpty() ? "/" : path);
        String rq = uri.getRawQuery();
        if (rq != null && !rq.isEmpty()) {
            TreeMap<String, List<String>> params = new TreeMap<>();
            for (String pair : rq.split("&")) {
                if (pair.isEmpty()) continue;
                int i = pair.indexOf('=');
                String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
                String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
                params.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
            }
            params.forEach((k, vs) -> {
                vs.sort(null);
                b.append('\n').append(k).append(':').append(String.join(",", vs));
            });
        }
        return b.toString();
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet())
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue() == null ? "" : e.getValue().trim();
        return "";
    }

    private static String hmacBase64(String base64Key, String stringToSign) {
        try {
            byte[] key = Base64.getDecoder().decode(base64Key);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Azure SharedKey signing failed: " + e.getMessage(), e);
        }
    }
}
