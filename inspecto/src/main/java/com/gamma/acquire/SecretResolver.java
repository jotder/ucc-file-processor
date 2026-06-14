package com.gamma.acquire;

/**
 * Resolves credential <em>references</em> to their runtime values (Data Acquisition — connection profiles).
 *
 * <p>Per the connection-profile security decision, an {@code *_connection.toon} never stores a secret — it
 * stores a reference that this resolver expands at the moment a connector needs it:
 * <ul>
 *   <li>{@code ${ENV:NAME}} → the {@code NAME} environment variable</li>
 *   <li>{@code ${SYS:prop}} → the {@code prop} JVM system property</li>
 *   <li>{@code ${NAME}} → environment {@code NAME}, falling back to system property {@code NAME}</li>
 *   <li>anything else → returned unchanged (a literal — discouraged, but tolerated)</li>
 * </ul>
 *
 * <p><b>Never logs a resolved value.</b> {@link #resolve} returns {@code null} when a reference cannot be
 * resolved (the env var / property is absent); {@link #isResolvable} answers the same question for a
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
            case "ENV" -> System.getenv(key);
            case "SYS" -> System.getProperty(key);
            default    -> {                                      // bare ${NAME}: env first, then system property
                String env = System.getenv(body);
                yield env != null ? env : System.getProperty(body);
            }
        };
    }

    /** Whether {@code ref} resolves to a non-blank value in this environment — for a connection test;
     *  a literal is trivially resolvable, a {@code ${…}} ref is resolvable only if its source is present. */
    public static boolean isResolvable(String ref) {
        if (ref == null || ref.isBlank()) return false;
        String v = resolve(ref);
        return v != null && !v.isBlank();
    }
}
