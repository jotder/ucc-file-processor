package com.gamma.service;

import com.gamma.etl.CommitLog;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.util.Csv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * File-backed {@link StatusStore} — reads the existing on-disk audit artifacts:
 * the durable commit log ({@code _commits.log}) for "did this batch finish", and
 * the run-timestamped audit CSVs ({@code _batches_*.csv}, {@code _status_*.csv},
 * {@code _lineage_*.csv}) in the status directory for the richer queries. Quarantine
 * is read by walking the quarantine tree. The default implementation for the 2.x
 * service line; M5 adds a database-backed alternative behind the same interface.
 */
public final class FileStatusStore implements StatusStore {

    private static final Logger log = LoggerFactory.getLogger(FileStatusStore.class);

    @Override
    public Set<String> committedBatches(PipelineConfig cfg) {
        String path = cfg.dirs().commitLogPath();
        if (path == null || path.isBlank()) return Set.of();
        return new CommitLog(path).committedBatchIds();
    }

    @Override
    public List<Map<String, String>> batches(PipelineConfig cfg) {
        return readRuns(cfg, "_batches_");
    }

    @Override
    public List<Map<String, String>> files(PipelineConfig cfg) {
        return readRuns(cfg, "_status_");
    }

    @Override
    public List<Map<String, String>> lineage(PipelineConfig cfg, String batchId) {
        List<Map<String, String>> rows = readRuns(cfg, "_lineage_");
        if (batchId == null || batchId.isBlank()) return rows;
        rows.removeIf(r -> !batchId.equals(r.get("batch_id")));
        return rows;
    }

    @Override
    public List<Map<String, String>> quarantine(PipelineConfig cfg) {
        List<Map<String, String>> out = new ArrayList<>();
        String qd = cfg.dirs().quarantine();
        if (qd == null || qd.isBlank()) return out;
        Path root = Path.of(qd);
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> w = Files.walk(root)) {
            w.filter(Files::isRegularFile).sorted().forEach(p -> {
                Path rel = root.relativize(p);
                // layout: <quarantine>/<relParent>/<reason>/<filename>
                String reason = rel.getNameCount() >= 2
                        ? rel.getName(rel.getNameCount() - 2).toString() : "unknown";
                Map<String, String> m = new LinkedHashMap<>();
                m.put("file", p.getFileName().toString());
                m.put("reason", reason);
                m.put("path", rel.toString().replace('\\', '/'));
                try { m.put("size_bytes", Long.toString(Files.size(p))); } catch (IOException ignore) {}
                out.add(m);
            });
        } catch (IOException e) {
            log.warn("Could not read quarantine dir {}: {}", root, e.getMessage());
        }
        return out;
    }

    // ── internals ────────────────────────────────────────────────────────────────

    /**
     * Read every run-timestamped audit CSV matching {@code <pipeline><infix>*.csv} in
     * the status directory, oldest run first, into ordered header→value maps.
     */
    private List<Map<String, String>> readRuns(PipelineConfig cfg, String infix) {
        List<Map<String, String>> rows = new ArrayList<>();
        String statusFile = cfg.dirs().statusFilePath();
        if (statusFile == null || statusFile.isBlank()) return rows;
        Path dir = Path.of(statusFile).toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) return rows;
        String glob = cfg.identity().pipelineName() + infix + "*.csv";
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            ds.forEach(files::add);
        } catch (IOException e) {
            log.warn("Could not list audit files {} in {}: {}", glob, dir, e.getMessage());
            return rows;
        }
        files.sort(null);   // run timestamp is in the name → chronological
        for (Path f : files) readCsv(f, rows);
        return rows;
    }

    /** Append each data row of a header-bearing CSV to {@code out} as a header→value map. */
    private void readCsv(Path file, List<Map<String, String>> out) {
        try {
            Csv.readInto(file, out);
        } catch (Exception e) {
            log.warn("Could not read audit CSV {}: {}", file, e.getMessage());
        }
    }
}
