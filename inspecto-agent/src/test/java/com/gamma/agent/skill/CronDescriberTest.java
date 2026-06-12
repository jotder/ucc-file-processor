package com.gamma.agent.skill;

import com.gamma.service.CronExpression;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CronDescriber} (deterministic cron → English) and the {@code nextRuns}
 * sequence the skill builds on the core {@link CronExpression}. No model involved — fully CPU-only.
 */
class CronDescriberTest {

    @Test
    void describesCommonShapes() {
        assertEquals("every day at 02:00", CronDescriber.describe("0 2 * * *"));
        assertEquals("every 15 minutes", CronDescriber.describe("*/15 * * * *"));
        assertEquals("every hour", CronDescriber.describe("0 * * * *"));
        assertEquals("every minute", CronDescriber.describe("* * * * *"));
    }

    @Test
    void describesDaysOfWeek() {
        assertEquals("every day at 06:00 on weekdays", CronDescriber.describe("0 6 * * MON-FRI"));
        assertEquals("every day at 09:00 on Monday", CronDescriber.describe("0 9 * * MON"));
        assertEquals("every day at 08:30 on Monday, Wednesday and Friday",
                CronDescriber.describe("30 8 * * MON,WED,FRI"));
        assertEquals("every day at 10:00 on weekends", CronDescriber.describe("0 10 * * SAT,SUN"));
    }

    @Test
    void describesMonthlyAndSeconds() {
        assertEquals("every day at 00:00 on day 1 of the month", CronDescriber.describe("0 0 1 * *"));
        assertEquals("every 30 seconds", CronDescriber.describe("*/30 * * * * *"));
    }

    @Test
    void blankAndExoticAreSafe() {
        assertEquals("no calendar schedule (event-triggered or manual)", CronDescriber.describe(""));
        assertEquals("no calendar schedule (event-triggered or manual)", CronDescriber.describe(null));
        // 7 fields is not valid cron — must not throw, falls back to a generic phrasing.
        assertTrue(CronDescriber.describe("0 0 0 0 0 0 0").startsWith("custom schedule"));
    }

    @Test
    void everyDescribedCronIsRealAndParses() {
        // The describer must only ever be handed cron the oracle accepts — sanity-check the fixtures.
        for (String c : List.of("0 2 * * *", "*/15 * * * *", "0 6 * * MON-FRI", "0 0 1 * *", "*/30 * * * * *")) {
            assertDoesNotThrow(() -> CronExpression.parse(c), c);
            assertNotNull(CronDescriber.describe(c));
        }
    }

    @Test
    void nextRunsSequenceIsStrictlyIncreasing() {
        CronExpression daily = CronExpression.parse("0 6 * * MON-FRI");
        ZonedDateTime from = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneId.of("UTC")); // a Monday
        List<ZonedDateTime> runs = new ArrayList<>();
        ZonedDateTime cursor = from;
        for (int i = 0; i < 5; i++) {
            cursor = daily.next(cursor);
            runs.add(cursor);
        }
        assertEquals(5, runs.size());
        for (int i = 1; i < runs.size(); i++) {
            assertTrue(runs.get(i).isAfter(runs.get(i - 1)), "strictly increasing");
        }
        // Weekday-only: none of the five fire on Sat/Sun.
        for (ZonedDateTime r : runs) {
            int dow = r.getDayOfWeek().getValue(); // 6=Sat, 7=Sun
            assertTrue(dow >= 1 && dow <= 5, "weekday only: " + r);
            assertEquals(6, r.getHour());
            assertEquals(0, r.getMinute());
        }
    }
}
