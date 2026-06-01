package com.gamma.agent.skill;

import com.gamma.config.spec.Finding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The extractive-grounding oracle for {@code report-narrative} (M8 / v3.8.0). A 2B model asked to
 * narrate a report can <em>hallucinate figures</em> — the single thing that would make the prose unsafe.
 * This guard makes the skill safe by being strict and deterministic: it verifies that <b>every number
 * appearing in the narrative also appears in the source report</b>. Any ungrounded figure is a
 * {@link Finding} fed back to the {@link RepairLoop}, so an invented number is repaired, never surfaced.
 *
 * <h3>What counts as "appears in the report"</h3>
 * The report's numeric content is collected recursively from its JSON map — numeric values, and digit
 * runs inside string values (so a timestamp like {@code 2026-06-01} grounds the year/month/day). A
 * narrative number matches a report number when they are equal within a small tolerance, or equal after
 * a ×100 / ÷100 scale (so a {@code 0.017} error-rate grounds a {@code 1.7%} mention), or equal as
 * integers. The model is instructed to restate the report's raw values and units (durations in
 * milliseconds), so no genuine narration needs to <em>compute</em> — it only needs to <em>quote</em>.
 */
final class NarrativeGuard {

    private NarrativeGuard() {}

    /** Numbers, optionally with thousands separators, a decimal part, and/or a trailing percent. */
    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?%?");
    /** A bare run of digits (used to mine numbers out of string values like timestamps). */
    private static final Pattern DIGITS = Pattern.compile("\\d+(?:\\.\\d+)?");

    /** Relative tolerance for a float match; integers compare exactly. */
    private static final double EPS = 1e-6;

    /**
     * Check {@code narrative} against {@code report}. Returns an empty list when every figure is
     * grounded, else one ERROR {@link Finding} per ungrounded figure (deduplicated).
     */
    static List<Finding> check(String narrative, Object report) {
        List<Finding> findings = new ArrayList<>();
        if (narrative == null || narrative.isBlank()) {
            findings.add(Finding.error("narrative", "the narrative is empty"));
            return findings;
        }
        List<Double> allowed = new ArrayList<>();
        collect(report, allowed);

        TreeSet<String> ungrounded = new TreeSet<>();
        Matcher m = NUMBER.matcher(narrative);
        while (m.find()) {
            String token = m.group();
            Double val = parse(token);
            if (val == null) continue;
            if (!grounded(val, allowed)) ungrounded.add(token);
        }
        for (String token : ungrounded) {
            findings.add(Finding.error("narrative",
                    "the figure '" + token + "' does not appear in the report — narrate only the "
                            + "report's own numbers (and units), do not compute or invent figures"));
        }
        return findings;
    }

    /** Recursively gather every numeric value the report exposes (incl. digit runs inside strings). */
    private static void collect(Object node, List<Double> out) {
        switch (node) {
            case null -> { }
            case Number n -> out.add(n.doubleValue());
            case Boolean ignored -> { }
            case Map<?, ?> map -> map.values().forEach(v -> collect(v, out));
            case Iterable<?> it -> it.forEach(v -> collect(v, out));
            case CharSequence s -> {
                Matcher d = DIGITS.matcher(s);
                while (d.find()) {
                    Double v = parse(d.group());
                    if (v != null) out.add(v);
                }
            }
            default -> {
                // Arrays of primitives are uncommon here; fall back to the string form.
                if (node.getClass().isArray()) return;
                Double v = parse(node.toString());
                if (v != null) out.add(v);
            }
        }
    }

    private static boolean grounded(double x, List<Double> allowed) {
        for (double a : allowed) {
            if (approx(x, a) || approx(x, a * 100) || approx(x, a / 100)) return true;
            if (Math.rint(x) == Math.rint(a) && approx(Math.rint(x), Math.rint(a))) return true;
        }
        return false;
    }

    private static boolean approx(double x, double a) {
        return Math.abs(x - a) <= EPS * Math.max(1.0, Math.abs(a));
    }

    private static Double parse(String token) {
        if (token == null) return null;
        String t = token.replace(",", "").replace("%", "").trim();
        if (t.isEmpty() || t.equals("-")) return null;
        try {
            return Double.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
