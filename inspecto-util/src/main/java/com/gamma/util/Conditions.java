package com.gamma.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The shared condition language (RBAC/ABAC plan §4 A2 — the "one policy engine, many policy kinds"
 * decision, 2026-07-23): a small, closed, dependency-free grammar parsed once into a predicate over a
 * {@code Map<String,Object>} context. Domain-agnostic by design — Access Policies bind it over
 * {@code subject.* / resource.* / env.*} attribute maps, and other in-memory rule families (Tag Rules,
 * Notification Rules, future retention/case conditions) can adopt the same grammar over their own
 * context maps. Each consumer keeps its own canonical kind and authoring surface.
 *
 * <p><b>Grammar</b> (precedence low → high):
 * <pre>
 *   expr       := and ('or' and)*
 *   and        := unary ('and' unary)*
 *   unary      := 'not' unary | comparison
 *   comparison := operand (('==' | '!=' | 'in' | 'contains') operand)?
 *   operand    := '(' expr ')' | literal | reference
 *   literal    := 'string' | "string" | number | true | false | null
 *   reference  := dotted path, e.g. subject.dataScopes / resource.space (keywords are reserved)
 * </pre>
 *
 * <p><b>Semantics</b> (deterministic, fail-closed): a reference resolves through nested maps via
 * {@link DottedPath} — a missing attribute is {@code null}. {@code ==}/{@code !=} are value equality
 * (numbers compared numerically across integer/decimal types). {@code in} is collection membership
 * ({@code left in rightCollection}); {@code contains} is the converse ({@code leftCollection contains
 * right}) plus substring on two strings. Any other operand typing yields {@code false}, never a throw.
 * Boolean context is strict: only {@code Boolean.TRUE} is truthy — a bare non-boolean reference (or a
 * type-mismatched comparison) is falsy, so {@code not} over garbage stays predictable. {@code and}/
 * {@code or} short-circuit.
 *
 * <p><b>Parsing is the authoring gate:</b> {@link #parse} throws {@link IllegalArgumentException} with
 * a position-bearing message on any syntax error — route handlers surface that as a 422. A stored
 * document whose condition no longer parses must be treated by its consumer as deny-loudly, never
 * silently skipped.
 */
public final class Conditions {

    private Conditions() {}

    /** A parsed condition — evaluate against a (possibly nested) attribute context. Thread-safe. */
    @FunctionalInterface
    public interface Condition {
        boolean test(Map<String, Object> context);
    }

    /** Parse {@code source} once into a reusable {@link Condition}.
     *  @throws IllegalArgumentException on any syntax error (message carries the offset). */
    public static Condition parse(String source) {
        if (source == null || source.isBlank())
            throw new IllegalArgumentException("condition is blank");
        Parser p = new Parser(source);
        Expr root = p.expr();
        p.expect(Tok.EOF);
        return ctx -> Boolean.TRUE.equals(root.eval(ctx == null ? Map.of() : ctx));
    }

    // ── expression tree ─────────────────────────────────────────────────────────────

    private interface Expr {
        Object eval(Map<String, Object> ctx);
    }

    private record Literal(Object value) implements Expr {
        public Object eval(Map<String, Object> ctx) { return value; }
    }

    private record Ref(String path) implements Expr {
        public Object eval(Map<String, Object> ctx) { return DottedPath.resolve(ctx, path); }
    }

    private record Not(Expr inner) implements Expr {
        public Object eval(Map<String, Object> ctx) { return !Boolean.TRUE.equals(inner.eval(ctx)); }
    }

    private record AndOr(boolean isAnd, Expr left, Expr right) implements Expr {
        public Object eval(Map<String, Object> ctx) {
            boolean l = Boolean.TRUE.equals(left.eval(ctx));
            if (isAnd && !l) return false;      // short-circuit
            if (!isAnd && l) return true;
            return Boolean.TRUE.equals(right.eval(ctx));
        }
    }

    private record Compare(String op, Expr left, Expr right) implements Expr {
        public Object eval(Map<String, Object> ctx) {
            Object l = left.eval(ctx);
            Object r = right.eval(ctx);
            return switch (op) {
                case "==" -> valueEquals(l, r);
                case "!=" -> !valueEquals(l, r);
                case "in" -> r instanceof Collection<?> c && memberOf(c, l);
                case "contains" -> (l instanceof Collection<?> c && memberOf(c, r))
                        || (l instanceof String s && r instanceof String needle && s.contains(needle));
                default -> false;   // unreachable — the parser only emits the four ops above
            };
        }

        private static boolean memberOf(Collection<?> c, Object v) {
            return c.stream().anyMatch(e -> valueEquals(e, v));
        }

        private static boolean valueEquals(Object a, Object b) {
            if (a instanceof Number x && b instanceof Number y)
                return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
            return Objects.equals(a, b);
        }
    }

    // ── recursive-descent parser ────────────────────────────────────────────────────

    private enum Tok { AND, OR, NOT, IN, CONTAINS, EQ, NE, LPAREN, RPAREN, STRING, NUMBER, REF, TRUE, FALSE, NULL, EOF }

    private record Token(Tok kind, Object value, int pos) {}

    private static final class Parser {
        private final List<Token> tokens;
        private int i;

        Parser(String source) {
            this.tokens = lex(source);
        }

        Expr expr() {
            Expr left = and();
            while (peek() == Tok.OR) { next(); left = new AndOr(false, left, and()); }
            return left;
        }

        private Expr and() {
            Expr left = unary();
            while (peek() == Tok.AND) { next(); left = new AndOr(true, left, unary()); }
            return left;
        }

        private Expr unary() {
            if (peek() == Tok.NOT) { next(); return new Not(unary()); }
            return comparison();
        }

        private Expr comparison() {
            Expr left = operand();
            String op = switch (peek()) {
                case EQ -> "=="; case NE -> "!="; case IN -> "in"; case CONTAINS -> "contains";
                default -> null;
            };
            if (op == null) return left;
            next();
            return new Compare(op, left, operand());
        }

        private Expr operand() {
            Token t = tokens.get(i);
            switch (t.kind()) {
                case LPAREN -> { next(); Expr inner = expr(); expect(Tok.RPAREN); return inner; }
                case STRING, NUMBER -> { next(); return new Literal(t.value()); }
                case TRUE -> { next(); return new Literal(Boolean.TRUE); }
                case FALSE -> { next(); return new Literal(Boolean.FALSE); }
                case NULL -> { next(); return new Literal(null); }
                case REF -> { next(); return new Ref((String) t.value()); }
                default -> throw error("expected a value, reference, or '('", t.pos());
            }
        }

        private Tok peek() { return tokens.get(i).kind(); }

        private void next() { i++; }

        void expect(Tok kind) {
            Token t = tokens.get(i);
            if (t.kind() != kind)
                throw error("expected " + (kind == Tok.EOF ? "end of condition" : "'" + kind.name().toLowerCase() + "'"), t.pos());
            i++;
        }

        private static IllegalArgumentException error(String message, int pos) {
            return new IllegalArgumentException("condition syntax error at offset " + pos + ": " + message);
        }

        private static List<Token> lex(String s) {
            List<Token> out = new ArrayList<>();
            int n = s.length(), i = 0;
            while (i < n) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                if (c == '(') { out.add(new Token(Tok.LPAREN, null, i++)); continue; }
                if (c == ')') { out.add(new Token(Tok.RPAREN, null, i++)); continue; }
                if (c == '=' || c == '!') {
                    if (i + 1 >= n || s.charAt(i + 1) != '=')
                        throw error("expected '" + c + "='", i);
                    out.add(new Token(c == '=' ? Tok.EQ : Tok.NE, null, i));
                    i += 2;
                    continue;
                }
                if (c == '\'' || c == '"') {
                    int start = i++;
                    StringBuilder sb = new StringBuilder();
                    while (i < n && s.charAt(i) != c) {
                        char d = s.charAt(i);
                        if (d == '\\' && i + 1 < n) { sb.append(s.charAt(i + 1)); i += 2; }
                        else { sb.append(d); i++; }
                    }
                    if (i >= n) throw error("unterminated string", start);
                    i++;   // closing quote
                    out.add(new Token(Tok.STRING, sb.toString(), start));
                    continue;
                }
                if (Character.isDigit(c) || (c == '-' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                    int start = i++;
                    while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                    String num = s.substring(start, i);
                    try {
                        out.add(new Token(Tok.NUMBER, Double.parseDouble(num), start));
                    } catch (NumberFormatException e) {
                        throw error("malformed number '" + num + "'", start);
                    }
                    continue;
                }
                if (Character.isLetter(c) || c == '_') {
                    int start = i++;
                    while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_'
                            || s.charAt(i) == '-' || s.charAt(i) == '.')) i++;
                    String word = s.substring(start, i);
                    Tok kind = switch (word) {
                        case "and" -> Tok.AND; case "or" -> Tok.OR; case "not" -> Tok.NOT;
                        case "in" -> Tok.IN; case "contains" -> Tok.CONTAINS;
                        case "true" -> Tok.TRUE; case "false" -> Tok.FALSE; case "null" -> Tok.NULL;
                        default -> Tok.REF;
                    };
                    if (kind == Tok.REF && (word.startsWith(".") || word.endsWith(".") || word.contains("..")))
                        throw error("malformed reference '" + word + "'", start);
                    out.add(new Token(kind, kind == Tok.REF ? word : null, start));
                    continue;
                }
                throw error("unexpected character '" + c + "'", i);
            }
            out.add(new Token(Tok.EOF, null, n));
            return out;
        }
    }
}
