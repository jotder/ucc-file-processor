package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.SecretResolver;
import com.gamma.acquire.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;

import static com.gamma.acquire.SourceConnector.Capability.STREAM;

/**
 * A <b>DB-export</b> {@link SourceConnector} (Data Acquisition — database-export source). Runs a SQL query against
 * a JDBC database and materialises the result as a CSV file, which then flows through the normal Inspecto batch
 * path exactly like any other acquired file. Lives in the optional {@code inspecto-connectors} module; the
 * PostgreSQL driver ships here, but the connector is JDBC-generic — any driver on the classpath works (the tests
 * drive it against an embedded DuckDB).
 *
 * <h3>Configuration (in the bound {@link ConnectionProfile})</h3>
 * <ul>
 *   <li>{@code jdbc_url} — explicit JDBC URL; or omit and the connector builds
 *       {@code jdbc:postgresql://host:port/database} from the profile.</li>
 *   <li>{@code query} <b>(required)</b> — the SQL to export. Supports {@code {pattern}} date tokens (a Java
 *       {@link DateTimeFormatter} pattern) resolved against "now", e.g.
 *       {@code SELECT * FROM cdr WHERE d = '{yyyy-MM-dd}'} — so each cycle exports a fresh time slice.</li>
 *   <li>{@code export_name} <b>(required)</b> — the output file name, also date-templated, e.g.
 *       {@code cdr_{yyyyMMdd}.csv}. The stable per-slice name lets the engine's marker/ledger dedup re-export the
 *       same slice only once.</li>
 *   <li>{@code driver} — optional explicit JDBC driver class to load.</li>
 *   <li>{@code tunnel} — an optional SSH bastion (host/port path only); the connector forwards a loopback port and
 *       points the JDBC URL at it.</li>
 * </ul>
 *
 * <p>The result is a single logical "file" per cycle, so this connector is {@link Capability#STREAM}-only — there
 * is no source-side file to delete/move/rename, and (with {@code source.post_action} unset) nothing tries to.
 */
public final class DbExportConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(DbExportConnector.class);
    private static final int DEFAULT_PG_PORT = 5432;

    private final ConnectionProfile profile;
    private final String queryTemplate;
    private final String nameTemplate;
    private final String driverClass;   // optional explicit driver

    private Connection conn;
    private SshTunnel tunnel;

    public DbExportConnector(ConnectionProfile profile) {
        this.profile = profile;
        this.queryTemplate = profile.options().get("query");
        this.nameTemplate = profile.options().get("export_name");
        this.driverClass = profile.options().get("driver");
        if (queryTemplate == null || queryTemplate.isBlank())
            throw new IllegalArgumentException("db-export connection '" + profile.id() + "' needs options.query");
        if (nameTemplate == null || nameTemplate.isBlank())
            throw new IllegalArgumentException("db-export connection '" + profile.id() + "' needs options.export_name");
    }

    @Override
    public String scheme() {
        return "db";
    }

    @Override
    public EnumSet<Capability> capabilities() {
        return EnumSet.of(STREAM);   // a query result has no source-side file to delete/move/rename
    }

    @Override
    public List<RemoteFile> discover(DiscoveryContext ctx) {
        // One logical export per cycle: the resolved export_name. No DB access here (discovery stays cheap).
        String name = resolveTokens(nameTemplate, ZonedDateTime.now());
        PatternFilter filter = new PatternFilter(ctx.includes(), ctx.excludes());
        if (!filter.accepts(name)) return List.of();
        return List.of(new RemoteFile(name, name, RemoteFile.SIZE_UNKNOWN, java.time.Instant.now(), null, null, null));
    }

    @Override
    public Readiness readiness(RemoteFile file) {
        return Readiness.READY;   // a query is always ready to run
    }

    @Override
    public InputStream open(RemoteFile file) throws AcquisitionException {
        // STREAM via a self-deleting temp file (the result must be materialised to run the query once).
        try {
            Path tmp = Files.createTempFile("dbexport-", ".csv");
            fetchTo(file, tmp);
            InputStream in = Files.newInputStream(tmp);
            return new java.io.FilterInputStream(in) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { Files.deleteIfExists(tmp); }
                }
            };
        } catch (IOException e) {
            throw new AcquisitionException("DB export stream failed for " + file.relativePath(), e);
        }
    }

    @Override
    public Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException {
        String sql = resolveTokens(queryTemplate, ZonedDateTime.now());
        try {
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            Connection c = ensureConnected();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                long rows = writeCsv(rs, dest);
                log.info("DB export {}: {} row(s) → {}", profile.id(), rows, dest.getFileName());
            }
            return dest;
        } catch (SQLException | IOException e) {
            throw new AcquisitionException("DB export failed for '" + profile.id() + "' → " + dest + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void post(RemoteFile file, PostAction action) throws AcquisitionException {
        if (action.kind() != PostAction.Kind.RETAIN)
            throw new AcquisitionException("db-export source has no source-side file to " + action.kind());
    }

    @Override
    public void close() throws AcquisitionException {
        IOException io = null;
        if (conn != null) try { conn.close(); } catch (SQLException e) { io = new IOException(e); }
        if (tunnel != null) try { tunnel.close(); } catch (IOException e) { if (io == null) io = e; }
        conn = null; tunnel = null;
        if (io != null) throw new AcquisitionException("Error closing DB export connection", io);
    }

    // ── connection ────────────────────────────────────────────────────────────

    private synchronized Connection ensureConnected() throws SQLException {
        if (conn != null && !conn.isClosed()) return conn;
        if (driverClass != null && !driverClass.isBlank()) {
            try { Class.forName(driverClass.trim()); }
            catch (ClassNotFoundException e) { throw new SQLException("JDBC driver not found: " + driverClass, e); }
        }
        String url = profile.options().get("jdbc_url");
        if (url == null || url.isBlank()) {
            String host = profile.host();
            int port = profile.port() > 0 ? profile.port() : DEFAULT_PG_PORT;
            if (profile.tunnel() != null && profile.tunnel().host() != null && !profile.tunnel().host().isBlank()) {
                try {
                    tunnel = SshTunnel.open(profile.tunnel(), host, port, DbExportConnector::sshAuth);
                } catch (IOException e) {
                    throw new SQLException("SSH tunnel for DB export '" + profile.id() + "' failed", e);
                }
                InetSocketAddress local = tunnel.localEndpoint();
                host = local.getHostString();
                port = local.getPort();
            }
            url = "jdbc:postgresql://" + host + ":" + port + "/" + profile.database();
        }
        String user = profile.username();
        String pass = SecretResolver.resolve(profile.password());
        conn = (user == null) ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, pass);
        return conn;
    }

    private static void sshAuth(net.schmizz.sshj.SSHClient client, String user, String passwordRef) throws IOException {
        String password = SecretResolver.resolve(passwordRef);
        if (password == null) throw new IOException("no usable credential for SSH tunnel user '" + user + "'");
        client.authPassword(user, password);
    }

    // ── CSV materialisation ─────────────────────────────────────────────────────

    /** Write the full result set to {@code dest} as RFC-4180 CSV (header + fully-quoted fields); returns the row count. */
    private static long writeCsv(ResultSet rs, Path dest) throws SQLException, IOException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        long rows = 0;
        try (BufferedWriter w = Files.newBufferedWriter(dest, StandardCharsets.UTF_8)) {
            for (int i = 1; i <= cols; i++) {
                if (i > 1) w.write(',');
                w.write(quote(md.getColumnLabel(i)));
            }
            w.write('\n');
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    if (i > 1) w.write(',');
                    String v = rs.getString(i);
                    w.write(quote(v == null ? "" : v));
                }
                w.write('\n');
                rows++;
            }
        }
        return rows;
    }

    private static String quote(String s) {
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /**
     * Replace each {@code {pattern}} token with {@code now} formatted by that Java date pattern; non-token text is
     * copied verbatim. e.g. {@code cdr_{yyyyMMdd}.csv} → {@code cdr_20260615.csv}.
     */
    static String resolveTokens(String template, ZonedDateTime now) {
        if (template == null || template.indexOf('{') < 0) return template;
        StringBuilder out = new StringBuilder(template.length() + 8);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int j = template.indexOf('}', i);
                if (j < 0) { out.append(template.substring(i)); break; }
                String pattern = template.substring(i + 1, j);
                out.append(DateTimeFormatter.ofPattern(pattern).format(now));
                i = j + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
