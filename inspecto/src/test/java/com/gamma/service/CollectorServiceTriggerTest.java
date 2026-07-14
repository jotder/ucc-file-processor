package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.inspector.MultiCollectorProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T13 / §3.8 — entry-node triggers driving the live loop. A pipeline with no {@code trigger:} rides the
 * global poll cycle exactly as before ({@code DEFAULT_POLL}); a {@code schedule:{every}}/{@code cron}
 * trigger gates the loop by its own cadence; {@code manual}/{@code event} flows are driven off the loop
 * (the trigger endpoint / an upstream batch-commit), never by the poll cycle.
 */
class CollectorServiceTriggerTest {

    private static final String CSV = "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-02-02\n";

    /** Write a single-schema CSV pipeline toon under {@code root} with the given name + optional trigger block. */
    private static Path pipeline(Path root, String name, String triggerBlock) throws Exception {
        Files.createDirectories(root);
        Path schema = root.resolve("schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        Path inbox = root.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"), CSV);
        String trig = (triggerBlock == null) ? "" : triggerBlock;
        String toon = """
                name: %1$s
                active: true
                %2$sdirs:
                  poll: %3$s/inbox
                  database: %3$s/db
                  backup: %3$s/backup
                  temp: %3$s/temp
                  quarantine: %3$s/quarantine
                  markers: %3$s/markers
                  status_dir: %3$s/status
                  log_dir: %3$s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  duplicate_check:
                    enabled: true
                    marker_extension: .processed
                  schema_file: "%4$s"
                  csv_settings:
                    delimiter: ","
                    has_header: true
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(name, trig, root.toString().replace('\\', '/'),
                schema.toString().replace('\\', '/'));
        Path p = root.resolve(name.toLowerCase() + "_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    private static long outputCount(Path root) throws Exception {
        Path db = root.resolve("db");
        if (!Files.exists(db)) return 0;
        try (Stream<Path> s = Files.walk(db)) {
            return s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".csv")).count();
        }
    }

    private static boolean waitForOutput(Path root, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (outputCount(root) >= 1) return true;
            Thread.sleep(50);
        }
        return outputCount(root) >= 1;
    }

    @Test
    void noTriggerRidesEveryPollCycle(@TempDir Path dir) throws Exception {
        // DEFAULT_POLL: a pipeline with no trigger runs on every cycle, exactly as before T13.
        Path a = pipeline(dir.resolve("a"), "PLAIN", null);
        try (CollectorService svc = new CollectorService(List.of(a), 3600, 1)) {
            assertEquals(1, svc.runAllOnce().total(), "untriggered pipeline runs on the first tick");
            assertEquals(1, svc.runAllOnce().total(), "...and on the very next tick too (no cadence gate)");
        }
    }

    @Test
    void intervalTriggerGatesTheLoopByItsOwnCadence(@TempDir Path dir) throws Exception {
        Path a = pipeline(dir.resolve("a"), "EVERY_HOUR", "trigger:\n  type: schedule\n  every: 3600s\n");
        try (CollectorService svc = new CollectorService(List.of(a), 1, 1)) {
            assertEquals(1, svc.runAllOnce().total(), "first tick: never run, so it is due");
            assertEquals(0, svc.runAllOnce().total(),
                    "immediate second tick: 3600s has not elapsed, so the interval gate skips it");
        }
    }

    @Test
    void manualTriggerIsExcludedFromTheLoopButRunsOnDemand(@TempDir Path dir) throws Exception {
        Path a = pipeline(dir.resolve("a"), "ON_DEMAND", "trigger:\n  type: manual\n");
        try (CollectorService svc = new CollectorService(List.of(a), 3600, 1)) {
            assertEquals(0, svc.runAllOnce().total(), "a manual pipeline is never run by the poll cycle");
            assertEquals(0, outputCount(dir.resolve("a")), "...so it produced no output from the loop");

            MultiCollectorProcessor.RunResult r = svc.runPipeline("on_demand").orElseThrow();
            assertEquals(1, r.total(), "runPipeline drives it on demand");
            assertTrue(outputCount(dir.resolve("a")) >= 1, "the on-demand run produced output");
        }
    }

    @Test
    void cronTriggerIsNotDueImmediatelyAfterStart(@TempDir Path dir) throws Exception {
        // A far-future cron (Jan 1 00:00) is never due within the test window; the plain pipeline still runs.
        Path cron  = pipeline(dir.resolve("c"), "YEARLY", "trigger:\n  type: schedule\n  cron: \"0 0 1 1 *\"\n");
        Path plain = pipeline(dir.resolve("p"), "PLAIN", null);
        try (CollectorService svc = new CollectorService(List.of(cron, plain), 3600, 2)) {
            assertEquals(1, svc.runAllOnce().total(),
                    "only the untriggered pipeline runs; the cron flow is not yet due");
            assertEquals(0, outputCount(dir.resolve("c")), "the cron pipeline did not run");
            assertTrue(outputCount(dir.resolve("p")) >= 1, "the plain pipeline did run");
        }
    }

    @Test
    void eventTriggerFiresWhenItsUpstreamCommits(@TempDir Path dir) throws Exception {
        // upstream is manual (so only our explicit run drives it); downstream listens for upstream's commit.
        Path up   = pipeline(dir.resolve("up"),   "UP_STREAM", "trigger:\n  type: manual\n");
        Path down = pipeline(dir.resolve("down"), "DOWN_STREAM",
                "trigger:\n  type: event\n  on: commit\n  from: up_stream\n");
        try (CollectorService svc = new CollectorService(List.of(up, down), 3600, 2)) {
            svc.start();   // wires the upstream-commit bus subscriber (poll interval 3600s won't interfere)

            assertEquals(0, svc.runAllOnce().total(), "neither flow is loop-driven (manual + event)");

            // drive the upstream: its commit event signals the downstream's coalescer (off the bus thread)
            svc.runPipeline("up_stream").orElseThrow();
            assertTrue(waitForOutput(dir.resolve("down"), 10_000),
                    "the event-triggered downstream ran when its upstream committed");
        }
    }
}
