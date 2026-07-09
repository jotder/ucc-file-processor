package com.gamma.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal guard evaluator for a Job's {@code when:} expression over the firing Signal's payload
 * ({@code $signal.<field>}) — job-framework §8.2. Grammar is flat (no parentheses): {@code ||} of
 * {@code &&} of single comparisons ({@code == != > < >= <=}). Operands are {@code $signal.<field>}, a
 * quoted string, a number, or a bare token (compared as a string). A missing field or unparsable term
 * is <b>false</b> (fail-closed — a guard that can't be evaluated does not run the Job).
 *
 * <p>Deliberately small and self-contained for P1c; consolidation with the shared Query Core
 * {@code $}-resolver (§7.3) is future work.
 */
final class WhenGuard {

    private WhenGuard() {}

    /** Evaluate {@code expr} against the payload; a blank/absent guard is {@code true} (always run). */
    static boolean eval(String expr, Map<String, Object> payload) {
        if (expr == null || expr.isBlank()) return true;
        for (String or : splitTop(expr, "||")) {
            boolean all = true;
            for (String and : splitTop(or, "&&")) {
                if (!term(and.trim(), payload)) { all = false; break; }
            }
            if (all) return true;
        }
        return false;
    }

    private static boolean term(String t, Map<String, Object> payload) {
        for (String op : new String[]{">=", "<=", "==", "!=", ">", "<"}) {
            int i = t.indexOf(op);
            if (i < 0) continue;
            return compare(resolve(t.substring(0, i).trim(), payload), op,
                           resolve(t.substring(i + op.length()).trim(), payload));
        }
        return false;   // no recognised operator
    }

    /** {@code $signal.<field>} → payload value; quoted → literal; else the bare token. */
    private static Object resolve(String tok, Map<String, Object> payload) {
        if (tok.startsWith("$signal.")) return payload.get(tok.substring("$signal.".length()));
        if (tok.length() >= 2 && (tok.charAt(0) == '"' || tok.charAt(0) == '\'')
                && tok.charAt(tok.length() - 1) == tok.charAt(0))
            return tok.substring(1, tok.length() - 1);
        return tok;
    }

    private static boolean compare(Object l, String op, Object r) {
        if (l == null || r == null) {
            boolean eq = l == null && r == null;
            return op.equals("==") ? eq : op.equals("!=") ? !eq : false;
        }
        Double ln = num(l), rn = num(r);
        if (ln != null && rn != null) {
            int c = Double.compare(ln, rn);
            return switch (op) {
                case ">"  -> c > 0;  case "<"  -> c < 0;
                case ">=" -> c >= 0; case "<=" -> c <= 0;
                case "==" -> c == 0; case "!=" -> c != 0;
                default   -> false;
            };
        }
        String ls = String.valueOf(l), rs = String.valueOf(r);
        return switch (op) {
            case "==" -> ls.equals(rs);
            case "!=" -> !ls.equals(rs);
            default   -> false;   // relational compare on non-numbers is unsupported → false
        };
    }

    private static Double num(Object o) {
        try { return Double.parseDouble(String.valueOf(o)); } catch (RuntimeException e) { return null; }
    }

    /** Split on a top-level operator (flat grammar — no nesting). */
    private static List<String> splitTop(String s, String op) {
        List<String> out = new ArrayList<>();
        int i = 0, j;
        while ((j = s.indexOf(op, i)) >= 0) { out.add(s.substring(i, j)); i = j + op.length(); }
        out.add(s.substring(i));
        return out;
    }
}
