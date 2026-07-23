package com.gamma.job;

import com.gamma.notify.NotificationStore;
import com.gamma.signal.Severity;
import com.gamma.signal.Signals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A {@link JobType#MAINTENANCE} job: a named built-in housekeeping task. Keeps the
 * platform tidy on a schedule without a separate cron/script.
 *
 * <h3>Tasks (PIP-7 maintenance library)</h3>
 * <ul>
 *   <li>{@code cleanup} (default) — retire files under {@code dir} that breach ANY configured policy
 *       limit (MNT-2b): older than {@code retention_days} (default 7), beyond the newest {@code max_count}
 *       files, or beyond a newest-first {@code max_size} byte budget; optionally filtered by a {@code glob}
 *       (default {@code *}). With {@code archive_instead_of_delete: true} affected files move to the
 *       required {@code archive_dir} (relative structure preserved, never re-walked) instead of being
 *       deleted. Useful for pruning old audit CSVs, markers, backups, exports, or quarantine.</li>
 *   <li>{@code ledger_prune} — delete acquisition-ledger fingerprints processed more than
 *       {@code retention_days} (required) ago, optionally scoped to one {@code source}. <b>Deliberate
 *       forgetting</b>: a pruned file still present at the source re-ingests as NEW — retention must
 *       exceed the source's own file lifetime.</li>
 *   <li>{@code runlog_prune} — delete Run history older than {@code retention_days} (required): the per-run
 *       JSONL files under {@code <auditDir>/runlog/} and {@code <auditDir>/artifacts/}, plus rows of the
 *       optional {@code inspecto_job_runs} projection; optional {@code max_count} caps each JSONL dir to
 *       its newest N files (System Maintenance MNT-2a).</li>
 *   <li>{@code notification_prune} — delete in-app notifications older than {@code retention_days}
 *       (required) from this space's feed, whatever their read/archived state. <b>Deliberate
 *       forgetting</b> like the other prunes. (The default feed is in-memory and self-caps, so this
 *       matters most once a persistent notification backend lands.)</li>
 *   <li>{@code storage_report} — read-only per-axis storage usage + largest consumers over {@code dir},
 *       recorded as Run Artifacts and — on a real run with a data/write root configured — appended as one
 *       row per axis to the queryable {@code maintenance_storage} catalog Dataset (the sample series
 *       {@code storage_trend} reads); optional {@code warn_bytes} threshold signal (MNT-3).</li>
 *   <li>{@code storage_trend} — read-only growth-trend analysis over the {@code maintenance_storage}
 *       sample series: per-axis and total bytes/day over a {@code window_days} window, a projected
 *       {@code warn_bytes} breach ETA, and the fastest-growing axes as archive candidates; emits
 *       {@code maintenance.storage.trend} when the projected breach is within {@code warn_days} (System
 *       Maintenance COULD tier). See {@link StorageTrendTask}.</li>
 *   <li>{@code scheduler_audit} — read-only hygiene audit of the Job registry: disabled jobs, duplicate
 *       names/specs, orphan triggers (MNT-4).</li>
 *   <li>{@code backup} / {@code backup_verify} / {@code restore} — zip + SHA-256-manifest archive of a
 *       directory, hash verification against the sidecar manifest, and fail-closed restore with preview
 *       and conflict detection (MNT-5/MNT-6). See {@link BackupTask}.</li>
 *   <li>{@code metadata_validate} — read-only cross-component integrity audit: broken references,
 *       duplicate definitions, missing physical data (MNT-7). See {@link MetadataValidateTask}.</li>
 *   <li>{@code file_repository_audit} — read-only data-root audit: unregistered stores + stale
 *       partial/temp files from interrupted writes (MNT-12).</li>
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

    /** The Dataset {@code storage_report} appends its per-axis samples to (one file per run, glob-union)
     *  and {@code storage_trend} reads (MNT-3 → COULD-tier growth-trend analysis). */
    private static final String STORAGE_CATALOG = "maintenance_storage";

    private final JobConfig cfg;
    private final String dataDir;   // the space's data root — needed only by the materialize task
    private final String auditDir;  // the space's audit root — needed only by the runlog_prune task
    /** The optional DuckDB run projection ({@code -Djobs.backend=duckdb}) runlog_prune also trims; may be null. */
    private final DbJobRunStore runStore;
    /** The hosting registry the scheduler_audit task inspects (MNT-4); null (bare test construction) = nothing to audit. */
    private final JobService host;

    MaintenanceJob(JobConfig cfg) {
        this(cfg, null);
    }

    MaintenanceJob(JobConfig cfg, String dataDir) {
        this(cfg, dataDir, null, null);
    }

    MaintenanceJob(JobConfig cfg, String dataDir, String auditDir, DbJobRunStore runStore) {
        this(cfg, dataDir, auditDir, runStore, null);
    }

    MaintenanceJob(JobConfig cfg, String dataDir, String auditDir, DbJobRunStore runStore, JobService host) {
        this.cfg = cfg;
        this.dataDir = dataDir;
        this.auditDir = auditDir;
        this.runStore = runStore;
        this.host = host;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "maintenance"; }

    @Override
    public JobResult run() throws Exception {
        return execute(null);
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        return execute(ctx);
    }

    private JobResult execute(JobContext ctx) throws Exception {
        boolean dryRun = ctx != null && ctx.dryRun();
        String task = cfg.opt("task", "cleanup").toLowerCase();
        return switch (task) {
            case "cleanup"            -> cleanup(dryRun);
            case "ledger_prune"       -> ledgerPrune(dryRun);
            case "runlog_prune"       -> runlogPrune(dryRun);
            case "notification_prune" -> notificationPrune(dryRun);
            // Read-only observers: a dry run and a real run observe the same thing. (storage_report
            // additionally persists its sample to the maintenance_storage catalog — real runs only.)
            case "storage_report"     -> storageReport(ctx);
            case "storage_trend"      -> StorageTrendTask.run(cfg, dataDir, ctx);
            case "scheduler_audit"    -> schedulerAudit(ctx);
            case "backup_verify"      -> BackupTask.verify(cfg, ctx);
            case "metadata_validate"  -> MetadataValidateTask.run(ctx, dataDir);
            case "file_repository_audit" -> fileRepositoryAudit(ctx);
            // Phase-2 write tasks with real previews (MNT-5/MNT-6).
            case "backup"             -> BackupTask.backup(cfg, ctx, dryRun, dataDir);
            case "restore"            -> BackupTask.restore(cfg, ctx, dryRun);
            case "db_maintenance"     -> dryRun
                    ? JobResult.ok("db_maintenance[dry-run]: would run CHECKPOINT/VACUUM on the ledger store", 0L)
                    : dbMaintenance();
            // Safe by default (MNT-1): a task with no preview does NOTHING on a dry run — never falls
            // through to the real action.
            case "compact"            -> dryRun ? noPreview(task) : PartitionCompactor.run(cfg);
            case "materialize"        -> dryRun ? noPreview(task) : MaterializeTask.run(cfg, dataDir);
            case "heartbeat", "noop"  -> JobResult.ok("heartbeat", 0L);
            default -> throw new IllegalArgumentException("unknown maintenance task '" + task + "'");
        };
    }

    private static JobResult noPreview(String task) {
        return JobResult.ok("[dry-run] task '" + task + "' has no preview — no action taken", 0L);
    }

    /** {@code ledger_prune}: forget fingerprints older than {@code retention_days} (see class doc). */
    private JobResult ledgerPrune(boolean dryRun) {
        long days = Long.parseLong(cfg.require("retention_days"));   // required: forgetting is deliberate
        if (days < 1) throw new IllegalArgumentException("ledger_prune retention_days must be >= 1");
        String source = cfg.opt("source", null);
        long t0 = System.nanoTime();
        long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
        var ledger = com.gamma.acquire.AcquisitionLedgers.shared();
        String scope = " older than " + days + "d" + (source != null ? " for source " + source : "");
        if (dryRun) {
            int would = ledger.countPrunable(cutoff, source);
            return JobResult.ok("ledger_prune[dry-run]: would remove " + would + " fingerprint(s)" + scope,
                    (System.nanoTime() - t0) / 1_000_000L);
        }
        int removed = ledger.prune(cutoff, source);
        return JobResult.ok("ledger_prune: removed " + removed + " fingerprint(s)" + scope,
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /**
     * {@code notification_prune}: forget in-app notifications older than {@code retention_days}
     * (required — deliberate forgetting, like {@code ledger_prune}), whatever their read/archived state.
     * The feed is per-space and reached through the hosting {@link JobService}; a bare/lazily-created
     * host with no feed attached prunes nothing (fail-open — never throws).
     */
    private JobResult notificationPrune(boolean dryRun) {
        long days = Long.parseLong(cfg.require("retention_days"));   // required: forgetting is deliberate
        if (days < 1) throw new IllegalArgumentException("notification_prune retention_days must be >= 1");
        long t0 = System.nanoTime();
        var store = host == null ? java.util.Optional.<NotificationStore>empty() : host.notificationStore();
        if (store.isEmpty())
            return JobResult.ok("notification_prune: no notification feed attached — nothing to prune", 0L);
        long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
        if (dryRun) {
            int would = store.get().countPrunable(cutoff);
            return JobResult.ok("notification_prune[dry-run]: would remove " + would
                    + " notification(s) older than " + days + "d", (System.nanoTime() - t0) / 1_000_000L);
        }
        int removed = store.get().prune(cutoff);
        return JobResult.ok("notification_prune: removed " + removed + " notification(s) older than "
                + days + "d", (System.nanoTime() - t0) / 1_000_000L);
    }

    /**
     * {@code runlog_prune} (MNT-2a): forget Run history older than {@code retention_days} (required —
     * forgetting is deliberate, like {@code ledger_prune}): per-run JSONL files under
     * {@code <auditDir>/runlog/} and {@code <auditDir>/artifacts/} (by file mtime ≈ run end time), plus
     * rows of the optional {@code inspecto_job_runs} DuckDB projection. An optional {@code max_count}
     * additionally caps each JSONL dir to its newest N files regardless of age (0/absent = no cap; the
     * count cap does not apply to the DB rows — retention governs those).
     */
    private JobResult runlogPrune(boolean dryRun) {
        long days = Long.parseLong(cfg.require("retention_days"));   // required: forgetting is deliberate
        if (days < 1) throw new IllegalArgumentException("runlog_prune retention_days must be >= 1");
        int maxCount = Integer.parseInt(cfg.opt("max_count", "0"));
        if (maxCount < 0) throw new IllegalArgumentException("runlog_prune max_count must be >= 0");
        long t0 = System.nanoTime();
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        Path auditRoot = auditDir == null ? null : Path.of(auditDir);
        int logs      = pruneJsonl(auditRoot == null ? null : auditRoot.resolve("runlog"), cutoff, maxCount, dryRun);
        int artifacts = pruneJsonl(auditRoot == null ? null : auditRoot.resolve("artifacts"), cutoff, maxCount, dryRun);
        String dbCutoff = java.time.LocalDateTime.now().minusDays(days)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        int rows = runStore == null ? 0
                : (dryRun ? runStore.countPrunable(dbCutoff) : runStore.prune(dbCutoff));
        String verb = dryRun ? "runlog_prune[dry-run]: would remove " : "runlog_prune: removed ";
        return JobResult.ok(verb + logs + " run log(s), " + artifacts + " artifact file(s), "
                + rows + " projected run row(s) older than " + days + "d"
                + (maxCount > 0 ? " (max_count=" + maxCount + ")" : ""),
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /** Prune one JSONL dir: everything older than {@code cutoff}, plus (when {@code maxCount} > 0)
     *  everything beyond the newest {@code maxCount} files. Returns the affected count. */
    private static int pruneJsonl(Path dir, Instant cutoff, int maxCount, boolean dryRun) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = new ArrayList<>(s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList());
        } catch (IOException e) {
            throw new UncheckedIOException("runlog_prune list failed under " + dir, e);
        }
        files.sort(Comparator.comparing(MaintenanceJob::mtime).reversed());   // newest first
        int affected = 0;
        for (int i = 0; i < files.size(); i++) {
            Path p = files.get(i);
            boolean victim = mtime(p).isBefore(cutoff) || (maxCount > 0 && i >= maxCount);
            if (!victim) continue;
            try {
                if (!dryRun) Files.delete(p);
                affected++;
            } catch (IOException e) {
                log.warn("runlog_prune: could not delete {}: {}", p, e.getMessage());
            }
        }
        return affected;
    }

    /** A file's mtime; unreadable files sort as freshest and are never pruned (fail-closed). */
    private static Instant mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.MAX;
        }
    }

    /**
     * {@code storage_report} (MNT-3): read-only storage observation over {@code dir} (typically the
     * space root). Reports per-axis (immediate subdirectory) file counts and bytes, records each axis
     * as a Run Artifact so the series accumulates queryably run over run, logs the {@code top} largest
     * files, and — when {@code warn_bytes} is set and breached — emits a
     * {@code maintenance.storage.threshold} WARNING signal an Alert Rule can subscribe to.
     */
    private JobResult storageReport(JobContext ctx) {
        Path dir = Path.of(cfg.require("dir"));
        long warnBytes = Long.parseLong(cfg.opt("warn_bytes", "0"));   // 0 = no threshold
        int top = Integer.parseInt(cfg.opt("top", "5"));
        long t0 = System.nanoTime();
        if (!Files.isDirectory(dir)) {
            return JobResult.ok("storage_report: directory not present, nothing to report (" + dir + ")", 0L);
        }
        Map<String, long[]> axes = new TreeMap<>();          // axis -> {files, bytes}
        List<Map.Entry<Path, Long>> files = new ArrayList<>();
        long totalBytes = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                long size;
                try {
                    size = Files.size(p);
                } catch (IOException e) {
                    log.warn("storage_report: could not size {}: {}", p, e.getMessage());
                    continue;
                }
                Path rel = dir.relativize(p);
                String axis = rel.getNameCount() > 1 ? rel.getName(0).toString() : ".";
                long[] acc = axes.computeIfAbsent(axis, k -> new long[2]);
                acc[0]++;
                acc[1] += size;
                files.add(Map.entry(p, size));
                totalBytes += size;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("storage_report walk failed under " + dir, e);
        }
        boolean breached = warnBytes > 0 && totalBytes > warnBytes;
        if (ctx != null) {
            axes.forEach((axis, acc) -> ctx.artifacts().file("axis:" + axis,
                    ".".equals(axis) ? dir : dir.resolve(axis), acc[1]));
            files.sort(Map.Entry.<Path, Long>comparingByValue().reversed());
            for (Map.Entry<Path, Long> f : files.subList(0, Math.min(top, files.size())))
                ctx.log().info("largest consumer", "path", f.getKey().toString(), "bytes", f.getValue());
            if (breached)
                ctx.signals().emit("maintenance.storage.threshold", Severity.WARN,
                        Map.of("dir", dir.toString(), "totalBytes", totalBytes, "warnBytes", warnBytes));
            // Persist this run's per-axis sample so the series accumulates queryably for storage_trend —
            // real runs only (a dry-run preview must never add a data point to the trend).
            if (!ctx.dryRun()) storageCatalog(ctx, axes);
        }
        StringBuilder perAxis = new StringBuilder();
        axes.forEach((axis, acc) -> perAxis.append(perAxis.isEmpty() ? "" : ", ")
                .append(axis).append('=').append(acc[1]).append('b'));
        return JobResult.ok("storage_report: " + files.size() + " file(s), " + totalBytes + " byte(s) under "
                + dir + " [" + perAxis + "]"
                + (breached ? " — OVER warn_bytes=" + warnBytes : ""),
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /**
     * Append this {@code storage_report} run's per-axis sample as one Parquet (a row per axis) in
     * {@code <dataDir>/maintenance_storage/} (readers glob {@code *.parquet} — rows union across runs)
     * and idempotently register the {@code maintenance_storage} Dataset, the {@link BackupTask} /
     * {@link MaterializeTask} catalog idiom. {@code created_ms} is the sortable/filterable sample key
     * (epoch millis — ISO-string comparison is not reliably chronological across variable precision).
     * Best-effort: a missing data/write root or a Parquet failure is noted in the Run Log, never fails
     * the report.
     */
    private void storageCatalog(JobContext ctx, Map<String, long[]> axes) {
        String writeRoot = System.getProperty("assist.write.root");
        if (dataDir == null || dataDir.isBlank() || writeRoot == null || writeRoot.isBlank()) {
            ctx.log().info("storage_report catalog skipped (no data root / write root configured)");
            return;
        }
        try {
            Instant now = Instant.now();
            Path storeDir = Path.of(dataDir).resolve(STORAGE_CATALOG);
            Files.createDirectories(storeDir);
            Path parquet = storeDir.resolve("storage_" + now.toEpochMilli() + "_out.parquet");
            com.gamma.util.DuckDbUtil.loadDriver();
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE storage_sample (created VARCHAR, created_ms BIGINT, "
                            + "axis VARCHAR, files BIGINT, bytes BIGINT)");
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO storage_sample VALUES (?,?,?,?,?)")) {
                    for (Map.Entry<String, long[]> e : axes.entrySet()) {
                        ps.setString(1, now.toString());
                        ps.setLong(2, now.toEpochMilli());
                        ps.setString(3, e.getKey());
                        ps.setLong(4, e.getValue()[0]);
                        ps.setLong(5, e.getValue()[1]);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (Statement st = conn.createStatement()) {
                    st.execute("COPY storage_sample TO '"
                            + parquet.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")
                            + "' (FORMAT PARQUET)");
                }
            }
            com.gamma.pipeline.ComponentStore store =
                    new com.gamma.pipeline.ComponentStore(Path.of(writeRoot).resolve("registry"));
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("name", STORAGE_CATALOG);
            content.put("physicalRef", STORAGE_CATALOG);
            content.put("description", "System Maintenance storage-usage samples (one row per axis per run, MNT-3)");
            store.write("dataset", STORAGE_CATALOG, content, false);   // result-stamp write, no version churn
            ctx.artifacts().dataset(STORAGE_CATALOG, STORAGE_CATALOG, null, (long) axes.size(), null);
        } catch (Exception e) {
            log.warn("storage_report catalog append failed (report itself succeeded): {}", e.getMessage());
            ctx.log().warn("storage_report catalog append failed: " + e.getMessage());
        }
    }

    /**
     * {@code scheduler_audit} (MNT-4): read-only hygiene audit of the hosting Job registry. Finding
     * classes: disabled jobs, duplicate job names (last registration wins, hiding the earlier one),
     * identical specs under different names, {@code on_pipeline} triggers naming a pipeline/job the
     * host doesn't know (only when the host wired its pipeline names — never guesses), and
     * {@code on_signal} triggers whose type no registered Job Type declares in {@code emits} and that
     * isn't a framework lifecycle type (reported as "verify", not asserted broken — signal types are
     * open). Cron syntax needs no re-check here: it is validated eagerly at config load. Findings go
     * to the Run Log and, when any exist, one {@code maintenance.scheduler.findings} WARNING signal.
     */
    private JobResult schedulerAudit(JobContext ctx) {
        long t0 = System.nanoTime();
        if (host == null) {
            return JobResult.ok("scheduler_audit: no job registry attached — nothing to audit", 0L);
        }
        List<JobConfig> all = host.configSnapshot();
        List<String> findings = new ArrayList<>();
        Map<String, Integer> nameCounts = new LinkedHashMap<>();
        Map<String, List<String>> specs = new LinkedHashMap<>();
        for (JobConfig c : all) {
            if (!c.enabled()) findings.add("disabled job '" + c.name() + "'");
            nameCounts.merge(c.name(), 1, Integer::sum);
            if (c.enabled()) specs.computeIfAbsent(c.type() + "|" + c.cron() + "|" + c.onPipeline()
                    + "|" + c.onSignal() + "|" + c.params(), k -> new ArrayList<>()).add(c.name());
        }
        nameCounts.forEach((n, k) -> {
            if (k > 1) findings.add("duplicate job name '" + n + "' (" + k + " definitions — last wins)");
        });
        for (List<String> names : specs.values()) {
            if (names.size() > 1 && Set.copyOf(names).size() > 1)
                findings.add("duplicate definition: jobs " + names + " share an identical spec");
        }
        Set<String> pipelines = host.knownPipelineNames();   // null = host never wired them — skip, don't guess
        if (pipelines != null) {
            Set<String> jobNames = new HashSet<>();
            for (JobConfig c : all) jobNames.add(c.name().toLowerCase());
            for (JobConfig c : all) {
                if (!c.enabled() || !c.hasEvent()) continue;
                String target = c.onPipeline().toLowerCase();   // BatchEvent.pipeline() is lowercased
                if (!pipelines.contains(target) && !jobNames.contains(target))
                    findings.add("orphan trigger: job '" + c.name() + "' waits on unknown pipeline '"
                            + c.onPipeline() + "'");
            }
        }
        Set<String> emitted = new LinkedHashSet<>(List.of("job.run.started", "job.run.completed",
                "job.run.failed", "job.run.rejected", "job.chain.cut", "pipeline.commit"));
        for (JobTypeDescriptor d : host.jobTypes()) emitted.addAll(d.emits());
        for (JobConfig c : all) {
            if (!c.enabled() || !c.hasSignal()) continue;
            boolean anyProducer = emitted.stream().anyMatch(t -> Signals.matchesType(t, c.onSignal()));
            if (!anyProducer)
                findings.add("no declared producer for on_signal '" + c.onSignal() + "' (job '"
                        + c.name() + "') — verify the emitter exists");
        }
        if (ctx != null) {
            for (String f : findings) ctx.log().warn(f);
            if (!findings.isEmpty())
                ctx.signals().emit("maintenance.scheduler.findings", Severity.WARN,
                        Map.of("count", findings.size(), "findings", findings));
        }
        return JobResult.ok("scheduler_audit: " + findings.size() + " finding(s) across " + all.size()
                + " job(s)" + (findings.isEmpty() ? " — healthy" : ""),
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /**
     * {@code file_repository_audit} (MNT-12): read-only audit of the data root. Finding classes:
     * store directories with no owning Dataset component (informational — a sink may legitimately
     * write an unregistered store; checked only when a component registry is configured, never
     * guessed) and stale partial/temporary files left by interrupted writes ({@code *.tmp},
     * {@code *.compacting}, {@code .compact-journal}) older than {@code min_age_days} (default 1 —
     * the quiet window, so an in-flight write is never flagged). Findings go to the Run Log and one
     * {@code maintenance.filerepo.findings} WARNING signal.
     */
    private JobResult fileRepositoryAudit(JobContext ctx) {
        long t0 = System.nanoTime();
        if (dataDir == null || dataDir.isBlank() || !Files.isDirectory(Path.of(dataDir))) {
            return JobResult.ok("file_repository_audit: no data root — nothing to audit", 0L);
        }
        Path dataRoot = Path.of(dataDir);
        long minAgeDays = Long.parseLong(cfg.opt("min_age_days", "1"));
        Instant cutoff = Instant.now().minus(Duration.ofDays(minAgeDays));
        List<String> findings = new ArrayList<>();
        String writeRoot = System.getProperty("assist.write.root");
        if (writeRoot != null && !writeRoot.isBlank()) {
            Set<String> refs = new HashSet<>();
            var store = new com.gamma.pipeline.ComponentStore(Path.of(writeRoot).resolve("registry"));
            for (var d : store.list("dataset")) {
                Object ref = d.content().get("physicalRef");
                if (ref != null) refs.add(String.valueOf(ref));
            }
            try (Stream<Path> s = Files.list(dataRoot)) {
                for (Path p : s.filter(Files::isDirectory).toList()) {
                    String name = p.getFileName().toString();
                    if (!name.startsWith(".") && !refs.contains(name))
                        findings.add("store '" + name + "' has no owning dataset component");
                }
            } catch (IOException e) {
                throw new UncheckedIOException("file_repository_audit list failed under " + dataRoot, e);
            }
        } else if (ctx != null) {
            ctx.log().info("unregistered-store check skipped (no component registry configured)");
        }
        try (Stream<Path> walk = Files.walk(dataRoot)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                boolean partial = name.endsWith(".tmp") || name.endsWith(".compacting")
                        || name.equals(".compact-journal");
                if (partial && olderThan(p, cutoff))
                    findings.add("stale partial file (older than " + minAgeDays + "d): " + dataRoot.relativize(p));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("file_repository_audit walk failed under " + dataRoot, e);
        }
        if (ctx != null) {
            for (String f : findings) ctx.log().warn(f);
            if (!findings.isEmpty())
                ctx.signals().emit("maintenance.filerepo.findings", Severity.WARN,
                        Map.of("count", findings.size(), "findings", findings));
        }
        return JobResult.ok("file_repository_audit: " + findings.size() + " finding(s) under " + dataRoot
                + (findings.isEmpty() ? " — healthy" : ""), (System.nanoTime() - t0) / 1_000_000L);
    }

    /** {@code db_maintenance}: CHECKPOINT/VACUUM every per-space DuckDB store over its own live
     *  connection (single-writer — never a second connection): the acquisition ledger, plus — when the
     *  hosting service configured them — the job-run and provenance projections (MNT-9). */
    private JobResult dbMaintenance() {
        long t0 = System.nanoTime();
        com.gamma.acquire.AcquisitionLedgers.shared().maintenance();
        int stores = 1;
        if (host != null) {
            var jobRuns = host.runStore();
            if (jobRuns.isPresent()) {
                jobRuns.get().maintenance();
                stores++;
            }
            var provenance = host.provenanceStore();
            if (provenance.isPresent()) {
                provenance.get().maintenance();
                stores++;
            }
        }
        return JobResult.ok("db_maintenance: " + stores + " store(s) maintenance completed"
                + (stores > 1 ? " (ledger + reporting/provenance projections)" : " (ledger)"),
                (System.nanoTime() - t0) / 1_000_000L);
    }

    private JobResult cleanup(boolean dryRun) {
        Path dir = Path.of(cfg.require("dir"));
        long days = Long.parseLong(cfg.opt("retention_days", "7"));
        String glob = cfg.opt("glob", "*");
        int maxCount = Integer.parseInt(cfg.opt("max_count", "0"));    // 0 = no count cap
        long maxSize = Long.parseLong(cfg.opt("max_size", "0"));       // bytes; 0 = no size cap
        // MNT-2c: the newest min_keep files are never retired, whatever the other limits say — so a
        // retention policy on a backup dir can never delete the last remaining backups.
        int minKeep = Integer.parseInt(cfg.opt("min_keep", "0"));
        boolean archive = Boolean.parseBoolean(cfg.opt("archive_instead_of_delete", "false"));
        // Archiving needs an explicit destination — no silent default that a later cleanup would re-walk.
        Path archiveDir = archive ? Path.of(cfg.require("archive_dir")) : null;
        long t0 = System.nanoTime();
        if (!Files.isDirectory(dir)) {
            return JobResult.ok("cleanup: directory not present, nothing to do (" + dir + ")", 0L);
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        PathMatcher matcher = dir.getFileSystem().getPathMatcher("glob:" + glob);
        List<Path> matches;
        try (Stream<Path> walk = Files.walk(dir)) {
            matches = new ArrayList<>(walk.filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(p.getFileName()))
                    .filter(p -> archiveDir == null || !p.startsWith(archiveDir))   // never re-clean the archive
                    .toList());
        } catch (IOException e) {
            throw new UncheckedIOException("cleanup walk failed under " + dir, e);
        }
        matches.sort(Comparator.comparing(MaintenanceJob::mtime).reversed());   // newest first
        // Policy dims combine as OR (MNT-2b): a file is affected when it breaches ANY configured limit —
        // older than retention, beyond the newest max_count files, or beyond the newest-first max_size budget.
        int affected = 0;
        long bytes = 0, kept = 0;
        for (int i = 0; i < matches.size(); i++) {
            Path p = matches.get(i);
            long size;
            try {
                size = Files.size(p);
            } catch (IOException e) {
                log.warn("cleanup: could not size {}: {}", p, e.getMessage());
                continue;
            }
            boolean victim = i >= minKeep
                    && (olderThan(p, cutoff)
                        || (maxCount > 0 && i >= maxCount)
                        || (maxSize > 0 && kept + size > maxSize));
            if (!victim) {
                kept += size;
                continue;
            }
            try {
                if (!dryRun) {
                    if (archive) {
                        Path target = archiveDir.resolve(dir.relativize(p));
                        Files.createDirectories(target.getParent());
                        Files.move(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.delete(p);
                    }
                }
                affected++;
                bytes += size;
            } catch (IOException e) {
                log.warn("cleanup: could not {} {}: {}", archive ? "archive" : "delete", p, e.getMessage());
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        String action = archive ? "archive" : "delete";
        String verb = dryRun ? "cleanup[dry-run]: would " + action + " "
                             : "cleanup: " + action + "d ";
        return JobResult.ok(verb + affected + " file(s), " + bytes + " byte(s), older than " + days
                + "d under " + dir + " (glob=" + glob
                + (maxCount > 0 ? ", max_count=" + maxCount : "")
                + (maxSize > 0 ? ", max_size=" + maxSize : "")
                + (archive ? ", archive_dir=" + archiveDir : "") + ")", ms);
    }

    private static boolean olderThan(Path p, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }
}
