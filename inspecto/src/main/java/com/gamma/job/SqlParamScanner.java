package com.gamma.job;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a {@code sql.template} Job's parameter contract from its SQL text and substitutes resolved
 * values back in (job-framework §15.1). A parameter is a {@code $name} token — {@code $} followed by an
 * identifier that is <b>not</b> a {@code $}-context expression (those end in {@code (} or {@code .}, e.g.
 * {@code $day(-1)}, {@code $run.id}); date arithmetic and run/signal context belong in a parameter's
 * {@code deduce:}, never inline in the SQL. This is the R3 demonstration: the SQL text <i>is</i> the
 * required-parameter declaration.
 *
 * <p>The possessive quantifier ({@code *+}) prevents the identifier from backtracking to a shorter match
 * when the next char is {@code (}/{@code .}, so {@code $day(-1)} yields no token rather than {@code $da}.
 */
final class SqlParamScanner {

    private SqlParamScanner() {}

    private static final Pattern TOKEN = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*+)(?![(.])");

    /** Distinct {@code $name} tokens (declaration order) as required {@code STRING} parameters. */
    static List<ParameterDecl> scan(String sql) {
        if (sql == null) return List.of();
        Map<String, ParameterDecl> out = new LinkedHashMap<>();
        Matcher m = TOKEN.matcher(sql);
        while (m.find()) {
            String name = m.group(1);
            out.putIfAbsent(name, ParameterDecl.required(name, ParamType.STRING,
                    "SQL template parameter $" + name));
        }
        return List.copyOf(out.values());
    }

    /**
     * Replace each {@code $name} with the SQL string literal of its resolved value (single-quote-escaped,
     * so an operator- or signal-supplied value can't break out of the literal). DuckDB casts the literal
     * to the column's type in a predicate ({@code WHERE d = '2026-07-07'}). A token with no resolved value
     * is left verbatim — it cannot occur for a required parameter, which the resolver gates as REJECTED.
     */
    static String substitute(String sql, Map<String, String> params) {
        if (sql == null) return null;
        Matcher m = TOKEN.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = params.get(m.group(1));
            String repl = v == null ? m.group() : "'" + v.replace("'", "''") + "'";
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
