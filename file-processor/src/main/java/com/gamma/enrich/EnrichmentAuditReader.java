package com.gamma.enrich;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read side of the Stage-2 enrichment audit — the counterpart to
 * {@link EnrichmentAuditWriter}. It reads back the append-only ledgers a job writes
 * ({@code <job>_enrich_runs.csv}, {@code <job>_enrich_lineage.csv}) so the run-level
 * audit/lineage the orchestrator already persists can be surfaced (over the Control
 * API, v2.9.0) without coupling readers to the CSV layout.
 *
 * <p>Rows come back as ordered {@code header→value} maps (so they serialise straight
 * to JSON, exactly like {@link com.gamma.service.StatusStore} rows) in <b>append
 * order</b> — which is chronological, since each ledger is a single growing file the
 * writer only ever appends to. Missing ledgers (a job that has not run yet) read as an
 * empty list rather than an error, mirroring the file-backed status store.
 */
public final class EnrichmentAuditReader {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentAuditReader.class);

    private final Path runsPath;
    private final Path lineagePath;

    /** Open the ledgers for {@code job} under {@code auditDir} (matching the writer's names). */
    public EnrichmentAuditReader(String auditDir, String job) {
        String base = job.toLowerCase().replace(' ', '_');
        Path dir = Paths.get(auditDir);
        this.runsPath    = dir.resolve(base + "_enrich_runs.csv");
        this.lineagePath = dir.resolve(base + "_enrich_lineage.csv");
    }

    /** Reader for a job's audit using the same {@code _audit} convention the writer uses. */
    public static EnrichmentAuditReader forConfig(EnrichmentConfig cfg) {
        return new EnrichmentAuditReader(EnrichmentAuditWriter.auditDir(cfg), cfg.name());
    }

    /** Every run-summary row (SUCCESS and FAILED), oldest first. Empty if the job never ran. */
    public List<Map<String, String>> runs() {
        return read(runsPath);
    }

    /**
     * Lineage rows (one per written output partition file), oldest first. When
     * {@code runId} is non-blank only that run's rows are returned — the per-run drill-down
     * the Control API exposes via {@code ?runId=}.
     */
    public List<Map<String, String>> lineage(String runId) {
        List<Map<String, String>> rows = read(lineagePath);
        if (runId == null || runId.isBlank()) return rows;
        rows.removeIf(r -> !runId.equals(r.get("run_id")));
        return rows;
    }

    /** Parse a header-bearing CSV ledger into ordered header→value maps. */
    private static List<Map<String, String>> read(Path file) {
        List<Map<String, String>> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVReader csv = new CSVReader(r)) {
            String[] header = csv.readNext();
            if (header == null) return out;
            String[] row;
            while ((row = csv.readNext()) != null) {
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 0; i < header.length; i++)
                    m.put(header[i], i < row.length ? row[i] : "");
                out.add(m);
            }
        } catch (IOException | CsvValidationException e) {
            log.warn("Could not read enrichment audit CSV {}: {}", file, e.getMessage());
        }
        return out;
    }
}
