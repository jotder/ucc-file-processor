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
 * Append-only JSONL persistence for {@link RunArtifact}s (R7, {@code docs/job-framework-design.md} §10):
 * one file per run at {@code <auditDir>/artifacts/<runId>.jsonl}, read back for the Control API and the
 * {@code $upstream(...)} Parameter Context. Best effort — a recording failure never fails the Run. Mirrors
 * {@link RunLogStore}, so artifacts persist regardless of whether the optional DuckDB reporting backend
 * ({@code -Djobs.backend}) is configured.
 */
final class RunArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(RunArtifactStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;

    RunArtifactStore(String auditDir) {
        this.dir = Path.of(auditDir == null || auditDir.isBlank() ? "." : auditDir).resolve("artifacts");
    }

    /** Append one artifact to the run's JSONL file; a failure is logged, never thrown into the Run. */
    void append(RunArtifact artifact) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(safe(artifact.runId()) + ".jsonl"),
                    JSON.writeValueAsString(artifact) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("run-artifact append failed for {}: {}", artifact.runId(), e.getMessage());
        }
    }

    /** All artifacts for one run in write order; empty if the run recorded none. */
    List<RunArtifact> read(String runId) {
        Path f = dir.resolve(safe(runId) + ".jsonl");
        if (!Files.isRegularFile(f)) return List.of();
        List<RunArtifact> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(f)) {
                if (!line.isBlank()) out.add(JSON.readValue(line, RunArtifact.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("run-artifact read failed for " + runId, e);
        }
        return out;
    }

    private static String safe(String runId) {
        return runId == null ? "_" : runId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
