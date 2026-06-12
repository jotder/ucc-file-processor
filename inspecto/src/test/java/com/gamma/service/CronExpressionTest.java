package com.gamma.service;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the dependency-free {@link CronExpression} parser + next-fire calculator.
 * Times are computed in a fixed zone so assertions are deterministic.
 */
class CronExpressionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static ZonedDateTime utc(int y, int mo, int d, int h, int mi, int s) {
        return ZonedDateTime.of(y, mo, d, h, mi, s, 0, UTC);
    }

    @Test
    void everyHourOnTheHour() {
        CronExpression c = CronExpression.parse("0 * * * *");
        ZonedDateTime next = c.next(utc(2026, 5, 30, 10, 15, 30));
        assertEquals(utc(2026, 5, 30, 11, 0, 0), next);
    }

    @Test
    void dailyAtTwoAm() {
        CronExpression c = CronExpression.parse("0 2 * * *");
        // before 02:00 → same day
        assertEquals(utc(2026, 5, 30, 2, 0, 0), c.next(utc(2026, 5, 30, 1, 59, 0)));
        // after 02:00 → next day
        assertEquals(utc(2026, 5, 31, 2, 0, 0), c.next(utc(2026, 5, 30, 2, 0, 1)));
    }

    @Test
    void sixFieldEveryThirtySeconds() {
        CronExpression c = CronExpression.parse("*/30 * * * * *");
        assertEquals(utc(2026, 5, 30, 10, 0, 30), c.next(utc(2026, 5, 30, 10, 0, 0)));
        assertEquals(utc(2026, 5, 30, 10, 1, 0),  c.next(utc(2026, 5, 30, 10, 0, 30)));
    }

    @Test
    void fiveFieldImpliesSecondZero() {
        CronExpression c = CronExpression.parse("*/15 * * * *");   // every 15 minutes
        ZonedDateTime next = c.next(utc(2026, 5, 30, 10, 7, 42));
        assertEquals(utc(2026, 5, 30, 10, 15, 0), next);
        assertEquals(0, next.getSecond(), "5-field form fires at second 0");
    }

    @Test
    void monthlyOnTheFirst() {
        CronExpression c = CronExpression.parse("0 0 1 * *");
        assertEquals(utc(2026, 6, 1, 0, 0, 0), c.next(utc(2026, 5, 30, 12, 0, 0)));
    }

    @Test
    void weekdaysAtNine() {
        CronExpression c = CronExpression.parse("0 9 * * MON-FRI");
        // 2026-05-30 is a Saturday → next weekday 09:00 is Monday Jun 1
        ZonedDateTime next = c.next(utc(2026, 5, 30, 12, 0, 0));
        assertEquals(utc(2026, 6, 1, 9, 0, 0), next);
        assertEquals(java.time.DayOfWeek.MONDAY, next.getDayOfWeek());
    }

    @Test
    void namedMonthAndList() {
        CronExpression c = CronExpression.parse("0 0 0 1 JAN,JUL *");
        // 6-field: sec min hour dom month dow — midnight Jan 1 and Jul 1
        assertEquals(utc(2026, 7, 1, 0, 0, 0), c.next(utc(2026, 5, 30, 0, 0, 0)));
        assertEquals(utc(2027, 1, 1, 0, 0, 0), c.next(utc(2026, 7, 1, 0, 0, 0)));
    }

    @Test
    void rangeAndStepInHours() {
        CronExpression c = CronExpression.parse("0 0 9-17/4 * * *");   // 09,13,17
        assertEquals(utc(2026, 5, 30, 9,  0, 0), c.next(utc(2026, 5, 30, 8, 0, 0)));
        assertEquals(utc(2026, 5, 30, 13, 0, 0), c.next(utc(2026, 5, 30, 9, 0, 0)));
        assertEquals(utc(2026, 5, 30, 17, 0, 0), c.next(utc(2026, 5, 30, 13, 0, 0)));
        assertEquals(utc(2026, 5, 31, 9,  0, 0), c.next(utc(2026, 5, 30, 17, 0, 0)));
    }

    @Test
    void domOrDowWhenBothRestricted() {
        // 1st of month OR any Monday (Vixie-cron OR semantics)
        CronExpression c = CronExpression.parse("0 0 1 * MON");
        // From mid-May 2026: next is Mon Jun 1 (both conditions), but the very next match
        // from May 30 (Sat) is Mon Jun 1.
        ZonedDateTime n1 = c.next(utc(2026, 5, 30, 0, 0, 0));
        assertEquals(utc(2026, 6, 1, 0, 0, 0), n1);
        // After Jun 1, next Monday is Jun 8 (dow match), well before the 1st of next month.
        assertEquals(utc(2026, 6, 8, 0, 0, 0), c.next(utc(2026, 6, 1, 0, 0, 0)));
    }

    @Test
    void sundayAsZeroOrSeven() {
        CronExpression zero  = CronExpression.parse("0 0 * * 0");
        CronExpression seven = CronExpression.parse("0 0 * * 7");
        ZonedDateTime from = utc(2026, 5, 30, 0, 0, 0);   // Saturday
        assertEquals(zero.next(from), seven.next(from), "0 and 7 both mean Sunday");
        assertEquals(java.time.DayOfWeek.SUNDAY, zero.next(from).getDayOfWeek());
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("0 0 0 0 *"));     // month 0 invalid
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("99 * * * *"));    // minute 99
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("0 0 * * FOO"));   // bad dow name
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("*/0 * * * *"));   // zero step
    }
}
