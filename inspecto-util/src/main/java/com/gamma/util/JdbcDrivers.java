package com.gamma.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Opens JDBC connections for DuckDB- or Postgres-backed stores. It registers the bundled
 * driver matching the URL's scheme, then hands back a {@link Connection} — centralising the
 * "which drivers ship + how a store opens its connection" logic that every store's
 * {@code open(...)} factory used to copy-paste.
 */
public final class JdbcDrivers {

    private JdbcDrivers() {}

    /**
     * Register the driver for {@code url}'s scheme, then open a connection with
     * URL-embedded / no credentials.
     *
     * @throws SQLException if the matched driver class is not on the classpath, or the connection fails
     */
    public static Connection connect(String url) throws SQLException {
        register(url);
        return DriverManager.getConnection(url);
    }

    /**
     * Register the driver for {@code url}'s scheme, then open a connection. When both
     * {@code user} and {@code pass} are {@code null}, any credentials embedded in the URL are used.
     *
     * @throws SQLException if the matched driver class is not on the classpath, or the connection fails
     */
    public static Connection connect(String url, String user, String pass) throws SQLException {
        register(url);
        return (user == null && pass == null)
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, pass);
    }

    /**
     * Best-effort dialect probe: {@code true} when {@code conn} speaks PostgreSQL, {@code false}
     * otherwise (including on any metadata error — callers default to the bundled DuckDB dialect).
     * Continuous percentiles are the one non-portable bit of SQL across our stores
     * ({@code quantile_cont(col, p)} vs {@code percentile_cont(p) WITHIN GROUP (ORDER BY col)}); this
     * centralises the probe that the dialect switch and the DB-browser engine label both need.
     */
    public static boolean isPostgres(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (SQLException e) {
            return false;
        }
    }

    /** Load the bundled JDBC driver matching the URL scheme; an unrecognised scheme self-registers (no-op). */
    private static void register(String url) throws SQLException {
        try {
            if (url.startsWith("jdbc:duckdb:")) Class.forName("org.duckdb.DuckDBDriver");
            else if (url.startsWith("jdbc:postgresql:")) Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("No JDBC driver on the classpath for " + url, e);
        }
    }
}
