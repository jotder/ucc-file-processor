package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.AcquisitionLedgers;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

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
 *   <li>{@code watermark_column} — optional; enables <b>row-level incremental export</b>. The result column whose
 *       max is tracked and bound into a {@code :watermark} placeholder, e.g.
 *       {@code SELECT * FROM cdr WHERE updated_at > :watermark ORDER BY updated_at}. The connector reads the stored
 *       watermark (per connection-profile id) before the query and persists the new max <em>after the batch
 *       commits</em> ({@link AcquisitionLedgers}), so a crash mid-ingest re-exports the slice rather than skipping
 *       it (at-least-once / resumable). Gap-free only over an append-only/monotonic column (strictly {@code >}).</li>
 *   <li>{@code watermark_initial} — optional first-run lower bound (used until a value is stored).</li>
 *   <li>{@code watermark_type} — optional {@code string} (default) | {@code long} | {@code timestamp}; controls how
 *       the bound value is typed and how the running max is compared.</li>
 * </ul>
 *
 * <p>The result is a single logical "file" per cycle, so this connector is {@link Capability#STREAM}-only — there
 * is no source-side file to delete/move/rename, and (with {@code source.post_action} unset) nothing tries to.
 */
public final class DbExportConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(DbExportConnector.class);
    private static final int DEFAULT_PG_PORT = 5432;

    /** The bind placeholder for the row-level watermark; not followed by an identifier char (so {@code :watermark2} is left alone). */
    private static final Pattern WATERMARK_TOKEN = Pattern.compile(":watermark(?![A-Za-z0-9_])");

    private final ConnectionProfile profile;
    private final String queryTemplate;
    private final String nameTemplate;
    private final String driverClass;       // optional explicit driver

    private final String watermarkColumn;   // null ⇒ row-level watermarking off
    private final String watermarkInitial;  // first-run lower bound (may be null ⇒ type floor)
    private final WatermarkType watermarkType;

    private Connection conn;
    private SshTunnel tunnel;

    public DbExportConnector(ConnectionProfile profile) {
        this.profile = profile;
        this.queryTemplate = profile.options().get("query");
        this.nameTemplate = profile.options().get("export_name");
        this.driverClass = profile.options().get("driver");
        String wc = profile.options().get("watermark_column");
        this.watermarkColumn = (wc == null || wc.isBlank()) ? null : wc.trim();
        this.watermarkInitial = profile.options().get("watermark_initial");
        this.watermarkType = WatermarkType.from(profile.options().get("watermark_type"));
        if (queryTemplate == null || queryTemplate.isBlank())
            throw new IllegalArgumentException("db-export connection '" + profile.id() + "' needs options.query");
        if (nameTemplate == null || nameTemplate.isBlank())
            throw new IllegalArgumentException("db-export connection '" + profile.id() + "' needs options.export_name");
        if (watermarkColumn != null && !WATERMARK_TOKEN.matcher(queryTemplate).find())
            throw new IllegalArgumentException("db-export connection '" + profile.id()
                    + "' sets options.watermark_column but options.query has no :watermark placeholder");
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
            Export e = (watermarkColumn != null)
                    ? exportIncremental(c, sql, dest)
                    : exportAll(c, sql, dest);
            // Advance the watermark only after the batch commits — stash it for BatchProcessor to persist. Empty
            // result ⇒ no max ⇒ nothing stashed ⇒ watermark unchanged (correct: no rows past the frontier).
            if (watermarkColumn != null && e.rows() > 0 && e.maxWatermark() != null)
                AcquisitionLedgers.stashDbWatermark(dest, profile.id(), e.maxWatermark());
            return dest;
        } catch (SQLException | IOException e) {
            throw new AcquisitionException("DB export failed for '" + profile.id() + "' → " + dest + ": " + e.getMessage(), e);
        }
    }

    /** The full, non-incremental export (current behaviour): plain {@link Statement}, no watermark tracking. */
    private Export exportAll(Connection c, String sql, Path dest) throws SQLException, IOException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            Export e = writeCsv(rs, dest, null, null);
            log.info("DB export {}: {} row(s) → {}", profile.id(), e.rows(), dest.getFileName());
            return e;
        }
    }

    /** Row-level incremental export: bind the stored watermark into {@code :watermark} and track the new max. */
    private Export exportIncremental(Connection c, String sql, Path dest) throws SQLException, IOException {
        String bound = AcquisitionLedgers.shared().dbWatermark(profile.id())
                .orElse(watermarkInitial != null ? watermarkInitial : watermarkType.floor());
        String prepared = bindPlaceholders(sql);
        int binds = countMatches(WATERMARK_TOKEN, sql);
        try (PreparedStatement ps = c.prepareStatement(prepared)) {
            for (int i = 1; i <= binds; i++) watermarkType.bind(ps, i, bound);
            try (ResultSet rs = ps.executeQuery()) {
                Export e = writeCsv(rs, dest, watermarkColumn, watermarkType);
                log.info("DB export {} (incremental, {} > {}): {} row(s) → {}",
                        profile.id(), watermarkColumn, bound, e.rows(), dest.getFileName());
                return e;
            }
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
                    // the bastion is the only SSH hop here, so host_key/known_hosts pin it directly.
                    tunnel = SshTunnel.open(profile.tunnel(), host, port, DbExportConnector::sshAuth,
                            HostKeyPolicy.from(profile));
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

    /** Outcome of one export: the row count and (when row-watermarking) the max watermark value seen, else null. */
    private record Export(long rows, String maxWatermark) {}

    /**
     * Write the full result set to {@code dest} as RFC-4180 CSV (header + fully-quoted fields). When
     * {@code watermarkColumn} is non-null, also track the maximum value of that column (compared per
     * {@code wmType}) so the caller can advance the watermark; returns both the row count and that max.
     */
    private static Export writeCsv(ResultSet rs, Path dest, String watermarkColumn, WatermarkType wmType)
            throws SQLException, IOException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        int wmIdx = (watermarkColumn == null) ? -1 : columnIndex(md, cols, watermarkColumn);
        long rows = 0;
        String maxWm = null;
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
                if (wmIdx > 0) {
                    String v = rs.getString(wmIdx);
                    if (v != null && (maxWm == null || wmType.compare(v, maxWm) > 0)) maxWm = v;
                }
            }
        }
        return new Export(rows, maxWm);
    }

    /** 1-based index of the column whose label matches {@code name} (case-insensitive); fails loud if absent. */
    private static int columnIndex(ResultSetMetaData md, int cols, String name) throws SQLException {
        for (int i = 1; i <= cols; i++) {
            if (md.getColumnLabel(i).equalsIgnoreCase(name)) return i;
        }
        throw new SQLException("watermark_column '" + name + "' is not in the query result");
    }

    /** Rewrite each {@code :watermark} bind placeholder to a JDBC positional {@code ?}. Package-private for tests. */
    static String bindPlaceholders(String sql) {
        return WATERMARK_TOKEN.matcher(sql).replaceAll("?");
    }

    private static int countMatches(Pattern p, String s) {
        int n = 0;
        var m = p.matcher(s);
        while (m.find()) n++;
        return n;
    }

    private static String quote(String s) {
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /**
     * How a row-level watermark value is bound into the query and compared when tracking the running max. Stored as
     * text (the value comes from {@link ResultSet#getString}); the type controls binding precision and ordering —
     * {@code string}/{@code timestamp} bind as text (the DB casts) and sort lexically (ISO timestamps sort right);
     * {@code long} binds numerically and compares numerically (so {@code "10" > "9"}).
     */
    enum WatermarkType {
        STRING {
            @Override void bind(PreparedStatement ps, int i, String v) throws SQLException { ps.setString(i, v); }
            @Override int compare(String a, String b) { return a.compareTo(b); }
            @Override String floor() { return ""; }
        },
        LONG {
            @Override void bind(PreparedStatement ps, int i, String v) throws SQLException {
                try { ps.setLong(i, Long.parseLong(v.trim())); }
                catch (NumberFormatException e) { throw new SQLException("watermark '" + v + "' is not a long", e); }
            }
            @Override int compare(String a, String b) { return Long.compare(Long.parseLong(a.trim()), Long.parseLong(b.trim())); }
            @Override String floor() { return Long.toString(Long.MIN_VALUE); }
        },
        TIMESTAMP {
            @Override void bind(PreparedStatement ps, int i, String v) throws SQLException { ps.setString(i, v); }
            @Override int compare(String a, String b) { return a.compareTo(b); }
            @Override String floor() { return "0001-01-01 00:00:00"; }
        };

        abstract void bind(PreparedStatement ps, int i, String v) throws SQLException;
        abstract int compare(String a, String b);
        abstract String floor();

        static WatermarkType from(String s) {
            if (s == null || s.isBlank()) return STRING;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "long", "int", "integer", "number", "bigint" -> LONG;
                case "timestamp", "datetime", "date" -> TIMESTAMP;
                default -> STRING;
            };
        }
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
