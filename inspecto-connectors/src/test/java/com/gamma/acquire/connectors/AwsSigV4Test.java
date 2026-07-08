package com.gamma.acquire.connectors;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pins {@link AwsSigV4} to AWS's published SigV4 test-vector credentials/date, plus the S3-specific parts. */
class AwsSigV4Test {

    // The AWS SigV4 test-suite constants (docs.aws.amazon.com "Signature Version 4 test suite").
    private static final String ACCESS = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final Instant WHEN = Instant.parse("2015-08-30T12:36:00Z");

    /** The "get-vanilla" vector: GET / on example.amazonaws.com, service "service" — exact signature match. */
    @Test
    void awsPublishedVectorGetVanilla() {
        Map<String, String> signed = AwsSigV4.sign("GET", URI.create("https://example.amazonaws.com/"),
                Map.of(), null, WHEN, "us-east-1", "service", ACCESS, SECRET);
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                        + "SignedHeaders=host;x-amz-date, "
                        + "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
                signed.get("Authorization"));
        assertEquals("20150830T123600Z", signed.get("x-amz-date"));
        assertNull(signed.get("x-amz-content-sha256"), "plain SigV4 does not send the S3 payload header");
    }

    /** S3 semantics: a payload hash is sent AND signed as x-amz-content-sha256. */
    @Test
    void s3StyleSigningIncludesContentSha256() {
        Map<String, String> signed = AwsSigV4.sign("GET", URI.create("http://localhost:9000/bucket/key.csv"),
                Map.of(), AwsSigV4.EMPTY_PAYLOAD_SHA256, WHEN, "us-east-1", "s3", ACCESS, SECRET);
        assertEquals(AwsSigV4.EMPTY_PAYLOAD_SHA256, signed.get("x-amz-content-sha256"));
        assertTrue(signed.get("Authorization").contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"));
        assertTrue(signed.get("Authorization").contains("/20150830/us-east-1/s3/aws4_request"));
    }

    /** Extra request headers (e.g. x-amz-copy-source) are signed too, and the non-default port reaches Host. */
    @Test
    void extraHeadersAreSigned() {
        Map<String, String> signed = AwsSigV4.sign("PUT", URI.create("http://localhost:9000/bucket/dest.csv"),
                Map.of("x-amz-copy-source", "/bucket/src.csv"), AwsSigV4.EMPTY_PAYLOAD_SHA256,
                WHEN, "us-east-1", "s3", ACCESS, SECRET);
        assertTrue(signed.get("Authorization")
                .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-copy-source;x-amz-date"));
        assertEquals("/bucket/src.csv", signed.get("x-amz-copy-source"));
    }

    /** Query canonicalization is order-independent: the same params in any order sign identically. */
    @Test
    void queryOrderDoesNotChangeSignature() {
        Map<String, String> a = AwsSigV4.sign("GET", URI.create("https://h.example/b?prefix=in%2F&list-type=2"),
                Map.of(), AwsSigV4.EMPTY_PAYLOAD_SHA256, WHEN, "us-east-1", "s3", ACCESS, SECRET);
        Map<String, String> b = AwsSigV4.sign("GET", URI.create("https://h.example/b?list-type=2&prefix=in%2F"),
                Map.of(), AwsSigV4.EMPTY_PAYLOAD_SHA256, WHEN, "us-east-1", "s3", ACCESS, SECRET);
        assertEquals(a.get("Authorization"), b.get("Authorization"));
    }

    @Test
    void uriEncodeIsStrictRfc3986() {
        assertEquals("a-b_c.d~e", AwsSigV4.uriEncode("a-b_c.d~e", true), "unreserved chars pass through");
        assertEquals("a%20b", AwsSigV4.uriEncode("a b", true), "space is %20, never +");
        assertEquals("in/sub/f.csv", AwsSigV4.uriEncode("in/sub/f.csv", false), "path keeps '/'");
        assertEquals("in%2Fsub", AwsSigV4.uriEncode("in/sub", true), "query encodes '/'");
        assertEquals("%E2%82%AC", AwsSigV4.uriEncode("€", true), "UTF-8 bytes percent-encoded");
    }
}
