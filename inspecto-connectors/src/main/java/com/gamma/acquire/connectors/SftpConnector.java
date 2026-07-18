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
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * An SFTP {@link CollectorConnector} (Data Acquisition roadmap Phase E) built on <a href="https://github.com/hierynomus/sshj">sshj</a>.
 * Lives in the optional connector module so the SSH/BouncyCastle dependencies never touch the lean core.
 *
 * <p>Reachability and credentials come from the bound {@link ConnectionProfile}: {@code host}/{@code port}
 * (default 22), {@code username}, {@code password} (a {@link SecretResolver} reference resolved at connect
 * time — never stored or logged), and {@code base_path} as the listing root. {@code options.private_key} (a
 * key file path) switches to public-key auth. An optional {@link ConnectionProfile.Tunnel SSH bastion} is
 * honoured by opening a local port-forward through it and pointing the SFTP session at the forwarded port.
 *
 * <p>The session is opened lazily on first use and held for the connector's lifetime (one per pipeline cycle),
 * then released by {@link #close()}. {@link #fetchTo} resumes a partial download (the {@link Capability#RESUMABLE}
 * contract); {@link #open} streams directly with no local copy.
 */
public final class SftpConnector implements CollectorConnector {

    private static final Logger log = LoggerFactory.getLogger(SftpConnector.class);
    private static final int DEFAULT_PORT = 22;

    private final ConnectionProfile profile;
    private final String basePath;
    private final String readyMarker;   // optional Phase-B sibling marker; null ⇒ readiness UNKNOWN

    private SSHClient ssh;
    private SFTPClient sftp;
    private SshTunnel tunnel;   // non-null only when an SSH bastion is configured

    public SftpConnector(ConnectionProfile profile, String readyMarker) {
        this.profile = profile;
        String bp = profile.basePath();
        this.basePath = (bp == null || bp.isBlank()) ? "." : bp.trim();
        this.readyMarker = (readyMarker == null || readyMarker.isBlank()) ? null : readyMarker.trim();
    }

    @Override
    public String scheme() {
        return "sftp";
    }

    @Override
    public EnumSet<Capability> capabilities() {
        return EnumSet.of(STREAM, RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, RENAME);
    }

    @Override
    public List<RemoteFile> discover(DiscoveryContext ctx) throws AcquisitionException {
        SFTPClient client = ensureConnected();
        PatternFilter filter = new PatternFilter(ctx.includes(), ctx.excludes());
        int maxDepth = ctx.bounded() ? ctx.maxDepth() : Integer.MAX_VALUE;
        List<RemoteFile> out = new ArrayList<>();
        try {
            walk(client, basePath, 1, maxDepth, filter, out);
        } catch (IOException e) {
            throw new AcquisitionException("SFTP discovery failed under " + basePath, e);
        }
        return out;
    }

    private void walk(SFTPClient client, String dir, int depth, int maxDepth,
                      PatternFilter filter, List<RemoteFile> out) throws IOException {
        List<RemoteResourceInfo> entries;
        try {
            entries = client.ls(dir);
        } catch (IOException e) {
            // A directory we can't list (e.g. permissions) shouldn't abort the whole discovery.
            log.warn("SFTP: cannot list {} — skipping: {}", dir, e.getMessage());
            return;
        }
        for (RemoteResourceInfo r : entries) {
            String name = r.getName();
            if (name.equals(".") || name.equals("..")) continue;
            if (r.isDirectory()) {
                if (depth < maxDepth) walk(client, r.getPath(), depth + 1, maxDepth, filter, out);
            } else if (r.isRegularFile() && depth <= maxDepth) {
                String rel = relativize(r.getPath());
                if (ReadyMarker.matches(readyMarker, name) || !filter.accepts(rel)) continue;
                FileAttributes a = r.getAttributes();
                long size = a != null ? a.getSize() : RemoteFile.SIZE_UNKNOWN;
                Instant mtime = (a != null && a.getMtime() > 0) ? Instant.ofEpochSecond(a.getMtime()) : null;
                out.add(new RemoteFile(name, rel, size, mtime, null, null, null));
            }
        }
    }

    @Override
    public Readiness readiness(RemoteFile file) throws AcquisitionException {
        if (readyMarker == null) return Readiness.UNKNOWN;
        SFTPClient client = ensureConnected();
        try {
            String marker = join(parentOf(remotePath(file)), ReadyMarker.apply(readyMarker, file.name()));
            return client.statExistence(marker) != null ? Readiness.READY : Readiness.NOT_READY;
        } catch (IOException e) {
            throw new AcquisitionException("SFTP readiness check failed for " + file.relativePath(), e);
        }
    }

    @Override
    public InputStream open(RemoteFile file) throws AcquisitionException {
        SFTPClient client = ensureConnected();
        try {
            net.schmizz.sshj.sftp.RemoteFile handle = client.open(remotePath(file));
            InputStream in = handle.new RemoteFileInputStream();
            // Close the underlying SFTP handle when the caller closes the stream.
            return new FilterInputStream(in) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { handle.close(); }
                }
            };
        } catch (IOException e) {
            throw new AcquisitionException("Cannot open SFTP file " + file.relativePath(), e);
        }
    }

    @Override
    public Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException {
        SFTPClient client = ensureConnected();
        try {
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            try (net.schmizz.sshj.sftp.RemoteFile handle = client.open(remotePath(file))) {
                long remoteLen = handle.length();
                long offset = 0;
                boolean append = false;
                if (Files.exists(dest)) {
                    long have = Files.size(dest);
                    if (have == remoteLen && remoteLen > 0) return dest;     // already complete
                    if (have > 0 && have < remoteLen) { offset = have; append = true; }   // resume (RESUMABLE)
                }
                try (InputStream in = handle.new RemoteFileInputStream(offset);
                     OutputStream out = append
                             ? Files.newOutputStream(dest, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                             : Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    in.transferTo(out);
                }
            }
            return dest;
        } catch (IOException e) {
            throw new AcquisitionException("SFTP fetch failed for " + file.relativePath() + " → " + dest, e);
        }
    }

    @Override
    public void post(RemoteFile file, PostAction action) throws AcquisitionException {
        SFTPClient client = ensureConnected();
        String remote = remotePath(file);
        try {
            switch (action.kind()) {
                case RETAIN -> { /* leave it */ }
                case DELETE -> client.rm(remote);
                case MOVE -> {
                    String archiveRoot = action.archiveTemplate();
                    String target = join(join(basePath, archiveRoot), file.relativePath());
                    mkdirs(client, parentOf(target));
                    client.rename(remote, target);
                }
                case RENAME -> client.rename(remote, join(parentOf(remote), "processed_" + file.name()));
                case TAG -> throw new AcquisitionException("SFTP does not support metadata tagging");
            }
        } catch (IOException e) {
            throw new AcquisitionException("SFTP post-action " + action.kind() + " failed for " + file.relativePath(), e);
        }
    }

    @Override
    public void close() throws AcquisitionException {
        IOException first = null;
        first = quietClose(sftp, first);
        first = quietClose(ssh, first);
        first = quietClose(tunnel, first);
        sftp = null; ssh = null; tunnel = null;
        if (first != null) throw new AcquisitionException("Error closing SFTP connection", first);
    }

    // ── connection ────────────────────────────────────────────────────────────

    private synchronized SFTPClient ensureConnected() throws AcquisitionException {
        if (sftp != null) return sftp;
        try {
            String host = profile.host();
            int port = profile.port() > 0 ? profile.port() : DEFAULT_PORT;
            // host_key/known_hosts pin the target SFTP server; over a bastion the fingerprint is target-specific
            // (see HostKeyPolicy.bastionView), while a known_hosts file can verify both hops.
            HostKeyPolicy hostKeys = HostKeyPolicy.from(profile);
            if (profile.tunnel() != null && profile.tunnel().host() != null && !profile.tunnel().host().isBlank()) {
                tunnel = SshTunnel.open(profile.tunnel(), host, port, this::authenticate, hostKeys.bastionView());
                InetSocketAddress local = tunnel.localEndpoint();
                host = local.getHostString();
                port = local.getPort();
            }
            ssh = SshTunnel.newClient(hostKeys);
            ssh.connect(host, port);
            authenticate(ssh, profile.username(), profile.password());
            sftp = ssh.newSFTPClient();
            return sftp;
        } catch (IOException e) {
            try { close(); } catch (AcquisitionException ignore) { /* surface the connect failure below */ }
            throw new AcquisitionException("Cannot connect SFTP to " + profile.host() + ": " + e.getMessage(), e);
        }
    }

    private void authenticate(SSHClient client, String user, String passwordRef) throws IOException {
        String keyFile = profile.options().get("private_key");
        if (keyFile != null && !keyFile.isBlank()) {
            client.authPublickey(user, keyFile.trim());
            return;
        }
        String password = SecretResolver.resolve(passwordRef);
        if (password == null) throw new IOException("no usable credential for SFTP user '" + user + "'");
        client.authPassword(user, password);
    }

    // ── path helpers ────────────────────────────────────────────────────────────

    private String remotePath(RemoteFile file) {
        return join(basePath, file.relativePath());
    }

    private String relativize(String fullPath) {
        String base = basePath.equals(".") ? "" : trimSlashes(basePath);
        String p = trimLeadingSlash(fullPath);
        if (!base.isEmpty() && p.startsWith(base + "/")) return p.substring(base.length() + 1);
        if (!base.isEmpty() && p.equals(base)) return "";
        return p;
    }

    private void mkdirs(SFTPClient client, String dir) throws IOException {
        if (dir == null || dir.isBlank() || dir.equals(".")) return;
        client.mkdirs(dir);
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank() || a.equals(".")) return b;
        if (b == null || b.isBlank()) return a;
        return trimTrailingSlash(a) + "/" + trimLeadingSlash(b);
    }

    private static String parentOf(String path) {
        String p = trimTrailingSlash(path);
        int i = p.lastIndexOf('/');
        return i <= 0 ? "." : p.substring(0, i);
    }

    private static String trimSlashes(String s) { return trimTrailingSlash(trimLeadingSlash(s)); }
    private static String trimLeadingSlash(String s) { return s.startsWith("/") ? s.substring(1) : s; }
    private static String trimTrailingSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

    private static IOException quietClose(AutoCloseable c, IOException first) {
        if (c == null) return first;
        try { c.close(); return first; }
        catch (Exception e) { return first != null ? first : new IOException(e); }
    }

    // ── connection workbench (probe · explore · sample) ─────────────────────────

    /** The {@link ConnectionWorkbench} view of an SFTP profile — contributed via {@link SftpConnectorFactory}. */
    static ConnectionWorkbench workbench(ConnectionProfile profile) {
        return new Workbench(new SftpConnector(profile, null));
    }

    private static final class Workbench extends AbstractRemoteWorkbench {
        private final SftpConnector conn;

        Workbench(SftpConnector conn) { this.conn = conn; }

        @Override
        String connect() throws AcquisitionException {
            conn.ensureConnected();
            return "SSH auth ok";
        }

        @Override
        List<Entry> list(String relDir) throws AcquisitionException {
            SFTPClient client = conn.ensureConnected();
            String dir = join(conn.basePath, relDir);
            try {
                List<Entry> out = new ArrayList<>();
                for (RemoteResourceInfo r : client.ls(dir.isBlank() ? "." : dir)) {
                    String name = r.getName();
                    if (name.equals(".") || name.equals("..")) continue;
                    FileAttributes a = r.getAttributes();
                    Long size = (a != null && !r.isDirectory()) ? a.getSize() : null;
                    Instant mtime = (a != null && a.getMtime() > 0) ? Instant.ofEpochSecond(a.getMtime()) : null;
                    out.add(new Entry(name, r.isDirectory(), size, mtime));
                }
                return out;
            } catch (IOException e) {
                throw new AcquisitionException("SFTP cannot list '" + relDir + "': " + e.getMessage(), e);
            }
        }

        @Override
        void scratchWriteDelete() throws AcquisitionException {
            SFTPClient client = conn.ensureConnected();
            String scratch = join(conn.basePath, ".inspecto-probe-" + System.nanoTime() + ".tmp");
            try {
                try (net.schmizz.sshj.sftp.RemoteFile h = client.open(scratch,
                        EnumSet.of(OpenMode.CREAT, OpenMode.WRITE))) {
                    byte[] probe = "probe".getBytes(StandardCharsets.UTF_8);
                    h.write(0, probe, 0, probe.length);
                }
                client.rm(scratch);
            } catch (IOException e) {
                try { client.rm(scratch); } catch (IOException ignore) { /* best effort */ }
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
