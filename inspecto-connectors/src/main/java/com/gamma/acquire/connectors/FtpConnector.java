package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.ReadyMarker;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.SecretResolver;
import com.gamma.acquire.CollectorConnector;
import net.schmizz.sshj.SSHClient;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.util.TrustManagerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static com.gamma.acquire.CollectorConnector.Capability.*;

/**
 * An FTP {@link CollectorConnector} (Data Acquisition roadmap Phase E) built on Apache
 * <a href="https://commons.apache.org/proper/commons-net/">commons-net</a>. Lives in the optional connector
 * module; commons-net never touches the lean core.
 *
 * <p>Reachability/credentials come from the bound {@link ConnectionProfile}: {@code host}/{@code port}
 * (default 21), {@code username}/{@code password} (a {@link SecretResolver} reference resolved at connect time;
 * anonymous when absent), {@code base_path} as the listing root. Passive mode is the default (firewall-friendly);
 * {@code options.active=true} switches to active mode, {@code options.binary=false} to ASCII transfers.
 *
 * <p>The control connection is opened lazily and held for the connector's lifetime. {@link #fetchTo} resumes a
 * partial download via {@code REST} when the server supports it ({@link Capability#RESUMABLE}); {@link #open}
 * streams directly.
 *
 * <p><b>FTPS</b> (TLS) is enabled via {@code options.tls} = {@code explicit} (FTPES: {@code AUTH TLS} after
 * connect) or {@code implicit} (TLS from the first byte, port 990) — or simply by binding the source to a
 * {@code connector: ftps} profile, which defaults to explicit. With TLS the data channel is also encrypted
 * ({@code PBSZ 0} + {@code PROT P}). Server certificates are validated against the JVM trust store by default;
 * {@code options.tls_trust=all} accepts any certificate (for self-signed / internal-CA servers — still
 * encrypted, but not authenticated).
 *
 * <p><b>SSH bastion.</b> An optional {@link ConnectionProfile.Tunnel} is honoured by forwarding the FTP control
 * connection through the bastion (host key verified per {@link HostKeyPolicy}). Because FTP opens <em>separate
 * passive data connections</em>, the server's passive port range must also be tunnelled: set
 * {@code options.passive_ports} (e.g. {@code "30000-30009"}) to the range the server is configured to hand out —
 * each is forwarded loopback→server and a passive NAT-workaround makes the client dial the loopback. (The server
 * must be configured with that fixed passive range.) Active mode cannot traverse a tunnel, so a tunnelled
 * connection is always passive.
 */
public final class FtpConnector implements CollectorConnector {

    private static final Logger log = LoggerFactory.getLogger(FtpConnector.class);

    private final ConnectionProfile profile;
    private final String basePath;
    private final String readyMarker;
    private final boolean passive;
    private final boolean binary;
    private final TlsMode tls;
    private final String tlsTrust;       // options.tls_trust: "all" ⇒ accept any cert; else JVM default trust
    private final int[] passivePorts;    // options.passive_ports: server's passive range, forwarded over a tunnel

    private FTPClient ftp;
    private SshTunnel tunnel;             // non-null only when an SSH bastion is configured

    public FtpConnector(ConnectionProfile profile, String readyMarker) {
        this(profile, readyMarker, TlsMode.NONE);
    }

    public FtpConnector(ConnectionProfile profile, String readyMarker, TlsMode defaultTls) {
        this.profile = profile;
        String bp = profile.basePath();
        this.basePath = (bp == null || bp.isBlank()) ? "" : trimTrailingSlash(bp.trim());
        this.readyMarker = (readyMarker == null || readyMarker.isBlank()) ? null : readyMarker.trim();
        this.passive = !"true".equalsIgnoreCase(profile.options().getOrDefault("active", "false"));
        this.binary  = !"false".equalsIgnoreCase(profile.options().getOrDefault("binary", "true"));
        this.tls     = TlsMode.from(profile.options().get("tls"), defaultTls);
        this.tlsTrust = profile.options().get("tls_trust");
        this.passivePorts = parsePorts(profile.options().get("passive_ports"));
    }

    @Override
    public String scheme() {
        return tls.secure() ? "ftps" : "ftp";
    }

    @Override
    public EnumSet<Capability> capabilities() {
        // No native streaming-without-copy guarantee beyond open(); resumable via REST; can delete/rename.
        return EnumSet.of(STREAM, RESUMABLE, DELETE, RENAME, MOVE);
    }

    @Override
    public List<RemoteFile> discover(DiscoveryContext ctx) throws AcquisitionException {
        FTPClient client = ensureConnected();
        PatternFilter filter = new PatternFilter(ctx.includes(), ctx.excludes());
        int maxDepth = ctx.bounded() ? ctx.maxDepth() : Integer.MAX_VALUE;
        List<RemoteFile> out = new ArrayList<>();
        try {
            walk(client, basePath, "", 1, maxDepth, filter, out);
        } catch (IOException e) {
            throw new AcquisitionException("FTP discovery failed under '" + basePath + "'", e);
        }
        return out;
    }

    private void walk(FTPClient client, String absDir, String relDir, int depth, int maxDepth,
                      PatternFilter filter, List<RemoteFile> out) throws IOException {
        FTPFile[] entries = client.listFiles(absDir.isEmpty() ? "." : absDir);
        for (FTPFile f : entries) {
            if (f == null) continue;
            String name = f.getName();
            if (name == null || name.equals(".") || name.equals("..")) continue;
            String rel = relDir.isEmpty() ? name : relDir + "/" + name;
            if (f.isDirectory()) {
                if (depth < maxDepth) walk(client, join(absDir, name), rel, depth + 1, maxDepth, filter, out);
            } else if (f.isFile() && depth <= maxDepth) {
                if (ReadyMarker.matches(readyMarker, name) || !filter.accepts(rel)) continue;
                long size = f.getSize() >= 0 ? f.getSize() : RemoteFile.SIZE_UNKNOWN;
                Instant mtime = f.getTimestamp() != null ? f.getTimestamp().toInstant() : null;
                out.add(new RemoteFile(name, rel, size, mtime, null, null, null));
            }
        }
    }

    @Override
    public Readiness readiness(RemoteFile file) throws AcquisitionException {
        if (readyMarker == null) return Readiness.UNKNOWN;
        FTPClient client = ensureConnected();
        try {
            String marker = join(parentOf(remotePath(file)), ReadyMarker.apply(readyMarker, file.name()));
            // listFiles on a specific path returns the entry iff it exists.
            FTPFile[] hit = client.listFiles(marker);
            return (hit != null && hit.length > 0) ? Readiness.READY : Readiness.NOT_READY;
        } catch (IOException e) {
            throw new AcquisitionException("FTP readiness check failed for " + file.relativePath(), e);
        }
    }

    @Override
    public InputStream open(RemoteFile file) throws AcquisitionException {
        FTPClient client = ensureConnected();
        try {
            InputStream in = client.retrieveFileStream(remotePath(file));
            if (in == null) throw new IOException("server refused retrieve: " + client.getReplyString());
            // The control connection needs completePendingCommand() once the data stream is drained.
            return new FilterInputStream(in) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { client.completePendingCommand(); }
                }
            };
        } catch (IOException e) {
            throw new AcquisitionException("Cannot open FTP file " + file.relativePath(), e);
        }
    }

    @Override
    public Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException {
        FTPClient client = ensureConnected();
        String remote = remotePath(file);
        try {
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            long restartAt = 0;
            boolean append = false;
            if (Files.exists(dest)) {
                long have = Files.size(dest);
                long remoteLen = file.hasSize() ? file.size() : -1;   // listing size; commons-net has no cheap stat
                if (have > 0 && remoteLen > 0 && have == remoteLen) return dest;      // already complete
                if (have > 0 && (remoteLen < 0 || have < remoteLen)) { restartAt = have; append = true; }
            }
            client.setRestartOffset(restartAt);   // REST — ignored by servers that don't support it
            try (OutputStream out = append
                    ? Files.newOutputStream(dest, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                    : Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                if (!client.retrieveFile(remote, out))
                    throw new IOException("retrieve failed: " + client.getReplyString());
            } finally {
                client.setRestartOffset(0);
            }
            return dest;
        } catch (IOException e) {
            throw new AcquisitionException("FTP fetch failed for " + file.relativePath() + " → " + dest, e);
        }
    }

    @Override
    public void post(RemoteFile file, PostAction action) throws AcquisitionException {
        FTPClient client = ensureConnected();
        String remote = remotePath(file);
        try {
            switch (action.kind()) {
                case RETAIN -> { /* leave it */ }
                case DELETE -> { if (!client.deleteFile(remote)) fail("delete", client); }
                case MOVE -> {
                    String target = join(join(basePath, action.archiveTemplate()), file.relativePath());
                    makeParents(client, parentOf(target));
                    if (!client.rename(remote, target)) fail("move", client);
                }
                case RENAME -> { if (!client.rename(remote, join(parentOf(remote), "processed_" + file.name()))) fail("rename", client); }
                case TAG -> throw new AcquisitionException("FTP does not support metadata tagging");
            }
        } catch (IOException e) {
            throw new AcquisitionException("FTP post-action " + action.kind() + " failed for " + file.relativePath(), e);
        }
    }

    @Override
    public void close() throws AcquisitionException {
        AcquisitionException err = null;
        if (ftp != null) {
            try {
                if (ftp.isConnected()) { ftp.logout(); ftp.disconnect(); }
            } catch (IOException e) {
                err = new AcquisitionException("Error closing FTP connection", e);
            } finally {
                ftp = null;
            }
        }
        if (tunnel != null) {
            try { tunnel.close(); }
            catch (IOException e) { if (err == null) err = new AcquisitionException("Error closing FTP SSH tunnel", e); }
            finally { tunnel = null; }
        }
        if (err != null) throw err;
    }

    // ── connection ────────────────────────────────────────────────────────────

    private synchronized FTPClient ensureConnected() throws AcquisitionException {
        if (ftp != null && ftp.isConnected()) return ftp;
        FTPClient client = newClient();
        String host = profile.host();
        int port = profile.port() > 0 ? profile.port() : tls.defaultPort();
        boolean tunnelled = profile.tunnel() != null && profile.tunnel().host() != null
                && !profile.tunnel().host().isBlank();
        try {
            if (tunnelled) {
                // Forward the control connection through the bastion; host_key/known_hosts pin the bastion.
                tunnel = SshTunnel.open(profile.tunnel(), host, port, FtpConnector::bastionAuth,
                        HostKeyPolicy.from(profile));
                // FTP's passive data connections need their own forwards: one loopback→server per passive port.
                for (int p : passivePorts) tunnel.addForward(p, host, p);
                if (passivePorts.length == 0)
                    log.warn("FTP source '{}' tunnels the control connection but no options.passive_ports are set "
                            + "— passive data transfers will fail unless the server's data ports are independently "
                            + "reachable. Set options.passive_ports to the server's configured passive range.",
                            profile.id());
                InetSocketAddress local = tunnel.localEndpoint();
                host = local.getHostString();
                port = local.getPort();
            } else if (profile.proxy() != null) {
                // Proxy dial-through: only meaningful direct-to-target. A bastion tunnel above already
                // rewrote host/port to a local loopback forward, so proxying that connect would defeat it.
                applyProxy(client, profile.proxy());
            }
            client.connect(host, port);   // FTPSClient negotiates AUTH TLS (explicit) / TLS-first (implicit) here
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                client.disconnect();
                throw new IOException("server refused connection: " + client.getReplyString());
            }
            String user = profile.username() != null ? profile.username() : "anonymous";
            String pass = SecretResolver.resolve(profile.password());
            if (pass == null) pass = "anonymous@";
            if (!client.login(user, pass)) {
                client.disconnect();
                throw new IOException("login failed for user '" + user + "': " + client.getReplyString());
            }
            if (client instanceof FTPSClient ftps) {
                ftps.execPBSZ(0);        // protection buffer size (0 for TLS)
                ftps.execPROT("P");      // encrypt the data channel too, not just the control channel
            }
            if (tunnelled) {
                // Ignore the server-advertised PASV address (an unreachable server-side IP) and dial the loopback
                // forwards instead. Active mode can't traverse a tunnel, so a tunnelled connection is passive.
                client.setPassiveNatWorkaroundStrategy(h -> "127.0.0.1");
                client.enterLocalPassiveMode();
            } else if (passive) {
                client.enterLocalPassiveMode();
            } else {
                client.enterLocalActiveMode();
            }
            client.setFileType(binary ? FTP.BINARY_FILE_TYPE : FTP.ASCII_FILE_TYPE);
            this.ftp = client;
            return client;
        } catch (IOException e) {
            try { if (client.isConnected()) client.disconnect(); } catch (IOException ignore) { }
            if (tunnel != null) { try { tunnel.close(); } catch (IOException ignore) { } tunnel = null; }
            throw new AcquisitionException("Cannot connect " + scheme().toUpperCase() + " to "
                    + profile.host() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Route the FTP/FTPS control connection through {@code proxy} (2026-07-24 — extends the SFTP connector's
     * 2026-07-20 proxy dial-through to commons-net). Only {@code SOCKS5} is supported: commons-net's
     * {@link org.apache.commons.net.SocketClient#setSocketFactory} lets a plain JDK {@link java.net.Socket}
     * constructed with a SOCKS {@link java.net.Proxy} tunnel the subsequent {@code connect(host, port)}
     * transparently, so {@link SocksProxySocketFactory} needs no protocol handshake of its own — and FTPS
     * negotiates its TLS session on top of that already-proxied socket. {@code HTTP} is rejected fail-closed
     * rather than silently ignored — this connector has no HTTP {@code CONNECT} handshake implementation (yet).
     */
    private void applyProxy(FTPClient client, ConnectionProfile.Proxy proxy) throws AcquisitionException {
        String type = proxy.type() == null ? "" : proxy.type().trim().toUpperCase(java.util.Locale.ROOT);
        if (!"SOCKS5".equals(type)) {
            throw new AcquisitionException(scheme().toUpperCase() + " connector supports proxy type SOCKS5 only (got '"
                    + proxy.type() + "') for profile targeting " + profile.host());
        }
        client.setSocketFactory(new SocksProxySocketFactory(proxy.host(), proxy.port()));
    }

    /** A plain {@link FTPClient}, or an {@link FTPSClient} (explicit/implicit) when {@code options.tls} is set. */
    private FTPClient newClient() {
        if (!tls.secure()) return new FTPClient();
        FTPSClient ftps = new FTPSClient(tls == TlsMode.IMPLICIT);
        if ("all".equalsIgnoreCase(tlsTrust)) ftps.setTrustManager(TrustManagerUtils.getAcceptAllTrustManager());
        return ftps;
    }

    /** Authenticate the SSH bastion with the tunnel's username + password reference (resolved at connect). */
    private static void bastionAuth(SSHClient client, String user, String passwordRef) throws IOException {
        String password = SecretResolver.resolve(passwordRef);
        if (password == null) throw new IOException("no usable credential for FTP SSH-tunnel user '" + user + "'");
        client.authPassword(user, password);
    }

    /** Parse {@code options.passive_ports} — a comma list and/or {@code a-b} ranges (e.g. {@code "30000-30009"}). */
    static int[] parsePorts(String spec) {
        if (spec == null || spec.isBlank()) return new int[0];
        List<Integer> ports = new ArrayList<>();
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            if (dash > 0) {
                int a = Integer.parseInt(part.substring(0, dash).trim());
                int b = Integer.parseInt(part.substring(dash + 1).trim());
                for (int p = Math.min(a, b); p <= Math.max(a, b); p++) ports.add(p);
            } else {
                ports.add(Integer.parseInt(part));
            }
        }
        int[] out = new int[ports.size()];
        for (int i = 0; i < out.length; i++) out[i] = ports.get(i);
        return out;
    }

    // ── path helpers ────────────────────────────────────────────────────────────

    private String remotePath(RemoteFile file) {
        return join(basePath, file.relativePath());
    }

    private static void fail(String op, FTPClient client) throws IOException {
        throw new IOException(op + " rejected: " + client.getReplyString());
    }

    private void makeParents(FTPClient client, String dir) throws IOException {
        if (dir == null || dir.isBlank() || dir.equals(".")) return;
        StringBuilder built = new StringBuilder();
        for (String seg : trimLeadingSlash(dir).split("/")) {
            if (seg.isEmpty()) continue;
            built.append(built.length() == 0 ? "" : "/").append(seg);
            client.makeDirectory(built.toString());   // ignore failures (already exists)
        }
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        return trimTrailingSlash(a) + "/" + trimLeadingSlash(b);
    }

    private static String parentOf(String path) {
        String p = trimTrailingSlash(path);
        int i = p.lastIndexOf('/');
        return i <= 0 ? "" : p.substring(0, i);
    }

    private static String trimLeadingSlash(String s) { return s.startsWith("/") ? s.substring(1) : s; }
    private static String trimTrailingSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

    // ── connection workbench (probe · explore · sample) ─────────────────────────

    /** The {@link ConnectionWorkbench} view of an FTP/FTPS profile — contributed via the two factories. */
    static ConnectionWorkbench workbench(ConnectionProfile profile, TlsMode defaultTls) {
        return new Workbench(new FtpConnector(profile, null, defaultTls));
    }

    private static final class Workbench extends AbstractRemoteWorkbench {
        private final FtpConnector conn;

        Workbench(FtpConnector conn) { this.conn = conn; }

        @Override
        String connect() throws AcquisitionException {
            conn.ensureConnected();
            return conn.scheme().toUpperCase() + " login ok";
        }

        @Override
        List<Entry> list(String relDir) throws AcquisitionException {
            FTPClient client = conn.ensureConnected();
            String dir = join(conn.basePath, relDir);
            try {
                List<Entry> out = new ArrayList<>();
                for (FTPFile f : client.listFiles(dir == null || dir.isEmpty() ? "." : dir)) {
                    if (f == null) continue;
                    String name = f.getName();
                    if (name == null || name.equals(".") || name.equals("..")) continue;
                    Long size = (f.isFile() && f.getSize() >= 0) ? f.getSize() : null;
                    Instant mtime = f.getTimestamp() != null ? f.getTimestamp().toInstant() : null;
                    out.add(new Entry(name, f.isDirectory(), size, mtime));
                }
                return out;
            } catch (IOException e) {
                throw new AcquisitionException("FTP cannot list '" + relDir + "': " + e.getMessage(), e);
            }
        }

        @Override
        void scratchWriteDelete() throws AcquisitionException {
            FTPClient client = conn.ensureConnected();
            String scratch = join(conn.basePath, ".inspecto-probe-" + System.nanoTime() + ".tmp");
            try {
                if (!client.storeFile(scratch, new ByteArrayInputStream("probe".getBytes(StandardCharsets.UTF_8))))
                    throw new IOException("store rejected: " + client.getReplyString());
                if (!client.deleteFile(scratch))
                    throw new IOException("delete rejected: " + client.getReplyString());
            } catch (IOException e) {
                try { client.deleteFile(scratch); } catch (IOException ignore) { /* best effort */ }
                throw new AcquisitionException("not writable: " + e.getMessage(), e);
            }
        }

        @Override
        InputStream openFile(String relPath, Long size) throws AcquisitionException {
            String name = relPath.substring(relPath.lastIndexOf('/') + 1);
            return conn.open(new RemoteFile(name, relPath,
                    size == null ? RemoteFile.SIZE_UNKNOWN : size, null, null, null, null));
        }

        @Override
        void closeSession() throws AcquisitionException {
            conn.close();
        }
    }
}
