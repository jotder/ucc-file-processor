package com.gamma.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;

/**
 * Moves rejected input files into the quarantine directory tree.
 *
 * <p>The quarantine mirrors the poll directory structure:
 * a file at {@code poll/providerA/20240101/feed.csv.gz} is moved to
 * {@code quarantine/providerA/20240101/<subDir>/feed.csv.gz}.
 * Files dropped directly in the poll root land at
 * {@code quarantine/<subDir>/feed.csv.gz}.
 *
 * <p>Optionally, the companion error CSV (produced by {@link CsvIngester} and
 * placed in {@code dirs.errors}) is relocated alongside the bad file so the
 * rejection evidence stays co-located.
 *
 * <p>Extracted from {@link com.gamma.inspector.SourceProcessor}.
 */
public final class QuarantineManager {

    private static final Logger log = LoggerFactory.getLogger(QuarantineManager.class);

    private QuarantineManager() {}

    // ── parse-stage reasons (legacy) ──────────────────────────────────────────
    /** A row failed field/type validation against the schema. */ public static final String REASON_FIELD_MISMATCH = "field_mismatch";
    /** The file could not be read/parsed at all. */              public static final String REASON_UNREADABLE     = "unreadable";

    // ── acquisition-stage reasons (Data Acquisition roadmap Phase F dead-letter) ──
    /** A fetched file failed its post-download integrity check (size/checksum mismatch). */
    public static final String REASON_CORRUPT_DOWNLOAD = "corrupt_download";

    /**
     * Move {@code inputFile} into the quarantine tree.
     *
     * @param inputFile       the file to quarantine
     * @param subDir          reason sub-directory: {@code "field_mismatch"} or {@code "unreadable"}
     * @param includeErrorCsv when {@code true}, also move the companion error CSV
     * @param cfg             pipeline configuration
     * @throws IOException if the move fails or the file is outside the poll root
     */
    public static void quarantine(File inputFile, String subDir,
                                  boolean includeErrorCsv, PipelineConfig cfg)
            throws IOException {
        Path pollPath  = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        Path fileParent= inputFile.toPath().toAbsolutePath().normalize().getParent();
        Path relParent = pollPath.relativize(fileParent);

        // Guard against symlinks or misconfiguration that places the file outside poll
        if (relParent.startsWith(".."))
            throw new IOException(
                    "Input file is not under poll root — cannot quarantine safely: " + inputFile);

        // <quarantine_dir>/<relative_parent>/<reason>/filename
        Path qDir = Paths.get(cfg.dirs().quarantine()).toAbsolutePath()
                         .resolve(relParent).resolve(subDir);
        Files.createDirectories(qDir);

        Path dst = qDir.resolve(inputFile.getName());
        Files.move(inputFile.toPath(), dst, StandardCopyOption.REPLACE_EXISTING);
        log.info("Quarantined [{}]: {} → {}", subDir, inputFile.getName(), dst);

        if (includeErrorCsv) {
            String baseName = CsvIngester.stripExtensions(inputFile.getName());
            Path errorCsv = Paths.get(cfg.dirs().errors()).toAbsolutePath()
                                 .resolve(baseName + "_errors.csv");
            if (Files.exists(errorCsv))
                Files.move(errorCsv, qDir.resolve(errorCsv.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
