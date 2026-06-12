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
 * <h3>Tasks</h3>
 * <ul>
 *   <li>{@code cleanup} (default) — delete files older than {@code retention_days} (default 7)
 *       under {@code dir}, optionally filtered by a {@code glob} (default {@code *}). Useful
 *       for pruning old audit CSVs, markers, or temp output.</li>
 *   <li>{@code heartbeat} / {@code noop} — do nothing but record a run (liveness probe / test).</li>
 * </ul>
 *
 * <p>Params: {@code task}, and for {@code cleanup}: {@code dir} (required),
 * {@code retention_days} (optional, default 7), {@code glob} (optional, default {@code *}).
 */
final class MaintenanceJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJob.class);

    private final JobConfig cfg;

    MaintenanceJob(JobConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String name() { return cfg.name(); }
    @Override public JobType type() { return JobType.MAINTENANCE; }

    @Override
    public JobResult run() throws Exception {
        String task = cfg.opt("task", "cleanup").toLowerCase();
        return switch (task) {
            case "cleanup"            -> cleanup();
            case "heartbeat", "noop"  -> JobResult.ok("heartbeat", 0L);
            default -> throw new IllegalArgumentException("unknown maintenance task '" + task + "'");
        };
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
