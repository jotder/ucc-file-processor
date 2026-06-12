package com.gamma.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link TarExtractor}: extract 'unknown' archives, CSV report, sentinel rerun, base validation. */
class TarExtractorTest {

    @Test
    void extractsReportsAndSkipsOnRerun(@TempDir Path base, @TempDir Path temp,
                                        @TempDir Path reports) throws Exception {
        TarFixtures.writeTar(base.resolve("unknown").resolve("delivery.tar.gz"),
                Map.of("inner/data_20260103.csv", "a,b\n1,2\n"));
        // Outside an 'unknown' dir → ignored.
        TarFixtures.writeTar(base.resolve("daily").resolve("routine.tar.gz"), Map.of("x.csv", "x"));

        new TarExtractor(base.toString(), temp.toString(), reports.toString(), false).run();

        Path extracted = temp.resolve("delivery").resolve("inner").resolve("data_20260103.csv");
        assertTrue(Files.exists(extracted), "member extracted under temp/<stem>/");
        assertTrue(Files.exists(temp.resolve("delivery").resolve(TarUtil.SENTINEL)), "sentinel written");
        assertFalse(Files.exists(temp.resolve("routine")), "archive outside 'unknown' ignored");

        String report = Files.readString(reports.resolve("extract_report.csv"));
        assertTrue(report.contains("delivery.tar.gz"), "report lists the archive");
        assertTrue(report.contains("extracted"), "report records the extraction");

        // Rerun with a fresh instance: the sentinel marks it already done.
        new TarExtractor(base.toString(), temp.toString(), reports.toString(), false).run();
        String rerun = Files.readString(reports.resolve("extract_report.csv"));
        assertTrue(rerun.contains("already_done"), "sentinel short-circuits the rerun");
    }

    @Test
    void dryRunExtractsNothingButWritesReport(@TempDir Path base, @TempDir Path temp,
                                              @TempDir Path reports) throws Exception {
        TarFixtures.writeTar(base.resolve("unknown").resolve("delivery.tar.gz"), Map.of("d.csv", "1"));

        new TarExtractor(base.toString(), temp.toString(), reports.toString(), true).run();

        assertFalse(Files.exists(temp.resolve("delivery")), "nothing extracted in dry-run");
        String report = Files.readString(reports.resolve("extract_report.csv.dryrun"));
        assertTrue(report.contains("DRY-RUN_OK"), "dry-run outcome recorded in the report");
    }

    @Test
    void missingBaseDirFailsFast(@TempDir Path dir) throws Exception {
        TarExtractor x = new TarExtractor(dir.resolve("ghost").toString(),
                dir.resolve("t").toString(), dir.toString(), true);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, x::run);
        assertTrue(e.getMessage().contains("base directory does not exist"));
    }
}
