package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P3b: the SQL text is the parameter contract — {@code $name} tokens are scanned; {@code $}-context
 *  expressions ({@code $day(-1)}, {@code $run.id}) are not; substitution quotes values injection-safely. */
class SqlParamScannerTest {

    @Test
    void scansBareDollarTokensAsRequiredParameters() {
        List<ParameterDecl> decls = SqlParamScanner.scan(
                "SELECT * FROM t WHERE d = $event_date AND region = $region AND d = $event_date");
        assertEquals(List.of("event_date", "region"), decls.stream().map(ParameterDecl::name).toList(),
                "distinct, in declaration order");
        assertTrue(decls.stream().allMatch(ParameterDecl::required));
        assertTrue(decls.stream().allMatch(d -> d.type() == ParamType.STRING));
    }

    @Test
    void ignoresDollarContextExpressions() {
        // $day(-1) and $run.id are $-context (they end in '(' / '.'), resolved via a param's deduce — not
        // parameters declared by the SQL itself; only the bare $threshold is.
        List<ParameterDecl> decls = SqlParamScanner.scan(
                "SELECT * FROM t WHERE d = $day(-1) AND run = $run.id AND n > $threshold");
        assertEquals(List.of("threshold"), decls.stream().map(ParameterDecl::name).toList());
    }

    @Test
    void substitutesQuotedLiteralsAndEscapes() {
        String out = SqlParamScanner.substitute(
                "SELECT * FROM t WHERE d = $event_date AND note = $note",
                Map.of("event_date", "2026-07-07", "note", "O'Brien"));
        assertEquals("SELECT * FROM t WHERE d = '2026-07-07' AND note = 'O''Brien'", out,
                "values become single-quote-escaped SQL literals");
    }

    @Test
    void leavesDollarContextAndUnresolvedTokensVerbatim() {
        String out = SqlParamScanner.substitute("WHERE d = $day(-1) AND x = $x", Map.of());
        assertEquals("WHERE d = $day(-1) AND x = $x", out, "unresolved / $-context tokens are untouched");
    }
}
