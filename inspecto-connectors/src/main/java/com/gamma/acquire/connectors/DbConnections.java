package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.SecretResolver;
import net.schmizz.sshj.SSHClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Opens a JDBC {@link Connection} for a {@code db} {@link ConnectionProfile}. Shared by
 * {@link DbExportConnector} (query-export source) and {@link DbConnectionWorkbench} (the probe/explore/sample
 * surface) so the URL / driver / SSH-tunnel / secret-resolution logic lives in exactly one place.
 *
 * <p>An explicit {@code options.jdbc_url} (with optional {@code options.driver}) is honoured verbatim;
 * otherwise a PostgreSQL URL is built from the profile's host/port/database, forwarded through the profile's
 * SSH bastion when it declares a {@code tunnel}. Secrets resolve through {@link SecretResolver} — plaintext
 * values never appear in config.
 */
final class DbConnections {

    /** Default PostgreSQL port used when the profile leaves {@code port} unset and no {@code jdbc_url} is given. */
    static final int DEFAULT_PG_PORT = 5432;

    private DbConnections() {}

    /** A live JDBC connection plus the SSH tunnel opened for it (null when none). The caller closes both. */
    record Handle(Connection conn, SshTunnel tunnel) {}

    /**
     * Open a connection to the profile's database. On any failure after a tunnel is opened, the tunnel is
     * closed before the exception propagates, so a failed connect never leaks a forward.
     */
    static Handle open(ConnectionProfile profile) throws SQLException {
        String driverClass = profile.options().get("driver");
        if (driverClass != null && !driverClass.isBlank()) {
            try { Class.forName(driverClass.trim()); }
            catch (ClassNotFoundException e) { throw new SQLException("JDBC driver not found: " + driverClass, e); }
        }

        SshTunnel tunnel = null;
        String url = profile.options().get("jdbc_url");
        try {
            if (url == null || url.isBlank()) {
                String host = profile.host();
                int port = profile.port() > 0 ? profile.port() : DEFAULT_PG_PORT;
                if (profile.tunnel() != null && profile.tunnel().host() != null && !profile.tunnel().host().isBlank()) {
                    // the bastion is the only SSH hop here, so host_key/known_hosts pin it directly.
                    try {
                        tunnel = SshTunnel.open(profile.tunnel(), host, port, DbConnections::sshAuth,
                                HostKeyPolicy.from(profile));
                    } catch (IOException e) {
                        throw new SQLException("SSH tunnel for DB connection '" + profile.id() + "' failed", e);
                    }
                    InetSocketAddress local = tunnel.localEndpoint();
                    host = local.getHostString();
                    port = local.getPort();
                }
                url = "jdbc:postgresql://" + host + ":" + port + "/" + profile.database();
            }
            String user = profile.username();
            String pass = SecretResolver.resolve(profile.password());
            Connection conn = (user == null) ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, pass);
            return new Handle(conn, tunnel);
        } catch (SQLException e) {
            if (tunnel != null) try { tunnel.close(); } catch (IOException ignore) { /* best effort */ }
            throw e;
        }
    }

    private static void sshAuth(SSHClient client, String user, String passwordRef) throws IOException {
        String password = SecretResolver.resolve(passwordRef);
        if (password == null) throw new IOException("no usable credential for SSH tunnel user '" + user + "'");
        client.authPassword(user, password);
    }
}
