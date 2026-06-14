package com.gamma.acquire;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;

/**
 * Sequence-gap detection (Data Acquisition roadmap Phase D) — pure decision logic that, given a strftime-style
 * <em>sequence template</em> and the set of file names a discovery cycle observed, computes which expected
 * keys in the series are <b>missing</b> ("no file silently missed").
 *
 * <h3>Template grammar</h3>
 * A literal prefix/suffix around a single {@code {…}} token whose body is a Java
 * {@link java.time.format.DateTimeFormatter} pattern, e.g. {@code "CDR_{yyyyMMddHH}"} ⇒ an hourly series
 * {@code CDR_2026061400}, {@code CDR_2026061401}, …. The finest pattern field present sets the step:
 * {@code s}→seconds, {@code m}→minutes, {@code H}/{@code h}→hours, {@code d}→days, {@code M}→months,
 * {@code y}/{@code u}→years. Fields coarser-than-present default (month/day→1, hour/min/sec→0, year→2000) so a
 * partial pattern still resolves to a {@link LocalDateTime}.
 *
 * <h3>Semantics</h3>
 * Only names matching the template participate; the rest are ignored. From the lowest to the highest observed
 * key the detector enumerates the expected contiguous series at the step granularity and reports every point
 * not observed. Fewer than two matching keys ⇒ no series ⇒ no gaps. Enumeration is capped
 * ({@link #MAX_SERIES}) so a pathological min/max span can never run away.
 *
 * <p>Pure and side-effect-free (the engine emits the {@code SEQUENCE_GAP} events / metric), so it is unit
 * tested directly — the same shape as {@link DuplicatePolicy} / {@link RetrievalPlanner}.
 */
public final class GapDetector {

    private GapDetector() {}

    /** Safety cap on the enumerated series length — a malformed template can't produce an unbounded scan. */
    public static final int MAX_SERIES = 1_000_000;

    /** The outcome of one detection pass: the matched keys, the holes, and the step unit (for the event attrs). */
    public record GapReport(String template, List<String> observed, List<String> missing, ChronoUnit unit) {
        public GapReport {
            observed = List.copyOf(observed);
            missing  = List.copyOf(missing);
        }
        /** Whether the series has at least one hole. */
        public boolean hasGaps() { return !missing.isEmpty(); }
    }

    /**
     * Detect holes in the {@code template} series across {@code names} (discovered file names). Returns the
     * matched keys (sorted, de-duplicated) and the missing keys (sorted), both formatted with the template's
     * pattern. A template with no {@code {…}} token throws — callers validate config once at parse time.
     */
    public static GapReport findGaps(String template, Collection<String> names) {
        Parsed t = parse(template);   // throws on a malformed template

        // Map each observed point → its full file name; TreeMap keeps them ordered and de-duplicated by instant.
        TreeMap<LocalDateTime, String> observed = new TreeMap<>();
        for (String name : names) {
            var m = t.matcher.matcher(name);
            if (!m.matches()) continue;
            try {
                observed.put(LocalDateTime.parse(m.group(1), t.formatter), name);
            } catch (RuntimeException malformed) {
                // matched the shape but not a valid date (e.g. month 13) — not part of the series
            }
        }
        if (observed.size() < 2)
            return new GapReport(template, new ArrayList<>(observed.values()), List.of(), t.unit);

        List<String> missing = new ArrayList<>();
        LocalDateTime cursor = observed.firstKey();
        LocalDateTime end     = observed.lastKey();
        int guard = 0;
        while (!cursor.isAfter(end) && guard++ < MAX_SERIES) {
            if (!observed.containsKey(cursor))
                missing.add(t.prefix + t.formatter.format(cursor) + t.suffix);   // full expected file name
            cursor = cursor.plus(1, t.unit);
        }
        return new GapReport(template, new ArrayList<>(observed.values()), missing, t.unit);
    }

    // ── template parsing ─────────────────────────────────────────────────────────────────────

    private record Parsed(java.util.regex.Pattern matcher, DateTimeFormatter formatter, ChronoUnit unit,
                          String prefix, String suffix) {}

    private static Parsed parse(String template) {
        if (template == null || template.isBlank())
            throw new IllegalArgumentException("gap sequence template is blank");
        int open = template.indexOf('{');
        int close = template.indexOf('}', open + 1);
        if (open < 0 || close < 0)
            throw new IllegalArgumentException(
                    "gap sequence template must contain a {pattern} token, e.g. \"CDR_{yyyyMMddHH}\": " + template);
        String prefix  = template.substring(0, open);
        String pattern = template.substring(open + 1, close);
        String suffix  = template.substring(close + 1);
        if (pattern.isBlank())
            throw new IllegalArgumentException("gap sequence token {…} is empty: " + template);

        // A name matches when prefix + (digits shaped like the pattern) + suffix matches the whole string.
        String regex = java.util.regex.Pattern.quote(prefix) + "(" + tokenRegex(pattern) + ")"
                + java.util.regex.Pattern.quote(suffix);
        java.util.regex.Pattern matcher = java.util.regex.Pattern.compile("^" + regex + "$");

        // Default only the fields the pattern does NOT itself carry, so a partial pattern resolves to a
        // LocalDateTime — defaulting a field the pattern parses would raise a "Conflict found" at parse time.
        DateTimeFormatterBuilder fb = new DateTimeFormatterBuilder().appendPattern(pattern);
        if (!has(pattern, "yu"))  fb.parseDefaulting(ChronoField.YEAR, 2000);
        if (!has(pattern, "ML"))  fb.parseDefaulting(ChronoField.MONTH_OF_YEAR, 1);
        if (!has(pattern, "dD"))  fb.parseDefaulting(ChronoField.DAY_OF_MONTH, 1);
        if (!has(pattern, "HhKk")) fb.parseDefaulting(ChronoField.HOUR_OF_DAY, 0);
        if (!has(pattern, "m"))   fb.parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0);
        if (!has(pattern, "s"))   fb.parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0);

        return new Parsed(matcher, fb.toFormatter(), stepUnit(pattern), prefix, suffix);
    }

    /** Turn a date pattern into a digit-shaped regex: each run of pattern letters → {@code \d{len}}, literals quoted. */
    private static String tokenRegex(String pattern) {
        StringBuilder sb = new StringBuilder();
        int i = 0, n = pattern.length();
        while (i < n) {
            char c = pattern.charAt(i);
            if (Character.isLetter(c)) {
                int j = i;
                while (j < n && pattern.charAt(j) == c) j++;
                sb.append("\\d{").append(j - i).append('}');
                i = j;
            } else {
                sb.append(java.util.regex.Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return sb.toString();
    }

    /** Whether {@code pattern} contains any of the {@code letters} (a pattern-field presence test). */
    private static boolean has(String pattern, String letters) {
        for (int i = 0; i < letters.length(); i++)
            if (pattern.indexOf(letters.charAt(i)) >= 0) return true;
        return false;
    }

    /** The step granularity = the finest temporal field present in the pattern. */
    private static ChronoUnit stepUnit(String pattern) {
        if (pattern.indexOf('s') >= 0)                            return ChronoUnit.SECONDS;
        if (pattern.indexOf('m') >= 0)                            return ChronoUnit.MINUTES;
        if (pattern.indexOf('H') >= 0 || pattern.indexOf('h') >= 0) return ChronoUnit.HOURS;
        if (pattern.indexOf('d') >= 0 || pattern.indexOf('D') >= 0) return ChronoUnit.DAYS;
        if (pattern.indexOf('M') >= 0 || pattern.indexOf('L') >= 0) return ChronoUnit.MONTHS;
        return ChronoUnit.YEARS;   // y / u only
    }
}
