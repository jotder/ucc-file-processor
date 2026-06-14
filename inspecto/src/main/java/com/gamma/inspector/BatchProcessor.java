package com.gamma.inspector;

import com.gamma.acquire.AcquisitionLedger;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.LedgerEntry;
import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Processes one {@link Batch} in a single pass.
 *
 * <p>This class is now a thin coordinator: it selects a {@link BatchIngestStrategy}
 * (CSV vs. plugin) based on config, runs it to produce an {@link IngestOutcome}, then
 * drives the shared, path-agnostic tail — {@link #commit} and {@link #writeAudit}.
 *
 * <h3>CSV path (default)</h3>
 * {@link CsvBatchStrategy} ingests each member into a per-file temp table, inserts accepted
 * rows into a shared {@code raw_input} tagged with {@code __src_id}, transforms once, writes
 * consolidated partition output, and computes the lineage matrix. Rejected members are
 * quarantined; their rows never reach {@code raw_input}.
 *
 * <h3>Plugin-ingester path</h3>
 * When {@link PipelineConfig.Schemas#ingesterClass()} is set, {@link StreamingPluginBatchStrategy}
 * runs the configured {@link StreamingFileIngester} and picks, per batch by file size, between
 * union mode (many small files → one transform/write) and generation mode (huge single files →
 * bounded scratch). All segment outputs aggregate into one batch audit entry.
 */
public final class BatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessor.class);

    private BatchProcessor() {}

    // ── entry point ───────────────────────────────────────────────────────────

    public static void process(Batch batch, PipelineConfig cfg, BatchAuditWriter audit) {
        BatchIngestStrategy strategy = (cfg.schemas().ingesterClass() == null)
                ? new CsvBatchStrategy()
                : new StreamingPluginBatchStrategy();

        IngestOutcome outcome;
        try {
            outcome = strategy.ingest(batch, cfg);
        } finally {
            // The strategies report per-member progress; the snapshot must never outlive the batch.
            IngestProgress.clear(cfg.identity().pipelineName());
        }

        String status = outcome.status();
        String error  = outcome.error();

        if ("SUCCESS".equals(status)) {
            try {
                commit(batch, cfg, outcome.survivors(), outcome.outputs(), outcome.lineage());
            } catch (Exception e) {
                // Output was written, but a side effect (backup/manifest/markers) failed. Demote
                // to FAILED so the batch stays visible to audit/lineage/recovery instead of
                // vanishing — a silently un-audited batch is the worst outcome for reprocessing.
                status = "FAILED";
                error  = "commit failed: " + BatchIngestStrategy.msg(e);
                log.error("Batch {} failed during commit", batch.batchId(), e);
            }
        }
        try {   // audit is always written — even when commit failed above
            writeAudit(batch, cfg, audit, outcome, status, error);
        } catch (Exception e) {
            log.error("Batch {} failed during audit", batch.batchId(), e);
        }
    }

    // ── commit: register, manifest, markers, backup ────────────────────────────

    private static void commit(Batch batch, PipelineConfig cfg, List<Batch.Member> survivors,
                               List<PartitionOutput> outputs, List<LineageRow> lineage)
            throws IOException {

        // ── ordering rationale ────────────────────────────────────────────────
        // Markers signal "already processed; skip on next poll." If a crash leaves
        // markers without a corresponding backup, the input file is stranded in the
        // inbox forever (skipped by poll, never moved). So markers go LAST, after
        // every other side effect is durable. Sequence:
        //   1. DuckLake register (optional, non-fatal — log & continue)
        //   2. Manifest write   (required: reprocess reads from here)
        //   3. Backup originals (moves files out of the inbox)
        //   4. Marker files     (last — only created when 1-3 all succeeded)
        // A crash at any point before step 4 is idempotent on rerun: outputs use
        // OVERWRITE_OR_IGNORE, the manifest is rewritten, and absent markers mean
        // the (still-present-in-inbox) files are picked up again.

        DuckLakeRegistrar.register(outputs.stream().map(PartitionOutput::outputFile).toList(),
                batch.table(), cfg);

        Path poll   = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        Path backup = (cfg.dirs().backup() != null && !cfg.dirs().backup().isBlank())
                ? Paths.get(cfg.dirs().backup()).toAbsolutePath() : null;

        // Content-based dedup (Phase C): capture each survivor's fingerprint now, while the file is still at
        // its inbox path (the backup step below moves it), and record it to the ledger LAST — alongside the
        // markers, after every other side-effect is durable — so a crash mid-commit doesn't leave a stranded
        // "already processed" fingerprint. PATH mode records nothing (it uses marker sentinels).
        boolean ledgerRecord = cfg.processing().duplicateCheckEnabled() && cfg.source().duplicate().contentBased();
        boolean checksumMode = ledgerRecord && "checksum".equals(cfg.source().duplicate().mode());
        String dupAlgorithm = cfg.source().duplicate().algorithm();
        String sourceId = cfg.source().id();
        List<LedgerEntry> ledgerEntries = ledgerRecord ? new ArrayList<>() : null;

        List<BatchManifest.MemberEntry> memberEntries = new ArrayList<>();
        List<String> markerPaths = new ArrayList<>();
        for (Batch.Member m : survivors) {
            Path filePath = m.file().toPath().toAbsolutePath().normalize();
            String rel    = poll.relativize(filePath).toString().replace('\\', '/');
            String backupPath = backup != null
                    ? backup.resolve(poll.relativize(filePath)).toString() : "";
            if (cfg.dirs().markers() != null)
                markerPaths.add(MarkerManager.getMarkerPath(m.file(), cfg).toString());
            if (ledgerRecord) {
                try {
                    // CHECKSUM mode: reuse the hash computed during the run-path dedup (stashed by
                    // SourceProcessor), or compute it now from the still-in-inbox file if absent.
                    String checksum = null;
                    if (checksumMode) {
                        checksum = AcquisitionLedgers.takeChecksum(filePath);
                        if (checksum == null) checksum = com.gamma.acquire.Checksums.of(filePath, dupAlgorithm);
                    }
                    ledgerEntries.add(new LedgerEntry(sourceId, rel, m.file().getName(),
                            Files.size(filePath), checksum, Files.getLastModifiedTime(filePath).toMillis(),
                            System.currentTimeMillis(), LedgerEntry.PROCESSED));
                } catch (IOException ignore) { /* vanished pre-backup — skip recording this member */ }
            }
            memberEntries.add(new BatchManifest.MemberEntry(
                    m.file().getName(), m.srcId(), rel, backupPath, "SUCCESS"));
        }

        if (cfg.dirs().manifestsDir() != null) {
            BatchManifest manifest = new BatchManifest();
            manifest.batchId     = batch.batchId();
            manifest.pipeline    = cfg.identity().pipelineName();
            manifest.schemaName  = batch.schemaName();
            manifest.outputTable = batch.table();
            manifest.createdAt   = LocalDateTime.now().format(DuckDbUtil.DT_FMT);
            manifest.members     = memberEntries;
            manifest.outputs     = outputs.stream()
                    .map(o -> new BatchManifest.OutputEntry(o.partition(), o.outputFile())).toList();
            manifest.markers     = markerPaths;
            ManifestStore.write(cfg.dirs().manifestsDir(), manifest);
        }

        // Backup BEFORE markers — see ordering rationale at top of method.
        if (backup != null)
            for (Batch.Member m : survivors) backupFile(m.file(), cfg);

        // Markers LAST — created only after every other side-effect is durable.
        if (cfg.dirs().markers() != null)
            for (Batch.Member m : survivors) MarkerManager.createMarkerFile(m.file(), cfg);

        // Fingerprint ledger LAST too (content-based dedup; same stranding-safety reason as markers).
        if (ledgerRecord) {
            AcquisitionLedger ledger = AcquisitionLedgers.shared();
            for (LedgerEntry e : ledgerEntries) ledger.record(e);
        }
    }

    private static void backupFile(File inputFile, PipelineConfig cfg) throws IOException {
        Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        Path file = inputFile.toPath().toAbsolutePath().normalize();
        Path dst  = Paths.get(cfg.dirs().backup()).resolve(poll.relativize(file));
        Files.createDirectories(dst.getParent());
        Files.move(file, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    // ── audit assembly ──────────────────────────────────────────────────────────

    /**
     * Assemble and flush the batch + file + lineage audit rows. Path-agnostic: the
     * CSV/plugin difference is carried by {@link IngestOutcome#schemaLabel()}. The
     * {@code status}/{@code error} args are the <em>final</em> values (a post-write
     * commit failure demotes a SUCCESS outcome to FAILED).
     */
    private static void writeAudit(Batch batch, PipelineConfig cfg, BatchAuditWriter audit,
                                   IngestOutcome outcome, String status, String error) {
        if (audit == null) return;
        LocalDateTime end = LocalDateTime.now();
        List<LineageRow>      lineage = outcome.lineage();
        List<PartitionOutput> outputs = outcome.outputs();

        Map<Integer, LinkedHashSet<String>> outBySrc = new HashMap<>();
        for (LineageRow r : lineage)
            outBySrc.computeIfAbsent(r.srcId(), k -> new LinkedHashSet<>()).add(r.outputFile());

        List<BatchAuditWriter.FileRow> fileRows = new ArrayList<>();
        int rejected = 0;
        for (MemberAudit ma : outcome.memberAudits()) {
            if (!ma.status().equals("SUCCESS")) rejected++;
            List<String> paths = new ArrayList<>(
                    outBySrc.getOrDefault(ma.srcId(), new LinkedHashSet<>()));
            fileRows.add(new BatchAuditWriter.FileRow(
                    ma.start().format(DuckDbUtil.DT_FMT), end.format(DuckDbUtil.DT_FMT),
                    ma.filename(), ma.status(), ma.parsedRows(), ma.errorRows(),
                    paths, Collections.nCopies(paths.size(), 0L),
                    Duration.between(ma.start(), end).toMillis(), ma.error(), batch.batchId()));
        }

        long totalOutputRows  = lineage.stream().mapToLong(LineageRow::rowCount).sum();
        long totalOutputBytes = outputs.stream().mapToLong(PartitionOutput::bytes).sum();

        BatchAuditWriter.BatchRow batchRow = new BatchAuditWriter.BatchRow(
                batch.batchId(), cfg.identity().pipelineName(), outcome.schemaLabel(), batch.table(),
                outcome.batchStart().format(DuckDbUtil.DT_FMT), end.format(DuckDbUtil.DT_FMT), status,
                batch.members().size(), rejected, outcome.totalInputRows(), totalOutputRows,
                outputs.size(), totalOutputBytes,
                Duration.between(outcome.batchStart(), end).toMillis(), error);

        audit.flush(batchRow, fileRows, lineage);
    }
}
