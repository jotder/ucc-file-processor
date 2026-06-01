package com.gamma.sql;

import com.gamma.config.spec.Finding;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A lexical/structural allow-list for agent-generated SQL — the first of the SQL sandbox's two layers
 * (M6 / v3.6.0; closes architecture gap G4). It runs <em>before</em> the SQL ever reaches DuckDB,
 * because a query that merely {@code EXPLAIN}s can still evaluate smuggled functions <em>during
 * planning</em> — so {@code EXPLAIN}/{@code LIMIT 0} is not, by itself, a security boundary. This guard
 * is: a candidate must be a <b>single, read-only {@code SELECT}/{@code WITH} statement</b> that touches
 * none of DuckDB's file/extension/system surface.
 *
 * <h3>What it rejects</h3>
 * <ul>
 *   <li><b>Multi-statement</b> — any {@code ;} beyond a single optional trailing one.</li>
 *   <li><b>Not a read-only query</b> — anything not beginning with {@code SELECT} or {@code WITH …
 *       SELECT}; any DDL/DML keyword ({@code CREATE}/{@code INSERT}/{@code UPDATE}/{@code DELETE}/
 *       {@code DROP}/{@code ALTER}/{@code MERGE}/{@code TRUNCATE}/{@code ATTACH}/{@code COPY}/…).</li>
 *   <li><b>File/extension/system functions</b> — {@code read_*}/{@code write_*}/{@code *_scan}/
 *       {@code copy}/{@code getenv}/{@code glob}/{@code sniff_csv}/{@code system}/{@code shell}/
 *       {@code query}/{@code query_table}/{@code read_blob}/{@code read_text}/…; the {@code install}/
 *       {@code load}/{@code pragma}/{@code set} configuration verbs.</li>
 *   <li><b>Comment-smuggling</b> — line ({@code --}) and block ({@code /* *}{@code /}) comments are
 *       stripped (outside string literals) <em>before</em> the token scan, with block comments removed
 *       to empty so {@code read/*x*}{@code /_csv(…)} cannot hide a blocked token; an unterminated block
 *       comment is itself rejected.</li>
 * </ul>
 *
 * <p>Returns {@code ERROR}-severity {@link Finding}s (reusing the config-spec model, as
 * {@code ConfigSafetyValidator} does); an empty list means the SQL passed the allow-list. Never throws.
 * Errs toward rejection: a false reject only forces a repair round, a false accept is a security hole.
 *
 * @since 3.6.0
 */
public final class SqlGuard {

    private SqlGuard() {}

    /** Functions whose presence (name followed by {@code (}) is rejected outright. */
    private static final Pattern BLOCKED_FUNCTIONS = Pattern.compile(
            "\\b(read_\\w+|write_\\w+|\\w*_scan|copy|getenv|glob|sniff_csv|system|shell|exec|eval"
                    + "|query|query_table|read_blob|read_text|read_json\\w*)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /** Keyword verbs that have no place in a read-only SELECT (matched as whole words, anywhere). */
    private static final Pattern BLOCKED_KEYWORDS = Pattern.compile(
            "\\b(attach|detach|install|load|pragma|set|reset|export|import|copy|call|checkpoint"
                    + "|create|insert|update|delete|drop|alter|merge|truncate|replace|grant|revoke"
                    + "|vacuum|analyze|prepare|execute)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A cleaned candidate must start here: optional leading {@code (}, then SELECT or WITH. */
    private static final Pattern STARTS_READONLY = Pattern.compile(
            "^\\(*\\s*(select|with)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Check {@code sql} against the allow-list. Returns every violation as an {@code ERROR}
     * {@link Finding}; an empty list means the SQL is a single read-only query touching no blocked
     * surface. Never throws.
     */
    public static List<Finding> check(String sql) {
        List<Finding> out = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            out.add(Finding.error("sql", "the SQL query is empty"));
            return out;
        }

        Stripped s = strip(sql);
        if (s.unterminatedComment) {
            out.add(Finding.error("sql", "the SQL contains an unterminated block comment (/* with no */)"));
            return out;
        }
        String cleaned = s.text.trim();
        if (cleaned.isEmpty()) {
            out.add(Finding.error("sql", "the SQL is empty once comments are removed"));
            return out;
        }

        // One statement only: strip a single trailing ';', then any remaining ';' is a second statement.
        String noTrailing = cleaned.endsWith(";")
                ? cleaned.substring(0, cleaned.length() - 1).trim()
                : cleaned;
        if (noTrailing.contains(";")) {
            out.add(Finding.error("sql",
                    "only a single statement is allowed; remove the extra ';'-separated statement(s)"));
        }

        if (!STARTS_READONLY.matcher(noTrailing).find()) {
            out.add(Finding.error("sql",
                    "only a single read-only query is allowed — it must begin with SELECT or WITH"));
        }

        var fm = BLOCKED_FUNCTIONS.matcher(noTrailing);
        if (fm.find()) {
            out.add(Finding.error("sql", "the function '" + fm.group(1).toLowerCase()
                    + "(...)' is not allowed (file/extension/system access is forbidden in a KPI query)"));
        }

        var km = BLOCKED_KEYWORDS.matcher(noTrailing);
        if (km.find()) {
            out.add(Finding.error("sql", "the keyword '" + km.group(1).toUpperCase()
                    + "' is not allowed — only a read-only SELECT over the catalog views is permitted"));
        }

        return out;
    }

    /** True iff {@code sql} passes the allow-list with no findings. */
    public static boolean isReadOnly(String sql) {
        return check(sql).isEmpty();
    }

    // ── comment stripping (string-literal aware) ───────────────────────────────────────

    private record Stripped(String text, boolean unterminatedComment) {}

    /**
     * Remove line and block comments, honouring single-quoted string literals (so a literal containing
     * {@code --} or {@code /*} is not mistaken for a comment). Block comments are removed to empty so a
     * comment cannot be used to split a blocked token; line comments become a newline.
     */
    private static Stripped strip(String sql) {
        StringBuilder b = new StringBuilder(sql.length());
        int i = 0, n = sql.length();
        boolean inStr = false;
        while (i < n) {
            char c = sql.charAt(i);
            if (inStr) {
                b.append(c);
                if (c == '\'') {
                    if (i + 1 < n && sql.charAt(i + 1) == '\'') { // escaped '' inside a literal
                        b.append('\'');
                        i += 2;
                        continue;
                    }
                    inStr = false;
                }
                i++;
                continue;
            }
            if (c == '\'') {
                inStr = true;
                b.append(c);
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') i++;
                b.append('\n');
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) return new Stripped(b.toString(), true);
                i = end + 2; // drop the comment entirely (no separator) to defeat token-splitting
            } else {
                b.append(c);
                i++;
            }
        }
        return new Stripped(b.toString(), false);
    }
}
