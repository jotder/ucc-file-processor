package com.gamma.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only JSONL persistence for {@link RunLog} entries (P0, {@code docs/job-framework-design.md}
 * §9): one file per run at {@code <auditDir>/runlog/<runId>.jsonl}, read back for the Control API.
 * Best effort — a logging failure never fails the Run.
 */
final class RunLogStore {

    private static final Logger log = LoggerFactory.getLogger(RunLogStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;

    RunLogStore(String auditDir) {
        this.dir = Path.of(auditDir == null || auditDir.isBlank() ? "." : auditDir).resolve("runlog");
    }

    /** Append one entry to the run's JSONL file; a failure is logged, never thrown into the Run. */
    void append(RunLogEntry entry) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(safe(entry.runId()) + ".jsonl"),
                    JSON.writeValueAsString(entry) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("run-log append failed for {}: {}", entry.runId(), e.getMessage());
        }
    }

    /** All entries for one run in write order; empty if the run logged nothing. */
    List<RunLogEntry> read(String runId) {
        Path f = dir.resolve(safe(runId) + ".jsonl");
        if (!Files.isRegularFile(f)) return List.of();
        List<RunLogEntry> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(f)) {
                if (!line.isBlank()) out.add(JSON.readValue(line, RunLogEntry.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("run-log read failed for " + runId, e);
        }
        return out;
    }

    private static String safe(String runId) {
        return runId == null ? "_" : runId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
