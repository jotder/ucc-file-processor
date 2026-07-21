package com.gamma.etl;

import com.gamma.event.EventLog;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BatchAuditWriterTest {

    @Test
    void writesHeadersAndRows(@TempDir Path dir) throws Exception {
        String statusCsv  = dir.resolve("p_status_TS.csv").toString();
        String batchesCsv = dir.resolve("p_batches_TS.csv").toString();
        String lineageCsv = dir.resolve("p_lineage_TS.csv").toString();
        BatchAuditWriter w = new BatchAuditWriter(statusCsv, batchesCsv, lineageCsv);

        var fileRows = List.of(
                new BatchAuditWriter.FileRow("2026-05-27 10:30:00", "2026-05-27 10:30:01",
                        "a.csv", "SUCCESS", 2, 0, List.of("/db/B1_out.csv"), List.of(120L), 1000, "", "B1"),
                new BatchAuditWriter.FileRow("2026-05-27 10:30:00", "2026-05-27 10:30:01",
                        "bad.csv", "QUARANTINED_MISMATCH", 0, 3, List.of(), List.of(), 50, "0 valid rows", "B1"));
        var batchRow = new BatchAuditWriter.BatchRow("B1", "mini_etl", "mini", "",
                "2026-05-27 10:30:00", "2026-05-27 10:30:02", "SUCCESS",
                2, 1, 2, 2, 1, 120L, 2000, "");
        var lineage = List.of(new LineageRow("B1", 0, "a.csv", "/db/B1_out.csv", "year=2020/month=04/day=03", 2));

        w.flush(batchRow, fileRows, lineage);

        String status = Files.readString(Path.of(statusCsv));
        assertTrue(status.startsWith("start_time,end_time,filename,status,parsed_rows,error_rows,output_paths,output_sizes_bytes,duration_ms,error,batch_id"));
        assertTrue(status.contains("a.csv"));
        assertTrue(status.contains("QUARANTINED_MISMATCH"));

        String batches = Files.readString(Path.of(batchesCsv));
        assertTrue(batches.contains("batch_id,pipeline,schema_name,output_table"));
        assertTrue(batches.contains("B1"));

        String lin = Files.readString(Path.of(lineageCsv));
        assertTrue(lin.startsWith("batch_id,src_id,input_file,output_file,partition,row_count"));
        assertTrue(lin.contains("year=2020/month=04/day=03"));
    }

    /** v3.7.0: a FAILED batch's emitted event carries error detail (error/offendingFile/errorRows). */
    @Test
    void emittedEventCarriesErrorDetailOnFailure(@TempDir Path dir) {
        BatchAuditWriter w = new BatchAuditWriter(
                dir.resolve("s.csv").toString(), dir.resolve("b.csv").toString(),
                dir.resolve("l.csv").toString());
        AtomicReference<BatchEvent> seen = new AtomicReference<>();
        w.setCommitListener(seen::set);

        var fileRows = List.of(
                new BatchAuditWriter.FileRow("t0", "t1", "good.csv", "SUCCESS",
                        2, 0, List.of("/db/out.csv"), List.of(10L), 5, "", "B9"),
                new BatchAuditWriter.FileRow("t0", "t1", "bad.csv", "QUARANTINED_MISMATCH",
                        0, 3, List.of(), List.of(), 5, "schema selector mismatch", "B9"));
        var batchRow = new BatchAuditWriter.BatchRow("B9", "mini_etl", "mini", "",
                "t0", "t2", "FAILED", 2, 1, 2, 0, 0, 0L, 20, "batch failed: schema selector mismatch");

        w.flush(batchRow, fileRows, List.of());

        BatchEvent ev = seen.get();
        assertNotNull(ev, "a terminal batch emits an event");
        assertEquals("FAILED", ev.status());
        assertEquals("batch failed: schema selector mismatch", ev.error());
        assertEquals("bad.csv", ev.offendingFile(), "first member file with an error");
        assertEquals(3L, ev.errorRows(), "sum of member error rows");
    }

    /** S1 (event-signal-backbone-plan §4.2): a terminal batch also lands a canonical
     *  {@code pipeline.batch.committed|failed} Signal on the ledger — additively, alongside the
     *  existing {@code BatchEventBus}-style {@link BatchEvent} commitListener fan-out (proved by the
     *  same flush call in this test, so neither path regresses the other). */
    @Test
    void flushEmitsACanonicalSignalAlongsideTheExistingBatchEvent(@TempDir Path dir) {
        // Isolate from the shared global EventLog (its ring buffer is flooded by the rest of the
        // reactor's tests) by registering a fresh per-test EventLog under a unique space id.
        String space = "batch-audit-writer-test-" + UUID.randomUUID();
        EventLog log = EventLog.create();
        EventLog.register(space, log);
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
        try {
            BatchAuditWriter w = new BatchAuditWriter(
                    dir.resolve("s2.csv").toString(), dir.resolve("b2.csv").toString(),
                    dir.resolve("l2.csv").toString());
            AtomicReference<BatchEvent> seen = new AtomicReference<>();
            w.setCommitListener(seen::set);

            String batchId = "SIG-" + UUID.randomUUID();
            var fileRows = List.of(new BatchAuditWriter.FileRow("t0", "t1", "good.csv", "SUCCESS",
                    5, 0, List.of("/db/out.csv"), List.of(10L), 5, "", batchId));
            var batchRow = new BatchAuditWriter.BatchRow(batchId, "sig_pipeline", "mini", "",
                    "t0", "t2", "SUCCESS", 1, 0, 5, 5, 1, 10L, 20, "");
            var lineage = List.of(new LineageRow(batchId, 0, "good.csv", "/db/out.csv", "year=2026", 5));

            w.flush(batchRow, fileRows, lineage);

            // existing path: BatchEvent still fires unchanged
            BatchEvent ev = seen.get();
            assertNotNull(ev, "the pre-existing BatchEvent path must still fire");
            assertEquals("SUCCESS", ev.status());
            assertEquals(batchId, ev.batchId());

            // new path: a queryable pipeline.batch.committed Signal on the ledger
            List<Signal> signals = Signals.query(log.store(), "pipeline.batch.*",
                    null, null, null, batchId, 10);
            assertEquals(1, signals.size(), "exactly one signal for this batch's correlationId");
            Signal sig = signals.get(0);
            assertEquals("pipeline.batch.committed", sig.type());
            assertEquals(batchId, sig.correlationId());
            assertEquals("sig_pipeline", sig.subject().id());
            assertEquals(5L, ((Number) sig.payload().get("outputRows")).longValue());
        } finally {
            org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
            EventLog.unregister(space);
        }
    }
}
