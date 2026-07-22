package com.gamma.acquire;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * Resolves credential <em>references</em> to their runtime values (Data Acquisition — connection profiles).
 *
 * <p>Per the connection-profile security decision, an {@code *_connection.toon} never stores a secret — it
 * stores a reference that this resolver expands at the moment a connector needs it:
 * <ul>
 *   <li>{@code ${ENV:NAME}} → the {@code NAME} environment variable</li>
 *   <li>{@code ${SYS:prop}} → the {@code prop} JVM system property</li>
 *   <li>{@code ${FILE:/path}} → the contents of a secret file (one trailing newline stripped) — the
 *       container/orchestrator idiom where a secret is mounted as a file (Docker/Kubernetes secrets)</li>
 *   <li>{@code ${KEYSTORE:alias}} → a {@code SecretKeyEntry} read from a Java KeyStore (SEC-8). The store
 *       is located by the JVM properties {@code secrets.keystore.path} (required),
 *       {@code secrets.keystore.type} (default {@code JCEKS} — the type that holds secret keys), and
 *       {@code secrets.keystore.password} (itself a reference, so the store password need not be in the
 *       clear, e.g. {@code ${ENV:KEYSTORE_PW}}). This is the pure-JDK, OS-independent keystore option;
 *       a Vault-backed scope is a future addition (its client is not bundled in the lean core).</li>
 *   <li>{@code ${NAME}} → environment {@code NAME}, falling back to system property {@code NAME}</li>
 *   <li>anything else → returned unchanged (a literal — discouraged, but tolerated)</li>
 * </ul>
 *
 * <p><b>Never logs a resolved value.</b> {@link #resolve} returns {@code null} when a reference cannot be
 * resolved (the source is absent or unreadable); {@link #isResolvable} answers the same question for a
 * connection test without exposing the value.
 */
public final class SecretResolver {

    private SecretResolver() {}

    /** Whether {@code s} is a {@code ${…}} reference (vs. a literal value). */
    public static boolean isReference(String s) {
        return s != null && s.startsWith("${") && s.endsWith("}") && s.length() > 3;
    }

    /** Resolve a reference (or pass a literal through); {@code null} when an env/property reference is absent. */
    public static String resolve(String ref) {
        if (ref == null) return null;
        if (!isReference(ref)) return ref;                       // literal value
        String body = ref.substring(2, ref.length() - 1).trim();
        int colon = body.indexOf(':');
        String scope = colon < 0 ? "" : body.substring(0, colon).trim().toUpperCase();
        String key   = colon < 0 ? body : body.substring(colon + 1).trim();
        if (key.isEmpty()) return null;
        return switch (scope) {
            case "ENV"      -> System.getenv(key);
            case "SYS"      -> System.getProperty(key);
            case "FILE"     -> readFile(key);
            case "KEYSTORE" -> readKeystore(key);
            default         -> {                                 // bare ${NAME}: env first, then system property
                String env = System.getenv(body);
                yield env != null ? env : System.getProperty(body);
            }
        };
    }

    /** Read a secret mounted as a file; a single trailing newline is stripped. {@code null} if absent/unreadable. */
    private static String readFile(String path) {
        try {
            String s = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            if (s.endsWith("\r\n")) return s.substring(0, s.length() - 2);
            if (s.endsWith("\n"))   return s.substring(0, s.length() - 1);
            return s;
        } catch (Exception e) {
            return null;                                         // missing/unreadable ⇒ unresolved (never logged)
        }
    }

    /** Read a {@code SecretKeyEntry} by alias from the keystore named by {@code secrets.keystore.*}. */
    private static String readKeystore(String alias) {
        String path = System.getProperty("secrets.keystore.path");
        if (path == null || path.isBlank()) return null;
        String type = System.getProperty("secrets.keystore.type", "JCEKS");
        String pwRef = System.getProperty("secrets.keystore.password");
        char[] pw = null;
        try {
            String resolvedPw = resolve(pwRef);                  // the store password may itself be a reference
            if (resolvedPw != null) pw = resolvedPw.toCharArray();
            KeyStore ks = KeyStore.getInstance(type);
            try (var in = Files.newInputStream(Path.of(path))) {
                ks.load(in, pw);
            }
            var key = ks.getKey(alias, pw);
            if (!(key instanceof SecretKey secret)) return null; // absent alias or wrong entry type
            return new String(secret.getEncoded(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;                                         // any failure ⇒ unresolved (never logged)
        } finally {
            if (pw != null) java.util.Arrays.fill(pw, '\0');     // don't leave the store password in memory
        }
    }

    /** Whether {@code ref} resolves to a non-blank value in this environment — for a connection test;
     *  a literal is trivially resolvable, a {@code ${…}} ref is resolvable only if its source is present. */
    public static boolean isResolvable(String ref) {
        if (ref == null || ref.isBlank()) return false;
        String v = resolve(ref);
        return v != null && !v.isBlank();
    }
}
