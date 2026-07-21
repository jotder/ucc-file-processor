package com.gamma.query;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates a <b>calculated-column expression fragment</b> (DAT-5) before it is spliced into the trusted
 * dataset relation — the fragment-level counterpart of {@code SqlGuard} (which checks whole statements).
 * Design: {@code docs/superpower/calculated-columns-design.md}. Three cooperating rules:
 * <ol>
 *   <li><b>Closed token alphabet</b> — identifiers, numeric literals, single-quoted strings ({@code ''}
 *       escape), arithmetic/comparison/concat operators, parens, commas. Nothing else lexes.</li>
 *   <li><b>Keyword deny-set</b> — {@code SELECT}/{@code FROM}/… can never appear even as bare
 *       identifiers, which kills scalar-subquery smuggling structurally.</li>
 *   <li><b>Function-call whitelist</b> — an identifier followed by {@code (} must be a named scalar
 *       function, which kills {@code read_parquet(…)}/UDF calls by name.</li>
 * </ol>
 * Row-level only by design: no aggregates (that is the Measure layer), no window functions, no
 * subqueries. Unknown <em>columns</em> are not resolved here — DuckDB's binder rejects them cleanly at
 * query time.
 */
public final class ExpressionGuard {

    private ExpressionGuard() {}

    private static final int MAX_LENGTH = 500;

    private static final Pattern TOKEN = Pattern.compile(
            "\\s+"                                   // whitespace (skipped)
          + "|[A-Za-z_][A-Za-z0-9_]*"                // identifier / keyword / function name
          + "|[0-9]+(?:\\.[0-9]+)?"                  // numeric literal
          + "|'(?:[^']|'')*'"                        // single-quoted string, '' escape only
          + "|\\|\\||!=|<>|>=|<=|[+\\-*/%=<>(),]");  // operators, parens, comma

    /** Statement/structure keywords that must never appear, even as bare identifiers (rule 2). */
    private static final Set<String> DENIED = Set.of(
            "select", "from", "where", "group", "having", "union", "join", "with", "values",
            "insert", "update", "delete", "drop", "create", "alter", "attach", "copy",
            "pragma", "install", "load", "call", "set", "table", "exec", "execute");

    /** Flow keywords of a CASE expression + predicate glue — allowed as bare words (never called). */
    private static final Set<String> FLOW_KEYWORDS = Set.of(
            "case", "when", "then", "else", "end", "and", "or", "not", "is", "null",
            "in", "like", "between", "as");

    /** The scalar functions a calculated column may call (rule 3). */
    private static final Set<String> FUNCTIONS = Set.of(
            "abs", "round", "floor", "ceil", "coalesce", "nullif", "greatest", "least",
            "upper", "lower", "trim", "ltrim", "rtrim", "length", "substr", "substring",
            "concat", "replace", "cast", "try_cast");

    /** The type names a {@code cast(x AS type)} may target. */
    private static final Set<String> TYPES = Set.of(
            "integer", "int", "bigint", "smallint", "double", "float", "real", "decimal",
            "varchar", "text", "boolean", "date", "timestamp");

    /**
     * Validate {@code expr}; returns the trimmed expression for splicing.
     *
     * @throws IllegalArgumentException naming the offending token when the fragment is not provably safe
     */
    public static String check(String expr) {
        if (expr == null || expr.isBlank())
            throw new IllegalArgumentException("calculated column expression is empty");
        String e = expr.trim();
        if (e.length() > MAX_LENGTH)
            throw new IllegalArgumentException("calculated column expression exceeds " + MAX_LENGTH + " chars");
        // Comment sequences would survive tokenizing as innocent operator pairs (`--` = minus minus,
        // `/*` = divide times) yet change statement structure when spliced — reject them outright,
        // checked with string literals blanked so 'a--b' the *literal* stays allowed.
        String noStrings = e.replaceAll("'(?:[^']|'')*'", "''");
        if (noStrings.contains("--") || noStrings.contains("/*") || noStrings.contains("*/"))
            throw new IllegalArgumentException("comment sequences (--, /*, */) are not allowed in a calculated column");

        Matcher m = TOKEN.matcher(e);
        int pos = 0;
        int parens = 0;
        String prevWord = null;     // last identifier-ish token, to pair with a following '('
        boolean afterAs = false;    // inside cast(x AS <type>) — the next word must be a type
        while (pos < e.length()) {
            if (!m.find(pos) || m.start() != pos)
                throw new IllegalArgumentException("illegal character in expression at: '"
                        + e.substring(pos, Math.min(pos + 12, e.length())) + "'");
            String tok = m.group();
            pos = m.end();
            if (tok.isBlank()) continue;

            if (tok.equals("(")) {
                parens++;
                if (prevWord != null && !FUNCTIONS.contains(prevWord))
                    throw new IllegalArgumentException("function '" + prevWord + "' is not allowed"
                            + " (allowed: " + String.join(", ", FUNCTIONS.stream().sorted().toList()) + ")");
                prevWord = null;
                continue;
            }
            if (tok.equals(")")) {
                if (--parens < 0) throw new IllegalArgumentException("unbalanced ')' in expression");
                prevWord = null;
                continue;
            }

            if (tok.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                String w = tok.toLowerCase(Locale.ROOT);
                if (DENIED.contains(w))
                    throw new IllegalArgumentException("'" + tok + "' is not allowed in a calculated column");
                if (afterAs) {
                    if (!TYPES.contains(w))
                        throw new IllegalArgumentException("cast target type '" + tok + "' is not allowed"
                                + " (allowed: " + String.join(", ", TYPES.stream().sorted().toList()) + ")");
                    afterAs = false;
                    prevWord = null;
                    continue;
                }
                if (w.equals("as")) { afterAs = true; prevWord = null; continue; }
                // a flow keyword is never a call target; anything else may be a column ref OR a function
                // name — resolved when the next token is '('
                prevWord = FLOW_KEYWORDS.contains(w) ? null : w;
                continue;
            }
            afterAs = false;
            prevWord = null;   // literals/operators break any identifier-then-paren pairing
        }
        if (parens != 0) throw new IllegalArgumentException("unbalanced '(' in expression");
        if (afterAs) throw new IllegalArgumentException("dangling AS in expression");
        return e;
    }
}
