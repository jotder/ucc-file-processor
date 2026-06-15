package com.gamma.acquire.connectors;

/**
 * TLS mode for the FTP connector (Data Acquisition — FTPS hardening). Selected by a connection profile's
 * {@code options.tls}, or defaulted by the connector factory ({@code ftp} ⇒ {@link #NONE}, {@code ftps} ⇒
 * {@link #EXPLICIT}).
 *
 * <ul>
 *   <li>{@link #NONE} — plain FTP (the legacy default).</li>
 *   <li>{@link #EXPLICIT} — FTPES: connect on the normal port (21) then upgrade with {@code AUTH TLS}.</li>
 *   <li>{@link #IMPLICIT} — TLS from the first byte, on the implicit-FTPS port (990 by default).</li>
 * </ul>
 */
enum TlsMode {
    NONE, EXPLICIT, IMPLICIT;

    /** Default control port for this mode (implicit FTPS = 990; otherwise 21). */
    int defaultPort() {
        return this == IMPLICIT ? 990 : 21;
    }

    boolean secure() {
        return this != NONE;
    }

    /** Parse {@code options.tls}; blank/unknown ⇒ {@code dflt}. Accepts explicit synonyms (auth/starttls/true). */
    static TlsMode from(String s, TlsMode dflt) {
        if (s == null || s.isBlank()) return dflt;
        return switch (s.trim().toLowerCase()) {
            case "explicit", "explicit_tls", "auth", "auth_tls", "starttls", "ftpes", "true" -> EXPLICIT;
            case "implicit", "implicit_tls" -> IMPLICIT;
            case "none", "false", "off", "plain" -> NONE;
            default -> dflt;
        };
    }
}
