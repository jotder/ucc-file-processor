package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity + safety guard for {@link Parameters} (W4) — the Java mirror of the UI
 * {@code inspecto/query/parameters.ts}: built-in tokens, declared defaults, caller overrides, numeric
 * validation, and the strict isolation of the {@code $}-namespace from {@code :name} and {@code ${ENV:}}.
 */
class ParametersTest {

    private static final Parameters.Context CTX =
            new Parameters.Context(Instant.parse("2026-07-06T12:00:00Z"), "appUser", "analyst");

    @Test
    void resolvesBuiltins() {
        assertEquals("d = '2026-07-06'", Parameters.resolve("d = $today", List.of(), Map.of(), CTX));
        assertEquals("d >= '2026-06-29'", Parameters.resolve("d >= $day(-7)", List.of(), Map.of(), CTX));
        assertEquals("u = 'appUser'", Parameters.resolve("u = $current_user", List.of(), Map.of(), CTX));
        assertEquals("r = 'analyst'", Parameters.resolve("r = $role", List.of(), Map.of(), CTX));
    }

    @Test
    void declaredDefaultsAreTypedLiterals() {
        List<Parameters.Def> defs = List.of(
                new Parameters.Def("minAmt", "number", "5"),
                new Parameters.Def("region", "string", "BD"));
        assertEquals("amount > 5", Parameters.resolve("amount > $minAmt", defs, Map.of(), CTX),
                "number default emitted raw");
        assertEquals("region = 'BD'", Parameters.resolve("region = $region", defs, Map.of(), CTX),
                "string default single-quoted");
    }

    @Test
    void callerValueOverridesDefault() {
        List<Parameters.Def> defs = List.of(new Parameters.Def("minAmt", "number", "5"));
        assertEquals("amount > 15",
                Parameters.resolve("amount > $minAmt", defs, Map.of("minAmt", "15"), CTX));
    }

    @Test
    void nonNumericNumberParamRejected() {
        List<Parameters.Def> defs = List.of(new Parameters.Def("minAmt", "number", "5"));
        assertThrows(IllegalArgumentException.class,
                () -> Parameters.resolve("amount > $minAmt", defs, Map.of("minAmt", "10; DROP TABLE t"), CTX));
    }

    @Test
    void unknownTokenLeftVerbatim() {
        assertEquals("x = $nope", Parameters.resolve("x = $nope", List.of(), Map.of(), CTX));
    }

    @Test
    void otherNamespacesUntouched() {
        // ${ENV:SECRET} (config-time secret) and :fieldValue (rule template) must survive verbatim;
        // only $today resolves.
        String out = Parameters.resolve("a=${ENV:SECRET} b=:fieldValue c=$today", List.of(), Map.of(), CTX);
        assertEquals("a=${ENV:SECRET} b=:fieldValue c='2026-07-06'", out);
    }
}
