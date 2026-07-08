package com.gamma.job;

import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The {@code compact} maintenance task (PIP-7): merge the many small per-batch Parquet files inside each
 * partition directory under {@code dir} into one, via DuckDB's own read/write kernel
 * ({@code COPY (SELECT * FROM read_parquet([...])) TO merged}).
 *
 * <p><b>Safety model</b> (there is no lock between jobs and ingest — {@code DeletionFence} is advisory):
 * <ul>
 *   <li><b>Age cutoff</b> — only files older than {@code min_age_days} are touched; a concurrent batch commit
 *       only ever creates a <em>new</em> uniquely-named file, so the candidate set cannot race a writer.</li>
 *   <li><b>Glob invisibility</b> — every intermediate ({@code *.compact.tmp}, hidden originals
 *       {@code *.parquet.compacting}, the {@code .compact-journal} sentinel) falls outside the readers'
 *       {@code *.parquet} globs; the merged file appears with a single {@code ATOMIC_MOVE}.</li>
 *   <li><b>Crash journal</b> — each directory writes a {@code .compact-journal} (target + originals) before
 *       touching anything. On the next run {@link #heal} either finishes the swap (target was revealed) or
 *       restores the hidden originals (it was not), so a killed run never loses or duplicates rows.</li>
 * </ul>
 *
 * <p><b>Known trade-off</b>: {@code reprocess} of a batch whose output file was compacted away degrades to a
 * no-op delete + re-ingest (its manifest's {@code outputFile} no longer exists), which would duplicate its
 * rows — set {@code min_age_days} beyond the operational reprocess horizon. Recorded in
 * {@code docs/REQUIREMENTS.md} (PIP-7) and the job-library example.
 */
final class PartitionCompactor {

    private static final Logger log = LoggerFactory.getLogger(PartitionCompactor.class);
    private static final String JOURNAL = ".compact-journal";
    private static final String HIDDEN_SUFFIX = ".compacting";
    private static final String TMP_SUFFIX = ".compact.tmp";

    private PartitionCompactor() {}

    static JobResult run(JobConfig cfg) throws Exception {
        Path root = Path.of(cfg.require("dir"));
        long minAgeDays = Long.parseLong(cfg.opt("min_age_days", "1"));
        int minFiles = Integer.parseInt(cfg.opt("min_files", "4"));
        if (minFiles < 2) throw new IllegalArgumentException("compact min_files must be >= 2");
        long t0 = System.nanoTime();
        if (!Files.isDirectory(root))
            return JobResult.ok("compact: directory not present, nothing to do (" + root + ")", 0L);

        Instant cutoff = Instant.now().minus(Duration.ofDays(minAgeDays));
        int dirsCompacted = 0, filesMerged = 0;
        DuckDbUtil.loadDriver();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Stream<Path> walk = Files.walk(root)) {
            for (Path dir : (Iterable<Path>) walk.filter(Files::isDirectory)::iterator) {
                heal(dir);
                List<Path> candidates = candidates(dir, cutoff);
                if (candidates.size() < minFiles) continue;
                filesMerged += compactDir(conn, dir, candidates);
                dirsCompacted++;
            }
        }
        return JobResult.ok("compact: merged " + filesMerged + " file(s) across " + dirsCompacted
                + " partition dir(s) under " + root + " (min_age_days=" + minAgeDays
                + ", min_files=" + minFiles + ")", (System.nanoTime() - t0) / 1_000_000L);
    }

    /** The mergeable files in {@code dir}: direct-child {@code *.parquet} older than the cutoff. */
    private static List<Path> candidates(Path dir, Instant cutoff) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(p) || !p.getFileName().toString().endsWith(".parquet")) continue;
                try {
                    if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) out.add(p);
                } catch (IOException vanished) { /* raced away — not a candidate */ }
            }
        }
        return out;
    }

    /** Merge {@code candidates} into one file and swap it in (journal → merge → hide → reveal → clean). */
    private static int compactDir(Connection conn, Path dir, List<Path> candidates) throws Exception {
        String target = "compacted_" + System.currentTimeMillis() + "_out.parquet";
        Path tmp = dir.resolve(target + TMP_SUFFIX);
        Path journal = dir.resolve(JOURNAL);

        // 1. Journal first: target + originals, so heal() can always finish or undo this directory.
        List<String> lines = new ArrayList<>();
        lines.add(target);
        candidates.forEach(p -> lines.add(p.getFileName().toString()));
        Files.write(journal, lines);

        try {
            // 2. Merge into a glob-invisible temp, DuckDB doing the schema-tolerant heavy lifting.
            StringBuilder list = new StringBuilder();
            for (Path p : candidates) {
                if (!list.isEmpty()) list.append(", ");
                list.append('\'').append(p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")).append('\'');
            }
            try (Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT * FROM read_parquet([" + list + "])) TO '"
                        + tmp.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")
                        + "' (FORMAT PARQUET)");
            }

            // 3. Hide the originals (atomic per file; readers' *.parquet globs no longer see them) …
            for (Path p : candidates)
                Files.move(p, sibling(p, HIDDEN_SUFFIX), StandardCopyOption.ATOMIC_MOVE);
            // 4. … reveal the merged file in one atomic rename …
            Files.move(tmp, dir.resolve(target), StandardCopyOption.ATOMIC_MOVE);
            // 5. … then the hidden originals and the journal can go.
            for (Path p : candidates) Files.deleteIfExists(sibling(p, HIDDEN_SUFFIX));
            Files.deleteIfExists(journal);
            return candidates.size();
        } catch (Exception e) {
            heal(dir);   // undo/finish from the journal so the directory is never left half-swapped
            throw e;
        }
    }

    /**
     * Recover {@code dir} from an interrupted compaction using its journal: if the merged target was already
     * revealed, finish deleting the hidden originals; otherwise restore them and discard the temp. No journal
     * (the normal case) — only stray temps to sweep.
     */
    private static void heal(Path dir) throws IOException {
        Path journal = dir.resolve(JOURNAL);
        if (Files.exists(journal)) {
            List<String> lines = Files.readAllLines(journal);
            String target = lines.isEmpty() ? null : lines.get(0);
            boolean revealed = target != null && Files.exists(dir.resolve(target));
            for (String name : lines.subList(lines.isEmpty() ? 0 : 1, lines.size())) {
                Path hidden = dir.resolve(name + HIDDEN_SUFFIX);
                if (revealed) Files.deleteIfExists(hidden);                            // finish the swap
                else if (Files.exists(hidden))                                          // undo it
                    Files.move(hidden, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
            if (target != null) Files.deleteIfExists(dir.resolve(target + TMP_SUFFIX));
            Files.deleteIfExists(journal);
            log.warn("compact: healed interrupted compaction in {} ({})", dir,
                    revealed ? "finished swap" : "restored originals");
        } else {
            // No journal: any *.compact.tmp is pre-journal debris from a hard kill — safe to sweep.
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : (Iterable<Path>) files::iterator)
                    if (p.getFileName().toString().endsWith(TMP_SUFFIX)) Files.deleteIfExists(p);
            }
        }
    }

    private static Path sibling(Path p, String suffix) {
        return p.resolveSibling(p.getFileName().toString() + suffix);
    }
}
