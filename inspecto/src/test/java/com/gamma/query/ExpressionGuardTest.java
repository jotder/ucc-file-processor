package com.gamma.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ExpressionGuard} (DAT-5): the accept set (arithmetic, whitelisted scalar functions, CASE flow,
 * cast with whitelisted types) and — the part that matters — the reject set from the design doc's threat
 * cases: scalar-subquery smuggling, file-function calls, statement escapes, comment/quote tricks.
 */
class ExpressionGuardTest {

    private static void ok(String expr) {
        assertEquals(expr.trim(), ExpressionGuard.check(expr), expr);
    }

    private static void bad(String expr, String why) {
        assertThrows(IllegalArgumentException.class, () -> ExpressionGuard.check(expr), why + ": " + expr);
    }

    @Test
    void acceptsRowLevelArithmeticFunctionsAndCase() {
        ok("amount * 1.2");
        ok("round(amount / 60.0, 2)");
        ok("coalesce(region, 'UNKNOWN')");
        ok("upper(region) || '-' || cast(amount AS varchar)");
        ok("CASE WHEN amount > 100 THEN 'big' WHEN amount > 10 THEN 'mid' ELSE 'small' END");
        ok("try_cast(raw_ts AS timestamp)");
        ok("nullif(trim(code), '')");
        ok("amount IS NOT NULL AND region IN ('EU', 'US')");
    }

    @Test
    void killsScalarSubquerySmuggling() {
        bad("(SELECT secret FROM t)", "SELECT is denied even as a bare word");
        bad("1 + (select 1)", "case-insensitive");
        bad("amount FROM t", "FROM denied");
        bad("a UNION ALL b", "UNION denied");
    }

    @Test
    void killsFileAndUnknownFunctionCalls() {
        bad("read_parquet('/etc/passwd')", "file function by name");
        bad("read_csv('x')", "file function by name");
        bad("glob('*')", "glob is not whitelisted");
        bad("my_udf(amount)", "unknown function");
        bad("sum(amount)", "aggregates are the Measure layer's job, not row-level");
    }

    @Test
    void killsStatementAndLexicalEscapes() {
        bad("1; DROP TABLE t", "semicolon never lexes");
        bad("amount -- comment", "line comment never lexes");
        bad("amount /* c */", "block comment never lexes");
        bad("\"quoted\"", "double-quoted identifiers are out of the v1 alphabet");
        bad("'unterminated", "broken string literal");
        bad("cast(x AS blob)", "non-whitelisted cast target");
        bad("a + )", "unbalanced parens (close)");
        bad("(a + 1", "unbalanced parens (open)");
        bad("", "empty expression");
        bad("a".repeat(501), "length cap");
    }
}
