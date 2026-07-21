package com.gamma.inspector;

import com.gamma.etl.CommitLog;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: a real {@link CollectorProcessor} run records a SUCCESS line in the {@link CommitLog}.
 * Relocated out of {@code etl.CommitLogTest} when {@code etl} was extracted into its own module
 * (WS-D increment 2): this is an etl+inspector integration test, not an etl-local unit test.
 */
class CommitLogIntegrationTest {

    @Test
    void realRunRecordsCommit(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).load();
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-04-03\n2,20,2020-04-03\n");

        CollectorProcessor.run(cfg);

        Path commitLog = Path.of(cfg.dirs().commitLogPath());
        assertTrue(Files.exists(commitLog), "commit log should be created by a run");
        String content = Files.readString(commitLog);
        assertTrue(content.contains(",SUCCESS,"), "a committed batch should be recorded:\n" + content);
    }
}
