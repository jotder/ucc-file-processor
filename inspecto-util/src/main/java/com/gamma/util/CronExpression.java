package com.gamma.util;

import com.gamma.api.PublicApi;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * A tiny, dependency-free <b>quartz-like</b> cron parser and next-fire calculator.
 * It powers config-driven scheduling ({@code com.gamma.job}) without pulling in
 * Quartz — consistent with the rest of the 2.x line, which adds zero runtime
 * dependencies.
 *
 * <h3>Format</h3>
 * Five or six space-separated fields. Six fields lead with seconds; five fields
 * imply {@code seconds = 0}:
 * <pre>
 *   ┌───────────── second        (0-59)   [6-field form only]
 *   │ ┌─────────── minute        (0-59)
 *   │ │ ┌───────── hour          (0-23)
 *   │ │ │ ┌─────── day-of-month  (1-31)
 *   │ │ │ │ ┌───── month         (1-12 or JAN-DEC)
 *   │ │ │ │ │ ┌─── day-of-week   (0-7 or SUN-SAT; 0 and 7 are Sunday)
 *   │ │ │ │ │ │
 *   *  *  *  *  *  *
 * </pre>
 *
 * <h3>Each field accepts</h3>
 * <ul>
 *   <li>{@code *} — every value</li>
 *   <li>{@code a} — a single value</li>
 *   <li>{@code a-b} — an inclusive range</li>
 *   <li>{@code a-b/n} or {@code * /n} or {@code a/n} — a stepped range</li>
 *   <li>{@code a,b,c-d} — a comma list of any of the above</li>
 * </ul>
 * Month and day-of-week names (case-insensitive {@code JAN}…, {@code SUN}…) are
 * accepted in place of numbers. The quartz extensions {@code ? L W #} are <b>not</b>
 * supported — this is standard-cron semantics, deliberately small.
 *
 * <h3>Day-of-month / day-of-week</h3>
 * When <em>both</em> are restricted (neither is {@code *}), a time matches if it
 * satisfies <em>either</em> — the long-standing Vixie-cron convention. When only one
 * is restricted, only that one constrains the day.
 *
 * <h3>Examples</h3>
 * <pre>
 *   "0 * * * *"        every hour, on the hour
 *   "*&#47;30 * * * * *"   every 30 seconds
 *   "0 2 * * *"        daily at 02:00
 *   "0 0 1 * *"        midnight on the 1st of every month
 *   "0 9 * * MON-FRI"  09:00 on weekdays
 * </pre>
 *
 * <p>Instances are immutable and thread-safe.
 */
@PublicApi(since = "4.0.0")
public final class CronExpression {

    private final boolean[] seconds = new boolean[60];
    private final boolean[] minutes = new boolean[60];
    private final boolean[] hours   = new boolean[24];
    private final boolean[] daysOfMonth = new boolean[32];  // 1-31
    private final boolean[] months  = new boolean[13];       // 1-12
    private final boolean[] daysOfWeek = new boolean[8];     // 0-7 (0 and 7 = Sunday)
    private final boolean domRestricted;
    private final boolean dowRestricted;
    private final String raw;

    private static final Map<String, Integer> MONTHS = monthNames();
    private static final Map<String, Integer> DOWS   = dowNames();

    private CronExpression(String raw, boolean domRestricted, boolean dowRestricted) {
        this.raw = raw;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
    }

    /**
     * Parse a cron string.
     *
     * @throws IllegalArgumentException if it is not 5 or 6 fields, or any field is malformed
     */
    public static CronExpression parse(String expr) {
        if (expr == null || expr.isBlank())
            throw new IllegalArgumentException("Empty cron expression");
        String[] f = expr.trim().split("\\s+");
        if (f.length != 5 && f.length != 6)
            throw new IllegalArgumentException(
                    "Cron must have 5 or 6 fields, got " + f.length + ": '" + expr + "'");

        boolean sixField = f.length == 6;
        int i = 0;
        boolean[] sec = new boolean[60];
        if (sixField) fill(sec, f[i++], 0, 59, null, "seconds");
        else sec[0] = true;   // 5-field form fires at second 0

        boolean[] min = new boolean[60];
        boolean[] hr  = new boolean[24];
        boolean[] dom = new boolean[32];
        boolean[] mon = new boolean[13];
        boolean[] dow = new boolean[8];

        fill(min, f[i++], 0, 59, null, "minutes");
        fill(hr,  f[i++], 0, 23, null, "hours");
        String domField = f[i++];
        fill(dom, domField, 1, 31, null, "day-of-month");
        fill(mon, f[i++], 1, 12, MONTHS, "month");
        String dowField = f[i];
        fill(dow, dowField, 0, 7, DOWS, "day-of-week");
        if (dow[7]) dow[0] = true;   // normalise: 7 == Sunday == 0

        CronExpression c = new CronExpression(expr.trim(),
                !isWildcard(domField), !isWildcard(dowField));
        System.arraycopy(sec, 0, c.seconds, 0, 60);
        System.arraycopy(min, 0, c.minutes, 0, 60);
        System.arraycopy(hr,  0, c.hours,   0, 24);
        System.arraycopy(dom, 0, c.daysOfMonth, 0, 32);
        System.arraycopy(mon, 0, c.months,  0, 13);
        System.arraycopy(dow, 0, c.daysOfWeek, 0, 8);
        return c;
    }

    /**
     * The next instant strictly after {@code from} that matches this expression,
     * preserving {@code from}'s zone. Resolves to whole seconds.
     *
     * @throws IllegalStateException if no match is found within ~4 years (an
     *         impossible expression, e.g. Feb 30)
     */
    public ZonedDateTime next(ZonedDateTime from) {
        // Start at the next whole second after `from`.
        ZonedDateTime t = from.withNano(0).plusSeconds(1);
        // Coarse field-by-field advance: jump the most-significant mismatching field,
        // resetting everything below it, so we converge in very few iterations.
        for (int guard = 0; guard < 1_000_000; guard++) {
            if (!months[t.getMonthValue()]) {
                t = t.plusMonths(1).withDayOfMonth(1).with(java.time.LocalTime.MIDNIGHT);
                continue;
            }
            if (!dayMatches(t)) {
                t = t.plusDays(1).with(java.time.LocalTime.MIDNIGHT);
                continue;
            }
            if (!hours[t.getHour()]) {
                t = t.plusHours(1).withMinute(0).withSecond(0);
                continue;
            }
            if (!minutes[t.getMinute()]) {
                t = t.plusMinutes(1).withSecond(0);
                continue;
            }
            if (!seconds[t.getSecond()]) {
                t = t.plusSeconds(1);
                continue;
            }
            return t;
        }
        throw new IllegalStateException("No matching time within 4 years for cron '" + raw + "'");
    }

    /** The original (trimmed) expression text. */
    public String expression() { return raw; }

    @Override public String toString() { return raw; }

    // ── matching ─────────────────────────────────────────────────────────────────

    private boolean dayMatches(ZonedDateTime t) {
        int dom = t.getDayOfMonth();
        // java DayOfWeek: MON=1..SUN=7; cron: SUN=0..SAT=6
        int dow = t.getDayOfWeek().getValue() % 7;   // SUN→0, MON→1, …, SAT→6
        boolean domOk = daysOfMonth[dom];
        boolean dowOk = daysOfWeek[dow];
        if (domRestricted && dowRestricted) return domOk || dowOk;  // Vixie-cron OR
        if (domRestricted) return domOk;
        if (dowRestricted) return dowOk;
        return true;   // both wildcard
    }

    // ── parsing ──────────────────────────────────────────────────────────────────

    private static boolean isWildcard(String field) {
        return "*".equals(field.trim());
    }

    /** Populate {@code slots} for one field over [min,max], honouring lists/ranges/steps. */
    private static void fill(boolean[] slots, String field, int min, int max,
                             Map<String, Integer> names, String label) {
        for (String item : field.split(",")) {
            item = item.trim();
            if (item.isEmpty())
                throw new IllegalArgumentException("Empty term in " + label + " field");

            int step = 1;
            int slash = item.indexOf('/');
            String rangePart = item;
            if (slash >= 0) {
                rangePart = item.substring(0, slash);
                step = parseInt(item.substring(slash + 1), names, label);
                if (step <= 0) throw new IllegalArgumentException("Step must be > 0 in " + label + ": " + item);
            }

            int lo, hi;
            if (rangePart.equals("*")) {
                lo = min; hi = max;
            } else {
                int dash = rangePart.indexOf('-', rangePart.startsWith("-") ? 1 : 0);
                if (dash > 0) {
                    lo = parseInt(rangePart.substring(0, dash), names, label);
                    hi = parseInt(rangePart.substring(dash + 1), names, label);
                } else {
                    lo = parseInt(rangePart, names, label);
                    // `a/n` (no explicit hi) steps from a to the field max
                    hi = (slash >= 0) ? max : lo;
                }
            }
            if (lo < min || hi > max || lo > hi)
                throw new IllegalArgumentException(
                        label + " value out of range [" + min + "-" + max + "]: " + item);
            for (int v = lo; v <= hi; v += step) slots[v] = true;
        }
    }

    private static int parseInt(String s, Map<String, Integer> names, String label) {
        s = s.trim();
        if (names != null) {
            Integer n = names.get(s.toUpperCase());
            if (n != null) return n;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + " value: '" + s + "'");
        }
    }

    private static Map<String, Integer> monthNames() {
        Map<String, Integer> m = new HashMap<>();
        String[] n = {"JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"};
        for (int i = 0; i < n.length; i++) m.put(n[i], i + 1);
        return m;
    }

    private static Map<String, Integer> dowNames() {
        Map<String, Integer> m = new HashMap<>();
        String[] n = {"SUN","MON","TUE","WED","THU","FRI","SAT"};
        for (int i = 0; i < n.length; i++) m.put(n[i], i);
        return m;
    }
}
