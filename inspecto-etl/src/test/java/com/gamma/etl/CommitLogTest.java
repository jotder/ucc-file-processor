package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CommitLog} — the durable append-only batch ledger (D2).
 */
class CommitLogTest {

    @Test
    void writesHeaderOnCreation(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("p_commits.log");
        new CommitLog(f.toString());
        assertTrue(Files.exists(f));
        assertEquals("committed_at,batch_id,pipeline,status,member_count,output_count,output_rows,output_bytes",
                Files.readAllLines(f).get(0));
    }

    @Test
    void appendsRecordsAndReadsBackSuccessIds(@TempDir Path dir) throws Exception {
        CommitLog log = new CommitLog(dir.resolve("p_commits.log").toString());
        log.record("2020-04-03 10:00:00", "b1", "p", "SUCCESS", 2, 3, 100, 4096);
        log.record("2020-04-03 10:01:00", "b2", "p", "EMPTY",   0, 0, 0, 0);
        log.record("2020-04-03 10:02:00", "b3", "p", "SUCCESS", 1, 1, 50, 1024);

        Set<String> committed = log.committedBatchIds();
        assertEquals(Set.of("b1", "b3"), committed, "only SUCCESS batches are 'committed'");

        List<String> lines = Files.readAllLines(log.path());
        assertEquals(4, lines.size(), "header + 3 records");
        assertTrue(lines.get(1).startsWith("2020-04-03 10:00:00,b1,p,SUCCESS,2,3,100,4096"));
    }

    @Test
    void reopeningExistingLogPreservesContent(@TempDir Path dir) throws Exception {
        String path = dir.resolve("p_commits.log").toString();
        new CommitLog(path).record("t", "b1", "p", "SUCCESS", 1, 1, 10, 100);
        // Reopen — must NOT re-write the header or truncate.
        CommitLog reopened = new CommitLog(path);
        reopened.record("t", "b2", "p", "SUCCESS", 1, 1, 20, 200);

        assertEquals(Set.of("b1", "b2"), reopened.committedBatchIds());
        assertEquals(3, Files.readAllLines(reopened.path()).size(), "header + 2 records, no duplicate header");
    }

    @Test
    void concurrentRecordsAreAllPersisted(@TempDir Path dir) throws Exception {
        CommitLog log = new CommitLog(dir.resolve("p_commits.log").toString());
        int n = 50;
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int id = i;
                fs.add(ex.submit(() -> {
                    log.record("t", "b" + id, "p", "SUCCESS", 1, 1, id, id);
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        assertEquals(n, log.committedBatchIds().size(), "every concurrent record must survive");
        assertEquals(n + 1, Files.readAllLines(log.path()).size(), "header + n records, no lost/torn writes");
    }

    @Test
    void emptyLogReadsAsEmptySet(@TempDir Path dir) {
        CommitLog log = new CommitLog(dir.resolve("fresh_commits.log").toString());
        assertTrue(log.committedBatchIds().isEmpty(), "only the header exists → no committed ids");
    }
}
