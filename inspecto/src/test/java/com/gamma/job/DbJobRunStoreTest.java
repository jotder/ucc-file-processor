package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DbJobRunStore} (T27): the DuckDB projection of job runs that backs the reporting endpoints.
 * Uses an in-memory DuckDB ({@code jdbc:duckdb:}) so it needs no files and no new dependency.
 */
class DbJobRunStoreTest {

    private static JobRun run(String id, String job, String status, long ms, String start) {
        return new JobRun(id, job, "MAINTENANCE", "schedule", start, start, status, ms, status.toLowerCase());
    }

    @Test
    void projectsRunsAndAggregatesMetrics() throws Exception {
        try (DbJobRunStore db = DbJobRunStore.open("jdbc:duckdb:")) {
            db.record(run("r1", "j1", "SUCCESS", 10, "2026-06-17 10:00:00"));
            db.record(run("r2", "j1", "SUCCESS", 20, "2026-06-17 10:01:00"));
            db.record(run("r3", "j1", "SUCCESS", 30, "2026-06-17 10:02:00"));
            db.record(run("r4", "j1", "FAILED",  40, "2026-06-17 10:03:00"));
            db.record(run("r5", "j2", "SUCCESS",  5, "2026-06-17 10:04:00"));   // a different job

            Map<String, Object> all = db.metrics(null);
            assertEquals(5L, ((Number) all.get("total")).longValue());
            assertEquals(4L, ((Number) all.get("success")).longValue());
            assertEquals(1L, ((Number) all.get("failed")).longValue());

            Map<String, Object> j1 = db.metrics("j1");
            assertEquals(4L, ((Number) j1.get("total")).longValue());
            assertEquals(3L, ((Number) j1.get("success")).longValue());
            assertEquals(1L, ((Number) j1.get("failed")).longValue());
            assertEquals(0.75, ((Number) j1.get("successRate")).doubleValue(), 1e-9);
            assertTrue(((Number) j1.get("p95Ms")).doubleValue() >= ((Number) j1.get("p50Ms")).doubleValue());
            assertTrue(((Number) j1.get("meanMs")).doubleValue() > 0);
        }
    }

    @Test
    void recentRunsAreNewestFirstAndFilterByJob() throws Exception {
        try (DbJobRunStore db = DbJobRunStore.open("jdbc:duckdb:")) {
            db.record(run("r1", "j1", "SUCCESS", 10, "2026-06-17 10:00:00"));
            db.record(run("r2", "j1", "FAILED",  20, "2026-06-17 10:05:00"));
            db.record(run("r3", "j2", "SUCCESS", 30, "2026-06-17 10:10:00"));

            List<Map<String, Object>> j1 = db.recentRuns(10, "j1");
            assertEquals(2, j1.size(), "only j1 runs");
            assertEquals("r2", j1.get(0).get("runId"), "newest first");
            assertEquals("r1", j1.get(1).get("runId"));

            assertEquals(3, db.recentRuns(10, null).size(), "no filter = all jobs");
            assertEquals(1, db.recentRuns(1, null).size(), "limit honoured");
        }
    }

    @Test
    void keysetPagingWalksNewestFirstWithoutGaps() throws Exception {
        try (DbJobRunStore db = DbJobRunStore.open("jdbc:duckdb:")) {
            db.record(run("r1", "j1", "SUCCESS", 10, "2026-06-17 10:00:00"));
            db.record(run("r2", "j1", "SUCCESS", 10, "2026-06-17 10:01:00"));
            db.record(run("r3", "j1", "SUCCESS", 10, "2026-06-17 10:02:00"));
            db.record(run("r4", "j1", "SUCCESS", 10, "2026-06-17 10:03:00"));
            db.record(run("r5", "j1", "SUCCESS", 10, "2026-06-17 10:04:00"));

            List<Map<String, Object>> p1 = db.recentRuns(2, null, null, null);
            assertEquals(List.of("r5", "r4"), ids(p1), "first page, newest first (null marker = from the top)");

            Map<String, Object> after1 = p1.get(1);
            List<Map<String, Object>> p2 = db.recentRuns(2, null,
                    (String) after1.get("startTime"), (String) after1.get("runId"));
            assertEquals(List.of("r3", "r2"), ids(p2), "resumes strictly after r4 — no overlap");

            Map<String, Object> after2 = p2.get(1);
            List<Map<String, Object>> p3 = db.recentRuns(2, null,
                    (String) after2.get("startTime"), (String) after2.get("runId"));
            assertEquals(List.of("r1"), ids(p3), "final partial page");
        }
    }

    @Test
    void countRunsTotalsAndFiltersByJob() throws Exception {
        try (DbJobRunStore db = DbJobRunStore.open("jdbc:duckdb:")) {
            db.record(run("r1", "j1", "SUCCESS", 10, "2026-06-17 10:00:00"));
            db.record(run("r2", "j1", "FAILED", 10, "2026-06-17 10:01:00"));
            db.record(run("r3", "j2", "SUCCESS", 10, "2026-06-17 10:02:00"));
            assertEquals(3, db.countRuns(null));
            assertEquals(2, db.countRuns("j1"));
            assertEquals(0, db.countRuns("nope"));
        }
    }

    private static List<String> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (String) r.get("runId")).toList();
    }

    @Test
    void failureTrendGroupsByDay() throws Exception {
        try (DbJobRunStore db = DbJobRunStore.open("jdbc:duckdb:")) {
            db.record(run("a1", "j1", "SUCCESS", 10, "2026-06-16 09:00:00"));
            db.record(run("a2", "j1", "FAILED",  10, "2026-06-16 09:01:00"));
            db.record(run("b1", "j1", "SUCCESS", 10, "2026-06-17 09:00:00"));

            List<Map<String, Object>> trend = db.failureTrend(30);
            assertEquals(2, trend.size(), "two distinct days");
            // newest day first
            assertEquals("2026-06-17", trend.get(0).get("day"));
            assertEquals(1L, ((Number) trend.get(0).get("total")).longValue());
            assertEquals(0L, ((Number) trend.get(0).get("failed")).longValue());
            assertEquals("2026-06-16", trend.get(1).get("day"));
            assertEquals(2L, ((Number) trend.get(1).get("total")).longValue());
            assertEquals(1L, ((Number) trend.get(1).get("failed")).longValue());
        }
    }

    @Test
    void emptyStoreYieldsZeroedMetrics() throws Exception {
        try (DbJobRunStore db = DbJobRunStore.open("jdbc:duckdb:")) {
            Map<String, Object> m = db.metrics(null);
            assertEquals(0L, ((Number) m.get("total")).longValue());
            assertEquals(0.0, ((Number) m.get("successRate")).doubleValue(), 1e-9);
            assertTrue(db.recentRuns(10, null).isEmpty());
            assertTrue(db.failureTrend(30).isEmpty());
        }
    }
}
