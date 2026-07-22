package com.gamma.acquire.connectors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Google Cloud service-account OAuth 2.0 access-token minting (ACQ-4, GCS <b>native</b> JSON API), implemented
 * directly on JDK crypto + {@link HttpClient} — <b>no Google SDK</b>, keeping the connector module's
 * dependency surface at zero for object storage (the same discipline as {@link AwsSigV4}/{@code AzureSharedKey}).
 *
 * <p>This is the auth mechanism that distinguishes GCS-<em>native</em> from the S3-interoperability surface
 * ({@link S3Connector} with HMAC keys): a native GCS deployment issues a <b>service-account JSON key</b>, not
 * an interop HMAC key pair. The documented
 * <a href="https://developers.google.com/identity/protocols/oauth2/service-account#jwt-auth">JWT-bearer flow</a>
 * is: build a JWT assertion (header + claim set), RS256-sign it with the service account's private key, then
 * exchange it at the account's {@code token_uri} for a short-lived bearer token. The minted token is cached and
 * reused until shortly before it expires, so a scan cycle mints once, not per request.
 *
 * <p><b>Input</b> is the service-account key <em>file's JSON content</em> (as a {@code SecretResolver} reference
 * resolves it, e.g. {@code ${FILE:/secure/gcs-sa.json}}) — the standard {@code gcloud iam service-accounts keys
 * create} output, from which {@code client_email}, {@code private_key} (PKCS#8 PEM) and {@code token_uri} are read.
 */
final class GcpServiceAccountToken {

    /** Default read/write scope for object storage; overridable via {@code options.scope}. */
    static final String DEFAULT_SCOPE = "https://www.googleapis.com/auth/devstorage.read_write";
    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final String clientEmail;
    private final String tokenUri;
    private final String scope;
    private final PrivateKey privateKey;
    private final HttpClient http;

    private String cachedToken;
    private Instant cachedUntil = Instant.MIN;

    /**
     * @param serviceAccountJson the resolved service-account key file content (never a {@code ${…}} reference)
     * @param scope              the OAuth scope, or {@code null}/blank for {@link #DEFAULT_SCOPE}
     * @param http               the shared client used for the token exchange
     */
    GcpServiceAccountToken(String serviceAccountJson, String scope, HttpClient http) {
        JsonObject sa;
        try {
            sa = JsonParser.parseString(serviceAccountJson).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("gcs service-account key is not valid JSON: " + e.getMessage(), e);
        }
        this.clientEmail = requireField(sa, "client_email");
        this.tokenUri = sa.has("token_uri") && !sa.get("token_uri").isJsonNull()
                ? sa.get("token_uri").getAsString() : "https://oauth2.googleapis.com/token";
        this.scope = scope == null || scope.isBlank() ? DEFAULT_SCOPE : scope;
        this.privateKey = parsePkcs8Pem(requireField(sa, "private_key"));
        this.http = http;
    }

    /** A valid bearer token, minted on first use and re-minted only once it is within 60s of expiry. */
    synchronized String bearer() throws IOException {
        if (cachedToken != null && Instant.now().isBefore(cachedUntil)) return cachedToken;
        return mint();
    }

    private String mint() throws IOException {
        long now = Instant.now().getEpochSecond();
        String header = B64URL.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64URL.encodeToString(("{\"iss\":\"" + clientEmail + "\",\"scope\":\"" + scope
                + "\",\"aud\":\"" + tokenUri + "\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}")
                .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        String assertion = signingInput + "." + B64URL.encodeToString(rs256(signingInput));

        String form = "grant_type=" + java.net.URLEncoder.encode(GRANT_TYPE, StandardCharsets.UTF_8)
                + "&assertion=" + assertion;   // JWT is base64url — no reserved chars, no further encoding needed
        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUri))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("gcs token exchange interrupted", e);
        }
        if (resp.statusCode() / 100 != 2)
            throw new IOException("gcs token exchange failed for " + clientEmail + ": HTTP " + resp.statusCode()
                    + errorDetail(resp.body()));

        JsonObject tok;
        try {
            tok = JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("gcs token response is not valid JSON", e);
        }
        if (!tok.has("access_token"))
            throw new IOException("gcs token response has no access_token" + errorDetail(resp.body()));
        String token = tok.get("access_token").getAsString();
        long expiresIn = tok.has("expires_in") ? tok.get("expires_in").getAsLong() : 3600L;
        cachedToken = token;
        cachedUntil = Instant.now().plusSeconds(Math.max(1, expiresIn - 60));   // renew a minute early
        return token;
    }

    /** RS256 (SHA-256 with RSA) over the JWT signing input, using the service account's private key. */
    private byte[] rs256(String signingInput) throws IOException {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return sig.sign();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("gcs JWT signing failed: " + e.getMessage(), e);
        }
    }

    /** Parse a PKCS#8 PEM private key ({@code -----BEGIN PRIVATE KEY-----}) — the format in a GCS SA key file. */
    private static PrivateKey parsePkcs8Pem(String pem) {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        if (base64.isEmpty())
            throw new IllegalArgumentException("gcs service-account private_key is empty or not PKCS#8 PEM");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            throw new IllegalArgumentException("gcs service-account private_key is not a valid PKCS#8 RSA key: "
                    + e.getMessage(), e);
        }
    }

    private static String requireField(JsonObject sa, String field) {
        if (!sa.has(field) || sa.get(field).isJsonNull() || sa.get(field).getAsString().isBlank())
            throw new IllegalArgumentException("gcs service-account key is missing '" + field + "'");
        return sa.get(field).getAsString();
    }

    private static String errorDetail(String body) {
        if (body == null || body.isBlank()) return "";
        return " — " + body.replaceAll("\\s+", " ").trim();
    }
}
