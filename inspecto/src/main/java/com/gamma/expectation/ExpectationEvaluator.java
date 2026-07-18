package com.gamma.expectation;

import com.gamma.sql.SqlSandbox;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * Evaluates one {@link Expectation} against a target's at-rest Parquet by counting violating records in
 * an ephemeral DuckDB sandbox (ING-6). Mirrors {@code QueryExecutor}: the sandbox is opened
 * <b>unsealed</b> because the count query legitimately reads Parquet by absolute path. Unlike a user
 * query there is no untrusted SQL phase — the <em>entire</em> statement is server-built here from
 * validated inputs (column/ref identifiers pass {@link #SAFE_IDENT}, dataset refs pass {@link #SAFE_REF}
 * and a path-jail, numeric bounds are formatted as numbers, the regex is a single-quote-escaped literal),
 * so no {@code SqlGuard} pass is needed.
 */
public final class ExpectationEvaluator {

    private ExpectationEvaluator() {}

    /** Plain SQL identifier (column / ref column) — anything else is rejected before it reaches the SQL. */
    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** A dataset/target ref usable as a path segment under the data root (same shape as {@code DatasetRelation}). */
    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");

    /** The outcome of one evaluation. {@code status} is {@code PASSED} or {@code FAILED}. */
    public record Result(String status, long violations, long checkedAt) {}

    /**
     * Run {@code exp} against {@code dataRoot} and return the count of violating records.
     *
     * @param dataRoot the space's data directory; the target resolves to
     *                 {@code read_parquet('<dataRoot>/<target>/**\/*.parquet')}
     * @throws IllegalArgumentException on an unusable target/column (→ 422 at the route)
     * @throws SQLException             if the target has no queryable data or the count fails (→ 422)
     * @throws IOException              if the sandbox cannot be opened (→ 503)
     */
    public static Result evaluate(Expectation exp, Path dataRoot) throws SQLException, IOException {
        long now = System.currentTimeMillis();
        String sql = countSql(exp, dataRoot);
        try (SqlSandbox sandbox = SqlSandbox.open(SqlSandboxPolicy.defaultPolicy());
             Statement st = sandbox.statement();
             ResultSet rs = st.executeQuery(sql)) {
            long violations = rs.next() ? rs.getLong(1) : 0L;
            return new Result(violations > 0 ? "FAILED" : "PASSED", violations, now);
        }
    }

    /** Build the trusted {@code SELECT count(*) … WHERE <violation predicate>} for the expectation's kind. */
    static String countSql(Expectation exp, Path dataRoot) {
        String relation = parquetGlob(dataRoot, exp.target());
        String col = quote(ident(exp.column()));
        String predicate = switch (exp.kind()) {
            case "non_null" -> col + " IS NULL";
            case "range" -> rangePredicate(exp, col);
            case "regex" -> col + " IS NOT NULL AND NOT regexp_matches(CAST(" + col + " AS VARCHAR), "
                    + literal(exp.pattern()) + ")";
            case "referential" -> col + " IS NOT NULL AND CAST(" + col + " AS VARCHAR) NOT IN (SELECT CAST("
                    + quote(ident(exp.refColumn())) + " AS VARCHAR) FROM "
                    + parquetGlob(dataRoot, exp.refDataset()) + ")";
            default -> throw new IllegalArgumentException("unsupported expectation kind '" + exp.kind() + "'");
        };
        return "SELECT count(*) FROM " + relation + " AS __t WHERE " + predicate;
    }

    private static String rangePredicate(Expectation exp, String col) {
        String num = "TRY_CAST(" + col + " AS DOUBLE)";
        StringBuilder sb = new StringBuilder(col + " IS NOT NULL AND (");
        boolean first = true;
        if (exp.min() != null) {
            sb.append(num).append(" < ").append(number(exp.min()));
            first = false;
        }
        if (exp.max() != null) {
            if (!first) sb.append(" OR ");
            sb.append(num).append(" > ").append(number(exp.max()));
        }
        return sb.append(")").toString();
    }

    /**
     * {@code read_parquet('<dataRoot>/<ref>/**\/*.parquet')} — path-jailed under the data root. A
     * pipeline-shaped store (one with a {@code database/} subtree) is read at its mapped output only
     * ({@code SqlViews.storeReadRoot} — the store-layout contract), so quarantined/backup copies
     * never count against an expectation.
     */
    private static String parquetGlob(Path dataRoot, String ref) {
        if (dataRoot == null)
            throw new IllegalArgumentException("no data root for this space; cannot resolve target");
        if (ref == null || ref.contains("..") || !SAFE_REF.matcher(ref).matches())
            throw new IllegalArgumentException("unsafe expectation target/ref '" + ref + "'");
        Path resolved = dataRoot.resolve(ref).normalize();
        if (!resolved.startsWith(dataRoot.normalize()))
            throw new IllegalArgumentException("expectation target escapes the data root");
        String glob = com.gamma.sql.SqlViews.storeReadRoot(
                resolved.toString().replace('\\', '/')) + "/**/*.parquet";
        return "read_parquet(" + literal(glob) + ")";
    }

    private static String ident(String col) {
        if (col == null || !SAFE_IDENT.matcher(col).matches())
            throw new IllegalArgumentException("unsafe column identifier '" + col + "'");
        return col;
    }

    private static String quote(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** Format a numeric bound without locale/exponent surprises (it is interpolated into the SQL). */
    private static String number(double v) {
        return java.math.BigDecimal.valueOf(v).toPlainString();
    }
}
