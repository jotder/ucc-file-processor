package com.gamma.sql;

import com.gamma.config.spec.Finding;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates an agent-generated, read-only SQL query against the <em>real</em> catalog partitions —
 * the oracle behind {@code kpi-to-sql} (M6 / v3.6.0; closes architecture gap G4). It proves a query
 * <em>runs</em> (parses, plans, and produces a known set of columns), which is exactly what the
 * generate→validate→repair loop needs: a query that won't plan is an invisible internal retry rather
 * than a broken draft surfaced to a human. It does <b>not</b> prove the query computes the KPI
 * correctly — a wrong join key or a double-count plans clean — so the skill stays confirm-first.
 *
 * <h3>How it stays safe</h3>
 * Two layers, neither sufficient alone:
 * <ol>
 *   <li>{@link SqlGuard} runs first (before any DuckDB contact), because {@code EXPLAIN} can evaluate
 *       smuggled functions during planning. Any finding short-circuits — no connection is opened.</li>
 *   <li>{@link SqlSandbox}: the trusted oracle materialises each input as a schema-typed table while
 *       file access is on, then {@link SqlSandbox#seal() seals} the connection (no file access, frozen
 *       config) before the untrusted candidate ever executes.</li>
 * </ol>
 *
 * <h3>What it returns</h3>
 * On success: {@code ok=true}, the authoritative {@code columnsProduced} (from the candidate's
 * {@link ResultSetMetaData}, not the model's claim), and — only when {@link Request#sampleRows()} —
 * up to five preview rows. On failure: {@code ok=false} with the verbatim guard finding or DuckDB plan
 * error in {@code error}, ready to feed back to the repair loop.
 *
 * @since 3.6.0
 */
public final class SqlOracle {

    /** Rows materialised per input table when a sample is requested (bounds the preview's cost). */
    private static final int SAMPLE_INPUT_CAP = 10_000;
    /** Preview rows surfaced to the user. */
    private static final int SAMPLE_OUTPUT_ROWS = 5;

    private final SqlSandboxPolicy policy;

    public SqlOracle() {
        this(SqlSandboxPolicy.defaultPolicy());
    }

    public SqlOracle(SqlSandboxPolicy policy) {
        this.policy = (policy == null) ? SqlSandboxPolicy.defaultPolicy() : policy;
    }

    /**
     * A view to register before validating: a logical name the candidate SQL references, backed by a
     * real Parquet/CSV path or glob (resolved from the catalog by the caller).
     *
     * @param name       the table name the candidate SQL uses (e.g. {@code "input"}, a reference name)
     * @param format     {@code "PARQUET"} or {@code "CSV"}
     * @param pathOrGlob the real on-disk path/glob the catalog node points at
     * @param hive       whether the dataset is Hive-partitioned
     */
    public record ViewSpec(String name, String format, String pathOrGlob, boolean hive) {}

    /**
     * A validation request: the candidate read-only SQL, the inputs to register, and whether to
     * include preview rows.
     */
    public record Request(String sql, List<ViewSpec> views, boolean sampleRows) {
        public Request {
            views = (views == null) ? List.of() : List.copyOf(views);
        }
    }

    /**
     * The outcome. {@code ok} means the candidate planned and its columns were derived;
     * {@code columnsProduced} is authoritative. {@code sampleRows} is empty unless requested + non-empty
     * inputs existed. {@code error} (when {@code !ok}) is the verbatim guard/engine message;
     * {@code findings} carries the guard findings when the rejection was lexical.
     */
    public record Result(boolean ok, List<String> columnsProduced,
                         List<Map<String, Object>> sampleRows, String error, List<Finding> findings) {
        public Result {
            columnsProduced = (columnsProduced == null) ? List.of() : List.copyOf(columnsProduced);
            sampleRows = (sampleRows == null) ? List.of() : List.copyOf(sampleRows);
            findings = (findings == null) ? List.of() : List.copyOf(findings);
        }

        static Result rejected(List<Finding> findings) {
            String msg = findings.isEmpty() ? "rejected" : findings.get(0).message();
            return new Result(false, List.of(), List.of(), msg, findings);
        }

        static Result failed(String error) {
            return new Result(false, List.of(), List.of(), error, List.of());
        }

        static Result success(List<String> columns, List<Map<String, Object>> sample) {
            return new Result(true, columns, sample, null, List.of());
        }
    }

    /**
     * Validate {@code req.sql()} against the registered inputs in a sealed sandbox. Never throws —
     * every failure mode (lexical rejection, missing table/column, type error, sandbox error) is
     * reported in the {@link Result}.
     */
    public Result validate(Request req) {
        if (req == null) return Result.failed("no request");

        // Layer 1: lexical allow-list — before any DuckDB contact (planning can evaluate functions).
        List<Finding> guard = SqlGuard.check(req.sql());
        if (!guard.isEmpty()) return Result.rejected(guard);

        String candidate = stripTrailingSemicolon(req.sql().trim());
        int inputCap = req.sampleRows() ? SAMPLE_INPUT_CAP : 0;

        try (SqlSandbox sb = SqlSandbox.open(policy)) {
            // Layer 2a (trusted, file access on): materialise each input as a schema-typed table.
            try (Statement st = sb.statement()) {
                for (ViewSpec v : req.views()) {
                    st.execute("CREATE TABLE " + quote(v.name()) + " AS SELECT * FROM "
                            + SqlViews.reader(v.format(), v.pathOrGlob(), v.hive())
                            + " LIMIT " + inputCap);
                }
            }
            // Layer 2b: seal — no file access, frozen config — before the untrusted candidate runs.
            sb.seal();

            // Prove it plans.
            try (Statement st = sb.statement()) {
                st.execute("EXPLAIN " + candidate);
            }

            // Authoritative column list (independent of any rows).
            List<String> columns = new ArrayList<>();
            try (Statement st = sb.statement();
                 ResultSet rs = st.executeQuery("SELECT * FROM (" + candidate + ") AS __k LIMIT 0")) {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i));
            }

            // Optional preview rows (over the bounded input materialisation).
            List<Map<String, Object>> sample = List.of();
            if (req.sampleRows()) {
                sample = new ArrayList<>();
                try (Statement st = sb.statement();
                     ResultSet rs = st.executeQuery(
                             "SELECT * FROM (" + candidate + ") AS __k LIMIT " + SAMPLE_OUTPUT_ROWS)) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        sample.add(row);
                    }
                }
            }
            return Result.success(columns, sample);
        } catch (SQLException e) {
            return Result.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } catch (IOException e) {
            return Result.failed("SQL sandbox could not start: " + e.getMessage());
        }
    }

    private static String stripTrailingSemicolon(String sql) {
        String s = sql.trim();
        return s.endsWith(";") ? s.substring(0, s.length() - 1).trim() : s;
    }

    /** Quote a table name for DuckDB, escaping embedded double quotes. */
    private static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
