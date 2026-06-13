package com.gamma.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for {@link ParquetEventStore}: append → flush to rolling Hive-partitioned Parquet →
 * query back through DuckDB {@code read_parquet}, including the live-tail merge of the unflushed
 * buffer and that each flush writes a distinct (non-overwriting) file set.
 */
class ParquetEventStoreTest {

    private static Event ev(long ts, EventLevel lvl, String type, String pipe, String msg) {
        return Event.builder(type).ts(ts).level(lvl).source("src").pipeline(pipe).message(msg)
                .attr("k", 1).build();
    }

    @Test
    void roundTripsThroughParquetAndMergesUnflushedBuffer(@TempDir Path dir) {
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            store.append(ev(1_000L, EventLevel.INFO, EventType.JOB_STARTED, "PipeA", "started"));
            store.append(ev(1_001L, EventLevel.ERROR, EventType.BATCH_FAILED, "PipeA", "boom"));
            store.flush();                                              // → Parquet on disk
            store.append(ev(1_002L, EventLevel.INFO, EventType.LOG, "PipeB", "tail"));  // unflushed

            List<Event> all = store.query(EventQuery.recent(100));
            assertEquals(3, all.size(), "2 flushed + 1 buffered");
            assertEquals("tail", all.get(0).message(), "newest-first across buffer + Parquet");

            // attributes survive the columnar round trip
            assertEquals("1", all.get(all.size() - 1).attributes().get("k"));

            // severity filter (partition-pruned) — only the ERROR
            List<Event> errs = store.query(EventQuery.builder().minLevel(EventLevel.ERROR).limit(100).build());
            assertEquals(1, errs.size());
            assertEquals(EventType.BATCH_FAILED, errs.get(0).type());

            // type + pipeline filters
            assertEquals(1, store.query(EventQuery.builder().type("JOB_STARTED").limit(100).build()).size());
            assertEquals(1, store.query(EventQuery.builder().pipeline("pipeb").limit(100).build()).size());

            // text + time window
            assertEquals(1, store.query(EventQuery.builder().textContains("boom").limit(100).build()).size());
            assertEquals(0, store.query(EventQuery.builder().from(5_000L).limit(100).build()).size());

            // live tail from the in-memory ring
            assertEquals("tail", store.recent(1).get(0).message());
        }
    }

    @Test
    void multipleFlushesAccumulateWithoutOverwrite(@TempDir Path dir) {
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            store.append(ev(1, EventLevel.INFO, EventType.LOG, "p", "a")); store.flush();
            store.append(ev(2, EventLevel.INFO, EventType.LOG, "p", "b")); store.flush();
            store.append(ev(3, EventLevel.INFO, EventType.LOG, "p", "c")); store.flush();
            assertEquals(3, store.query(EventQuery.recent(100)).size(),
                    "each flush writes a uniquely-named file rather than overwriting");
        }
    }

    @Test
    void emptyStoreQueriesCleanlyAndFlushIsNoop(@TempDir Path dir) {
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            assertTrue(store.query(EventQuery.recent(100)).isEmpty(), "no Parquet yet → no crash");
            assertTrue(store.recent(10).isEmpty());
            store.flush();   // empty buffer → no-op
            assertTrue(store.query(EventQuery.recent(100)).isEmpty());
        }
    }

    @Test
    void sizeThresholdAutoFlushes(@TempDir Path dir) {
        try (ParquetEventStore store = new ParquetEventStore(dir, 2, 0, 100)) {
            store.append(ev(1, EventLevel.INFO, EventType.LOG, "p", "a"));
            store.append(ev(2, EventLevel.INFO, EventType.LOG, "p", "b"));   // hits threshold → flush
            // a fresh reader-only store over the same dir sees the auto-flushed rows on disk
            try (ParquetEventStore reader = new ParquetEventStore(dir, 1000, 0, 100)) {
                assertEquals(2, reader.query(EventQuery.recent(100)).size(),
                        "threshold flush persisted both events to Parquet");
            }
        }
    }
}
