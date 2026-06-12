package com.gamma.agent.skill;

/**
 * Shared request-input coercion helpers for the assist skills (v4.1, B1 hardening). Every skill
 * resolves loosely-typed wire input ({@code screenContext} / {@code partialInput} values) the same
 * way; these were previously six private copies per skill. Semantics are exactly the originals:
 * blank counts as absent, {@code asBool} accepts true/1/yes case-insensitively.
 */
final class SkillInputs {

    private SkillInputs() {}

    /** {@code toString} or {@code null} — wire values may be any JSON scalar. */
    static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** The value unless null/blank, else the fallback. */
    static String orDefault(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /** The first non-blank value, or {@code null}. */
    static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    /** Lenient boolean: true / 1 / yes (case-insensitive); anything else is false. */
    static boolean asBool(String v) {
        return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"));
    }
}
