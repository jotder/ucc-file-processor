package com.gamma.agent.kernel.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The default {@link GroundingGuard}: verifies that <b>every number appearing in the narration also
 * appears in the allowed {@link Evidence}</b>. A model asked to narrate can hallucinate figures — the
 * single thing that would make prose unsafe — so this guard is strict and deterministic.
 *
 * <p>Generalized from UCC's {@code NarrativeGuard}: the report object is replaced by the recursively
 * collected numeric content of each {@link Evidence#value()}. A narrative number matches an evidence
 * number when equal within a small tolerance, or after a ×100 / ÷100 scale (so {@code 0.017} grounds
 * {@code 1.7%}), or as integers. Non-numeric narration is always grounded by this guard.
 */
public final class NumericGroundingGuard implements GroundingGuard {

    /** Numbers, optionally with thousands separators, a decimal part, and/or a trailing percent. */
    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?%?");
    /** A bare run of digits (used to mine numbers out of string values like timestamps). */
    private static final Pattern DIGITS = Pattern.compile("\\d+(?:\\.\\d+)?");
    /** Relative tolerance for a float match; integers compare exactly. */
    private static final double EPS = 1e-6;

    @Override
    public Verdict check(String narration, List<Evidence> allowed) {
        if (narration == null || narration.isBlank()) {
            return new Verdict(false, List.of("(empty narration)"));
        }
        List<Double> grounded = new ArrayList<>();
        if (allowed != null) {
            for (Evidence e : allowed) collect(e.value(), grounded);
        }
        TreeSet<String> ungrounded = new TreeSet<>();
        Matcher m = NUMBER.matcher(narration);
        while (m.find()) {
            Double val = parse(m.group());
            if (val == null) continue;
            if (!isGrounded(val, grounded)) ungrounded.add(m.group());
        }
        return ungrounded.isEmpty() ? Verdict.ok() : new Verdict(false, new ArrayList<>(ungrounded));
    }

    /** Recursively gather every numeric value the evidence exposes (incl. digit runs inside strings). */
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
                if (node.getClass().isArray()) return;
                Double v = parse(node.toString());
                if (v != null) out.add(v);
            }
        }
    }

    private static boolean isGrounded(double x, List<Double> allowed) {
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
