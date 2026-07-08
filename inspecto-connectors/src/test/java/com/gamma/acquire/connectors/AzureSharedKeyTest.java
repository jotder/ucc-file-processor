package com.gamma.acquire.connectors;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AzureSharedKey} signing, pinned offline: the exact 2009-09-19 string-to-sign (positional headers,
 * zero Content-Length as empty string, empty Date with x-ms-date carrying time), canonicalized x-ms-* header
 * sorting, canonicalized-resource query handling, and the Authorization/header envelope of {@code sign}.
 */
class AzureSharedKeyTest {

    /** Azurite's published well-known dev key — a valid base64 HMAC key for offline signing. */
    private static final String DEV_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @Test
    void stringToSignPinnedByteForByte() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-ms-date", "Tue, 07 Jul 2026 10:00:00 GMT");
        headers.put("x-ms-version", "2021-08-06");
        URI uri = URI.create("http://127.0.0.1:10000/container?restype=container&comp=list&prefix=in%2F");

        String sts = AzureSharedKey.stringToSign("GET", uri, headers, 0, "acct");
        assertEquals("GET\n"
                        + "\n"                                    // Content-Encoding
                        + "\n"                                    // Content-Language
                        + "\n"                                    // Content-Length: zero signs as empty
                        + "\n"                                    // Content-MD5
                        + "\n"                                    // Content-Type
                        + "\n"                                    // Date: empty — x-ms-date carries the time
                        + "\n\n\n\n\n"                            // If-* and Range
                        + "x-ms-date:Tue, 07 Jul 2026 10:00:00 GMT\n"
                        + "x-ms-version:2021-08-06\n"
                        + "/acct/container"
                        + "\ncomp:list"                            // query: lowercase names, sorted, decoded values
                        + "\nprefix:in/"
                        + "\nrestype:container",
                sts);
    }

    @Test
    void nonZeroContentLengthAndStandardHeadersSignPositionally() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/xml");
        headers.put("Range", "bytes=100-");
        headers.put("x-ms-date", "Tue, 07 Jul 2026 10:00:00 GMT");
        String sts = AzureSharedKey.stringToSign("PUT",
                URI.create("https://acct.blob.core.windows.net/c/b.csv"), headers, 42, "acct");
        assertTrue(sts.startsWith("PUT\n\n\n42\n\napplication/xml\n"), "length + content-type positional:\n" + sts);
        assertTrue(sts.contains("\nbytes=100-\n"), "Range positional:\n" + sts);
        assertTrue(sts.endsWith("/acct/c/b.csv"), "no query → bare resource:\n" + sts);
    }

    @Test
    void xmsHeadersLowercasedAndSorted() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-MS-Version", "2021-08-06");
        headers.put("x-ms-copy-source", "https://a/b");
        headers.put("Content-Type", "text/plain");   // not x-ms-* → excluded here (signs positionally)
        assertEquals("x-ms-copy-source:https://a/b\nx-ms-version:2021-08-06\n",
                AzureSharedKey.canonicalizedHeaders(headers));
    }

    @Test
    void canonicalizedResourceSortsAndJoinsMultiValues() {
        URI uri = URI.create("http://h/c?b=2&a=1&b=10");
        assertEquals("/acct/c\na:1\nb:10,2", AzureSharedKey.canonicalizedResource("acct", uri),
                "names sorted; multi-values sorted then comma-joined");
        assertEquals("/acct/", AzureSharedKey.canonicalizedResource("acct", URI.create("http://h")),
                "empty path canonicalizes as /");
    }

    @Test
    void signEnvelopeCarriesDateVersionAndSharedKeyAuthorization() {
        Map<String, String> out = AzureSharedKey.sign("GET",
                URI.create("http://127.0.0.1:10000/devstoreaccount1/c?comp=list"),
                Map.of(), 0, Instant.parse("2026-07-07T10:00:00Z"), "devstoreaccount1", DEV_KEY);
        assertEquals("Tue, 7 Jul 2026 10:00:00 GMT", out.get("x-ms-date").replace(" 07 ", " 7 "),
                "x-ms-date is RFC 1123 GMT");
        assertEquals(AzureSharedKey.SERVICE_VERSION, out.get("x-ms-version"));
        String auth = out.get("Authorization");
        assertTrue(auth.startsWith("SharedKey devstoreaccount1:"), auth);
        // the signature decodes as base64 and is 32 bytes (HMAC-SHA256)
        assertEquals(32, Base64.getDecoder().decode(auth.substring(auth.indexOf(':') + 1)).length);
        // deterministic: same inputs → same signature
        assertEquals(auth, AzureSharedKey.sign("GET",
                URI.create("http://127.0.0.1:10000/devstoreaccount1/c?comp=list"),
                Map.of(), 0, Instant.parse("2026-07-07T10:00:00Z"), "devstoreaccount1", DEV_KEY).get("Authorization"));
    }
}
