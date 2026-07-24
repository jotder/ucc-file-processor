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
 * <p><b>Window functions (v2).</b> The relation wrap is a projection ({@code SELECT *, (expr) AS "n"
 * FROM (base)}), so a per-row <em>window</em> function is safe there but a bare aggregate is not (it
 * would collapse the group). A {@link #WINDOW_FUNCTIONS window-function} name is therefore callable
 * <em>only</em> when its call is immediately followed by a valid {@code OVER (…)} clause; the same names
 * used bare (no {@code OVER}) never lex as calls, so a plain {@code sum(x)}/{@code avg(x)} is still
 * rejected — aggregation stays the Measure layer's job. The {@code OVER} clause admits only
 * {@code PARTITION BY}/{@code ORDER BY} over columns, scalar functions, and {@code ASC}/{@code DESC}/
 * {@code NULLS FIRST|LAST}; explicit frame clauses ({@code ROWS|RANGE BETWEEN …}) are deliberately not
 * supported. Still no subqueries — the {@code DENIED} deny-set is checked ahead of every other rule.
 * Unknown <em>columns</em> are not resolved here — DuckDB's binder rejects them cleanly at query time.
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

    /** Functions callable ONLY as a window call — the call must be immediately followed by {@code OVER (…)}
     *  (rule 3 + window rule). The windowed aggregates ({@code sum}/{@code avg}/{@code count}/{@code min}/
     *  {@code max}) are deliberately absent from {@link #FUNCTIONS}, so used bare they stay rejected. */
    private static final Set<String> WINDOW_FUNCTIONS = Set.of(
            "sum", "avg", "count", "min", "max",
            "row_number", "rank", "dense_rank", "percent_rank", "cume_dist",
            "ntile", "lag", "lead", "first_value", "last_value", "nth_value");

    /** Bare words allowed only inside an {@code OVER (…)} clause (never call targets). */
    private static final Set<String> WINDOW_KEYWORDS = Set.of(
            "partition", "by", "order", "asc", "desc", "nulls", "first", "last");

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
        String prevWord = null;      // last identifier-ish token, to pair with a following '('
        boolean afterAs = false;     // inside cast(x AS <type>) — the next word must be a type
        boolean expectOver = false;  // a window call just closed — the next token MUST be 'over'
        boolean expectOverParen = false;  // 'over' just seen — the next token MUST be '('
        int windowOpenDepth = -1;    // parens depth inside a window fn's arg list (to detect its close)
        int windowSpecDepth = -1;    // parens depth inside the OVER (…) clause (window keywords legal here)
        while (pos < e.length()) {
            if (!m.find(pos) || m.start() != pos)
                throw new IllegalArgumentException("illegal character in expression at: '"
                        + e.substring(pos, Math.min(pos + 12, e.length())) + "'");
            String tok = m.group();
            pos = m.end();
            if (tok.isBlank()) continue;

            // A window call must be followed by OVER (…). These two gates run before anything else so a
            // bare aggregate/window call (no OVER) can never slip through as a normal expression.
            if (expectOver) {
                if (!(tok.matches("[A-Za-z_][A-Za-z0-9_]*") && tok.equalsIgnoreCase("over")))
                    throw new IllegalArgumentException("a window function must be followed by an OVER (…) clause");
                expectOver = false;
                expectOverParen = true;
                prevWord = null;
                continue;
            }
            if (expectOverParen) {
                if (!tok.equals("("))
                    throw new IllegalArgumentException("OVER must be followed by '('");
                expectOverParen = false;
                parens++;
                windowSpecDepth = parens;
                prevWord = null;
                continue;
            }

            if (tok.equals("(")) {
                boolean windowCall = prevWord != null && WINDOW_FUNCTIONS.contains(prevWord);
                if (prevWord != null && !FUNCTIONS.contains(prevWord) && !windowCall)
                    throw new IllegalArgumentException("function '" + prevWord + "' is not allowed"
                            + " (allowed: " + String.join(", ", FUNCTIONS.stream().sorted().toList()) + ")");
                parens++;
                if (windowCall) windowOpenDepth = parens;
                prevWord = null;
                continue;
            }
            if (tok.equals(")")) {
                boolean closingWindowCall = windowOpenDepth != -1 && parens == windowOpenDepth;
                boolean closingWindowSpec = windowSpecDepth != -1 && parens == windowSpecDepth;
                if (--parens < 0) throw new IllegalArgumentException("unbalanced ')' in expression");
                prevWord = null;
                if (closingWindowCall) { expectOver = true; windowOpenDepth = -1; }
                if (closingWindowSpec) windowSpecDepth = -1;
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
                // window keywords are only meaningful inside the OVER (…) clause; elsewhere they lex as
                // ordinary column refs (a DuckDB bind error at worst — never a structural break).
                if (windowSpecDepth != -1 && parens >= windowSpecDepth && WINDOW_KEYWORDS.contains(w)) {
                    prevWord = null;
                    continue;
                }
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
        if (expectOver) throw new IllegalArgumentException("a window function must be followed by an OVER (…) clause");
        if (expectOverParen) throw new IllegalArgumentException("OVER must be followed by '('");
        return e;
    }
}
