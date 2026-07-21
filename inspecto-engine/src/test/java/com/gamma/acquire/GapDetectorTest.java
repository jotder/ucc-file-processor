package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-D sequence-gap detection — pure logic over a template + observed file names. */
class GapDetectorTest {

    @Test
    void detectsAnHourlyHoleAndReportsTheUnit() {
        var r = GapDetector.findGaps("CDR_{yyyyMMddHH}",
                List.of("CDR_2026061400", "CDR_2026061401", "CDR_2026061403"));
        assertEquals(List.of("CDR_2026061402"), r.missing());
        assertEquals(ChronoUnit.HOURS, r.unit());
        assertTrue(r.hasGaps());
    }

    @Test
    void contiguousSeriesHasNoGaps() {
        var r = GapDetector.findGaps("CDR_{yyyyMMddHH}",
                List.of("CDR_2026061400", "CDR_2026061401", "CDR_2026061402"));
        assertTrue(r.missing().isEmpty());
        assertFalse(r.hasGaps());
        assertEquals(3, r.observed().size());
    }

    @Test
    void multipleHolesAcrossADayRollover() {
        // 23:00 then 01:00 next day ⇒ 00:00 missing; plus 22:00 missing before 23:00
        var r = GapDetector.findGaps("CDR_{yyyyMMddHH}",
                List.of("CDR_2026061421", "CDR_2026061423", "CDR_2026061501"));
        assertEquals(List.of("CDR_2026061422", "CDR_2026061500"), r.missing());
    }

    @Test
    void fewerThanTwoMatchesYieldsNoSeries() {
        assertTrue(GapDetector.findGaps("CDR_{yyyyMMddHH}", List.of("CDR_2026061400")).missing().isEmpty());
        assertTrue(GapDetector.findGaps("CDR_{yyyyMMddHH}", List.of()).missing().isEmpty());
    }

    @Test
    void dailyTemplateWithPrefixAndSuffix() {
        var r = GapDetector.findGaps("feed_{yyyyMMdd}.csv.gz",
                List.of("feed_20260610.csv.gz", "feed_20260612.csv.gz", "feed_20260613.csv.gz"));
        assertEquals(List.of("feed_20260611.csv.gz"), r.missing());
        assertEquals(ChronoUnit.DAYS, r.unit());
    }

    @Test
    void nonMatchingNamesAreIgnored() {
        var r = GapDetector.findGaps("CDR_{yyyyMMddHH}",
                List.of("CDR_2026061400", "README.txt", "notes.log", "CDR_2026061402"));
        assertEquals(List.of("CDR_2026061401"), r.missing());
        assertEquals(2, r.observed().size(), "only the two CDR_* names participate");
    }

    @Test
    void shapeMatchingButInvalidDateIsSkippedNotCrashed() {
        // CDR_2026061499 matches \d{10} but hour 99 is not a valid time → dropped, no crash
        var r = GapDetector.findGaps("CDR_{yyyyMMddHH}",
                List.of("CDR_2026061400", "CDR_2026061499", "CDR_2026061402"));
        assertEquals(List.of("CDR_2026061401"), r.missing());
    }

    @Test
    void monthlyStep() {
        var r = GapDetector.findGaps("m_{yyyyMM}",
                List.of("m_202601", "m_202603", "m_202604"));
        assertEquals(List.of("m_202602"), r.missing());
        assertEquals(ChronoUnit.MONTHS, r.unit());
    }

    @Test
    void malformedTemplateWithoutTokenThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> GapDetector.findGaps("CDR_yyyyMMddHH", List.of("CDR_2026061400")));
        assertThrows(IllegalArgumentException.class,
                () -> GapDetector.findGaps("", List.of("x")));
    }
}
