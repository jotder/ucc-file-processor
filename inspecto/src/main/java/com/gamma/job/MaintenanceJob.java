package com.gamma.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * A {@link JobType#MAINTENANCE} job: a named built-in housekeeping task. Keeps the
 * platform tidy on a schedule without a separate cron/script.
 *
 * <h3>Tasks (PIP-7 maintenance library)</h3>
 * <ul>
 *   <li>{@code cleanup} (default) — delete files older than {@code retention_days} (default 7)
 *       under {@code dir}, optionally filtered by a {@code glob} (default {@code *}). Useful
 *       for pruning old audit CSVs, markers, backups, or quarantine.</li>
 *   <li>{@code ledger_prune} — delete acquisition-ledger fingerprints processed more than
 *       {@code retention_days} (required) ago, optionally scoped to one {@code source}. <b>Deliberate
 *       forgetting</b>: a pruned file still present at the source re-ingests as NEW — retention must
 *       exceed the source's own file lifetime.</li>
 *   <li>{@code db_maintenance} — backend maintenance (CHECKPOINT/VACUUM) on the acquisition-ledger DB
 *       over its own live connection (DuckDB is single-writer; a second connection cannot attach).</li>
 *   <li>{@code compact} — merge the many small per-batch Parquet output files inside each partition
 *       directory under {@code dir} into one file. Params: {@code min_age_days} (default 1 — only files
 *       already this old are touched, the quiet-window safety), {@code min_files} (default 4 — leave
 *       small partitions alone). Readers glob {@code *.parquet}, so compaction is invisible to queries;
 *       the trade-off is that {@code reprocess} of a compacted-away batch is no longer supported (its
 *       manifest's outputFile is gone) — set {@code min_age_days} beyond your reprocess horizon.</li>
 *   <li>{@code materialize} — persist a summary Derived Table (a <b>Matrix</b>, DAT-4) from a measure
 *       spec over a source Dataset; the snapshot swaps in atomically and registers/refreshes a
 *       {@code dataset} component. See {@link MaterializeTask}.</li>
 *   <li>{@code heartbeat} / {@code noop} — do nothing but record a run (liveness probe / test).</li>
 * </ul>
 */
final class MaintenanceJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJob.class);

    private final JobConfig cfg;
    private final String dataDir;   // the space's data root — needed only by the materialize task

    MaintenanceJob(JobConfig cfg) {
        this(cfg, null);
    }

    MaintenanceJob(JobConfig cfg, String dataDir) {
        this.cfg = cfg;
        this.dataDir = dataDir;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "maintenance"; }

    @Override
    public JobResult run() throws Exception {
        String task = cfg.opt("task", "cleanup").toLowerCase();
        return switch (task) {
            case "cleanup"            -> cleanup();
            case "ledger_prune"       -> ledgerPrune();
            case "db_maintenance"     -> dbMaintenance();
            case "compact"            -> PartitionCompactor.run(cfg);
            case "materialize"        -> MaterializeTask.run(cfg, dataDir);
            case "heartbeat", "noop"  -> JobResult.ok("heartbeat", 0L);
            default -> throw new IllegalArgumentException("unknown maintenance task '" + task + "'");
        };
    }

    /** {@code ledger_prune}: forget fingerprints older than {@code retention_days} (see class doc). */
    private JobResult ledgerPrune() {
        long days = Long.parseLong(cfg.require("retention_days"));   // required: forgetting is deliberate
        if (days < 1) throw new IllegalArgumentException("ledger_prune retention_days must be >= 1");
        String source = cfg.opt("source", null);
        long t0 = System.nanoTime();
        long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
        int removed = com.gamma.acquire.AcquisitionLedgers.shared().prune(cutoff, source);
        return JobResult.ok("ledger_prune: removed " + removed + " fingerprint(s) older than " + days + "d"
                + (source != null ? " for source " + source : ""), (System.nanoTime() - t0) / 1_000_000L);
    }

    /** {@code db_maintenance}: CHECKPOINT/VACUUM the acquisition-ledger DB via its live connection. */
    private JobResult dbMaintenance() {
        long t0 = System.nanoTime();
        com.gamma.acquire.AcquisitionLedgers.shared().maintenance();
        return JobResult.ok("db_maintenance: ledger store maintenance completed",
                (System.nanoTime() - t0) / 1_000_000L);
    }

    private JobResult cleanup() {
        Path dir = Path.of(cfg.require("dir"));
        long days = Long.parseLong(cfg.opt("retention_days", "7"));
        String glob = cfg.opt("glob", "*");
        long t0 = System.nanoTime();
        if (!Files.isDirectory(dir)) {
            return JobResult.ok("cleanup: directory not present, nothing to do (" + dir + ")", 0L);
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        PathMatcher matcher = dir.getFileSystem().getPathMatcher("glob:" + glob);
        AtomicInteger deleted = new AtomicInteger();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> matcher.matches(p.getFileName()))
                .filter(p -> olderThan(p, cutoff))
                .forEach(p -> {
                    try { Files.delete(p); deleted.incrementAndGet(); }
                    catch (IOException e) { log.warn("cleanup: could not delete {}: {}", p, e.getMessage()); }
                });
        } catch (IOException e) {
            throw new UncheckedIOException("cleanup walk failed under " + dir, e);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        return JobResult.ok("cleanup: deleted " + deleted.get() + " file(s) older than "
                + days + "d under " + dir + " (glob=" + glob + ")", ms);
    }

    private static boolean olderThan(Path p, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }
}
