package com.gamma.agent.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a cron expression into a one-line, human-readable English description — the
 * {@code humanReadable} field of an {@code nl-to-schedule} draft. This is deterministic
 * presentation (no model), so a UI and the golden tests can rely on it.
 *
 * <p>The codebase had no cron describer (the core {@link com.gamma.service.CronExpression} only
 * <em>evaluates</em> a schedule), so this fills that gap. It recognises the common shapes
 * (every-N-seconds/minutes/hours, hourly, daily-at, weekday/weekend, named days-of-week, monthly)
 * and falls back to a safe generic phrasing for anything exotic — it never throws and never claims
 * more than it can prove, because a wrong description is worse than a generic one.
 *
 * <p>Accepts both 5-field ({@code min hour dom month dow}) and 6-field ({@code sec min hour dom
 * month dow}) cron, matching {@link com.gamma.service.CronExpression}.
 *
 * @since 3.4.0
 */
public final class CronDescriber {

    private CronDescriber() {}

    private static final String[] DOW_NAMES = {
            "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
    };

    /** Describe a cron expression in English, or a safe generic phrasing if it is unusual/blank. */
    public static String describe(String cron) {
        if (cron == null || cron.isBlank()) {
            return "no calendar schedule (event-triggered or manual)";
        }
        String[] f = cron.trim().split("\\s+");
        String sec, min, hour, dom, mon, dow;
        if (f.length == 6) {
            sec = f[0]; min = f[1]; hour = f[2]; dom = f[3]; mon = f[4]; dow = f[5];
        } else if (f.length == 5) {
            sec = "0"; min = f[0]; hour = f[1]; dom = f[2]; mon = f[3]; dow = f[4];
        } else {
            return "custom schedule (" + cron.trim() + ")";
        }
        try {
            return build(sec, min, hour, dom, mon, dow, cron.trim());
        } catch (RuntimeException e) {
            return "custom schedule (" + cron.trim() + ")";
        }
    }

    private static String build(String sec, String min, String hour,
                                String dom, String mon, String dow, String raw) {
        // Sub-minute schedules (6-field) — describe the seconds cadence directly.
        if (!"0".equals(sec) && isWildcard(min) && isWildcard(hour)) {
            Integer everySec = everyStep(sec);
            if (everySec != null) return atDays("every " + everySec + " seconds", dom, mon, dow);
            if ("*".equals(sec)) return atDays("every second", dom, mon, dow);
        }

        String time = describeTime(min, hour);
        return atDays(time, dom, mon, dow);
    }

    /** The minute/hour cadence as a phrase (e.g. "every 15 minutes", "every day at 06:00"). */
    private static String describeTime(String min, String hour) {
        Integer everyMin = everyStep(min);
        if (everyMin != null && isWildcard(hour)) return "every " + everyMin + " minutes";
        if ("*".equals(min) && "*".equals(hour)) return "every minute";

        Integer everyHour = everyStep(hour);
        if ("0".equals(min) && everyHour != null) return "every " + everyHour + " hours";
        if ("0".equals(min) && "*".equals(hour)) return "every hour";

        Integer m = asInt(min);
        Integer h = asInt(hour);
        if (m != null && h != null) return "every day at " + hhmm(h, m);
        if (m != null && "*".equals(hour)) return "at minute " + m + " of every hour";

        // Composite hour list/range at a fixed minute, e.g. "0 9-17/4 * * *".
        if (m != null) return "at minute " + m + " past hour(s) " + hour;
        return "at minute(s) " + min + " of hour(s) " + hour;
    }

    /** Wrap a time phrase with the day-of-week / day-of-month / month qualifier. */
    private static String atDays(String time, String dom, String mon, String dow) {
        String dowPhrase = isWildcard(dow) ? null : describeDow(dow);
        String domPhrase = isWildcard(dom) ? null : "on day " + dom + " of the month";
        String monPhrase = isWildcard(mon) ? null : "in " + mon;

        StringBuilder sb = new StringBuilder(time);
        if (dowPhrase != null && domPhrase != null) {
            // Vixie-cron OR semantics when both are restricted.
            sb.append(" on ").append(dowPhrase).append(" or ").append(domPhrase.substring(3));
        } else if (dowPhrase != null) {
            sb.append(" on ").append(dowPhrase);
        } else if (domPhrase != null) {
            sb.append(' ').append(domPhrase);
        } else {
            // time already implies "every day" where relevant; leave as-is
        }
        if (monPhrase != null) sb.append(' ').append(monPhrase);
        return sb.toString();
    }

    /** Day-of-week field → English, recognising weekdays/weekends and named/numeric days. */
    private static String describeDow(String dow) {
        String norm = dow.toUpperCase();
        if (norm.equals("MON-FRI") || norm.equals("1-5")) return "weekdays";
        if (norm.equals("SAT,SUN") || norm.equals("SUN,SAT")
                || norm.equals("0,6") || norm.equals("6,0") || norm.equals("6,7")) return "weekends";

        List<Integer> days = new ArrayList<>();
        for (String part : norm.split(",")) {
            int dash = part.indexOf('-');
            if (dash > 0) {
                int a = dowNum(part.substring(0, dash));
                int b = dowNum(part.substring(dash + 1));
                for (int d = a; d <= b; d++) days.add(d % 7);
            } else {
                days.add(dowNum(part));
            }
        }
        List<String> names = new ArrayList<>();
        for (int d : days) names.add(DOW_NAMES[d % 7]);
        return joinAnd(names);
    }

    private static int dowNum(String token) {
        String t = token.trim();
        return switch (t) {
            case "SUN", "0", "7" -> 0;
            case "MON", "1" -> 1;
            case "TUE", "2" -> 2;
            case "WED", "3" -> 3;
            case "THU", "4" -> 4;
            case "FRI", "5" -> 5;
            case "SAT", "6" -> 6;
            default -> throw new IllegalArgumentException("dow: " + token);
        };
    }

    /** "Monday", "Monday and Friday", "Monday, Wednesday and Friday". */
    private static String joinAnd(List<String> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        String last = items.get(items.size() - 1);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + last;
    }

    /** The step N of a star-slash-N field (e.g. the 15 in {@code "0/15"}-style steps), else null. */
    private static Integer everyStep(String field) {
        if (field.startsWith("*/")) {
            try {
                return Integer.parseInt(field.substring(2));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Integer asInt(String field) {
        try {
            return Integer.parseInt(field);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isWildcard(String field) {
        return "*".equals(field) || "?".equals(field);
    }

    private static String hhmm(int h, int m) {
        return String.format("%02d:%02d", h, m);
    }
}
