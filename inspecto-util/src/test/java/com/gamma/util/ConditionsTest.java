package com.gamma.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The A2 condition grammar (rbac-abac-plan §4): parse-once predicates over nested attribute maps.
 * Semantics under test: DottedPath references (missing = null), numeric-normalized equality,
 * `in`/`contains` collection membership + substring, strict Boolean truthiness (fail-closed),
 * short-circuit and/or, and position-bearing parse errors (the authoring 422 gate).
 */
class ConditionsTest {

    /** The Access-Policy-shaped context (subject./resource./env.) the PDP will bind — but the
     *  evaluator itself is domain-agnostic: any nested map works. */
    private static Map<String, Object> ctx() {
        return Map.of(
                "subject", Map.of("id", "ana", "space", "fraud",
                        "dataScopes", List.of("fraud", "billing"), "clearance", 3),
                "resource", Map.of("kind", "dataset", "space", "billing",
                        "tags", List.of("pii", "regulated"), "classification", "restricted"),
                "env", Map.of("action", "write", "route", "/api/v1/components/dataset"));
    }

    @Test
    void equalityAndInequalityOverReferencesAndLiterals() {
        assertTrue(Conditions.parse("subject.space == 'fraud'").test(ctx()));
        assertFalse(Conditions.parse("subject.space == 'billing'").test(ctx()));
        assertTrue(Conditions.parse("resource.space != subject.space").test(ctx()));
        assertTrue(Conditions.parse("subject.clearance == 3").test(ctx()));
        assertTrue(Conditions.parse("subject.clearance == 3.0").test(ctx()),
                "numbers compare numerically across integer/decimal types");
        assertTrue(Conditions.parse("resource.missing == null").test(ctx()),
                "a missing attribute is null, comparable explicitly");
    }

    @Test
    void inIsMembershipAndContainsIsTheConversePlusSubstring() {
        assertTrue(Conditions.parse("resource.space in subject.dataScopes").test(ctx()));
        assertFalse(Conditions.parse("'roaming' in subject.dataScopes").test(ctx()));
        assertTrue(Conditions.parse("resource.tags contains 'pii'").test(ctx()));
        assertFalse(Conditions.parse("resource.tags contains 'public'").test(ctx()));
        assertTrue(Conditions.parse("env.route contains '/components/'").test(ctx()), "substring on strings");
        assertFalse(Conditions.parse("subject.id in resource.classification").test(ctx()),
                "`in` against a non-collection is false, never a throw");
    }

    @Test
    void booleanCombinatorsWithPrecedenceAndParentheses() {
        assertTrue(Conditions.parse(
                "resource.classification == 'restricted' and resource.space != subject.space").test(ctx()));
        // and binds tighter than or: false-or-(true-and-true)
        assertTrue(Conditions.parse(
                "subject.space == 'x' or resource.kind == 'dataset' and env.action == 'write'").test(ctx()));
        assertFalse(Conditions.parse(
                "(subject.space == 'x' or resource.kind == 'dataset') and env.action == 'read'").test(ctx()));
        assertTrue(Conditions.parse("not resource.space in subject.dataScopes or true").test(ctx()));
        assertFalse(Conditions.parse("not (resource.space in subject.dataScopes)").test(ctx()));
    }

    @Test
    void truthinessIsStrictBooleanFailClosed() {
        assertFalse(Conditions.parse("subject.id").test(ctx()), "a bare non-boolean reference is falsy");
        assertFalse(Conditions.parse("subject.missing").test(ctx()));
        assertTrue(Conditions.parse("not subject.missing").test(ctx()),
                "not over a missing attribute is deterministic");
        assertTrue(Conditions.parse("true").test(ctx()));
        assertFalse(Conditions.parse("false or false").test(ctx()));
        assertTrue(Conditions.parse("true").test(null), "a null context evaluates as empty, no throw");
        assertFalse(Conditions.parse("subject.id == 'ana'").test(null),
                "references against a null context are null — the comparison is false, never a throw");
    }

    @Test
    void parseErrorsCarryTheOffsetAndNeverHalfParse() {
        for (String bad : List.of("", "   ", "subject.space ==", "== 'x'", "(a == 1",
                "a = 1", "a == 'unterminated", "a == 1 extra", "a .. b == 1", "a == 1 &&  b == 2")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Conditions.parse(bad), "should reject: " + bad);
            assertNotNull(e.getMessage());
        }
        assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> Conditions.parse("subject.space === 'x'"))
                .getMessage().contains("offset"), "syntax errors carry a position");
    }

    @Test
    void parsedConditionIsReusableAcrossContexts() {
        Conditions.Condition c = Conditions.parse("resource.space != subject.space");
        assertTrue(c.test(ctx()));
        assertFalse(c.test(Map.of("subject", Map.of("space", "fraud"), "resource", Map.of("space", "fraud"))));
    }
}
