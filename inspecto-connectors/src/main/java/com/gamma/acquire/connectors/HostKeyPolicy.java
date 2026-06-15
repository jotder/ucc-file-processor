package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * SSH host-key verification policy for the SFTP connector and SSH tunnels (Data Acquisition — security
 * hardening). Replaces the historical accept-on-connect ({@link PromiscuousVerifier}) default with a verifier
 * driven by the bound {@link ConnectionProfile}'s {@code options}, so an operator can pin the server identity
 * and refuse an unexpected key (defence against a man-in-the-middle / changed host):
 *
 * <ul>
 *   <li>{@code host_key} — a key fingerprint (OpenSSH MD5 colon-hex, or {@code SHA256:base64}); the presented
 *       host key must match it. Best for a direct, single-host connection.</li>
 *   <li>{@code known_hosts} — a path to an OpenSSH {@code known_hosts} file; the host must have an entry there.
 *       Works across multiple hosts (e.g. a bastion <em>and</em> a target).</li>
 *   <li>{@code strict_host_key} — when {@code true} and neither of the above is set, connecting <b>fails</b>
 *       rather than silently accepting any key. Default {@code false}.</li>
 * </ul>
 *
 * <p>With none of these set (and {@code strict_host_key} unset/false) the behaviour is unchanged from before —
 * accept-on-connect — so existing profiles keep working; pinning is purely additive and opt-in.
 */
final class HostKeyPolicy {

    private static final Logger log = LoggerFactory.getLogger(HostKeyPolicy.class);

    private final String fingerprint;     // options.host_key
    private final String knownHostsPath;  // options.known_hosts
    private final boolean strict;         // options.strict_host_key

    private HostKeyPolicy(String fingerprint, String knownHostsPath, boolean strict) {
        this.fingerprint = fingerprint;
        this.knownHostsPath = knownHostsPath;
        this.strict = strict;
    }

    /** Derive the policy from a profile's {@code options} (host_key / known_hosts / strict_host_key). */
    static HostKeyPolicy from(ConnectionProfile profile) {
        Map<String, String> o = profile.options();
        return new HostKeyPolicy(
                trimToNull(o.get("host_key")),
                trimToNull(o.get("known_hosts")),
                "true".equalsIgnoreCase(o.getOrDefault("strict_host_key", "false")));
    }

    /** The legacy accept-on-connect policy (no pinning, non-strict) — used where verification is not configurable. */
    static HostKeyPolicy acceptAll() {
        return new HostKeyPolicy(null, null, false);
    }

    /**
     * A view for an intermediate <b>bastion</b> hop: the single {@code host_key} fingerprint pins the <em>target</em>
     * host (a fingerprint matches one host only), so it does not apply to the bastion; {@code known_hosts} (which
     * can list many hosts) and the {@code strict} flag still do.
     */
    HostKeyPolicy bastionView() {
        return new HostKeyPolicy(null, knownHostsPath, strict);
    }

    /** Install the configured verifier on {@code client}, before it connects. */
    void apply(SSHClient client) throws IOException {
        if (fingerprint != null) {
            client.addHostKeyVerifier(fingerprint);
        } else if (knownHostsPath != null) {
            client.loadKnownHosts(new File(knownHostsPath));
        } else if (strict) {
            throw new IOException("strict_host_key is set but no host_key fingerprint or known_hosts file is "
                    + "configured — refusing to connect without a way to verify the server identity");
        } else {
            client.addHostKeyVerifier(new PromiscuousVerifier());   // legacy accept-on-connect
            log.debug("SSH host-key verification is disabled (accept-on-connect); set host_key or known_hosts to pin it");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
