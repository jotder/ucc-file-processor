package com.gamma.etl;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Writes a materialized table to partitioned output, excluding the internal
 * {@code __src_id} column, and reveals each partition file under a stable name
 * via a two-step atomic rename.
 *
 * <p>The {@code __src_id} column is dropped from the written rows with
 * {@code SELECT * EXCLUDE (__src_id)} so the output schema is unchanged.
 *
 * <p>Extracted from {@link DataTransformer}, where this COPY/rename logic lived
 * before batching required it to be reusable and lineage-aware.
 */
public final class PartitionWriter {

    private PartitionWriter() {}

    private static final List<String> DEFAULT_PARTITION_COLS = List.of("year", "month", "day");

    /**
     * Reveal the staged partition files in parallel only when there are at least
     * this many. Below the threshold a sequential loop is faster (no fork/join
     * setup) and keeps low-cardinality output byte-for-byte as before; above it,
     * the per-file rename dance is what dominates write cost, so we fan it out.
     */
    private static final int REVEAL_PARALLEL_THRESHOLD = 16;

    /**
     * Backward-compatible overload — partitions by {@code (year, month, day)}.
     *
     * @param conn         worker DuckDB connection containing {@code table}
     * @param table        table to write (must contain partition cols + {@code __src_id})
     * @param databaseDir  output root (already resolved to include any table sub-dir)
     * @param outputFormat {@code "CSV"} or {@code "PARQUET"}
     * @param compression  parquet compression (ignored for CSV; may be {@code null})
     * @param baseName     output file stem; final files are {@code <baseName>_out.<ext>}
     * @return one {@link PartitionOutput} per revealed partition file
     */
    public static List<PartitionOutput> write(Connection conn, String table,
                                              String databaseDir, String outputFormat,
                                              String compression, String baseName)
            throws Exception {
        return write(conn, table, databaseDir, outputFormat, compression, baseName,
                DEFAULT_PARTITION_COLS);
    }

    /**
     * Write {@code table} to Hive-partitioned output using the supplied partition columns.
     *
     * @param partitionColumns ordered list of column names to partition by (e.g.
     *                         {@code ["event_type","year","month","day"]}); must be
     *                         non-empty and present in {@code table}
     */
    public static List<PartitionOutput> write(Connection conn, String table,
                                              String databaseDir, String outputFormat,
                                              String compression, String baseName,
                                              List<String> partitionColumns)
            throws Exception {
        // CSV/plugin ingest path: the materialized table carries the internal
        // __src_id lineage tag, which must be excluded from written output.
        return write(conn, table, databaseDir, outputFormat, compression, baseName,
                partitionColumns, List.of("__src_id"));
    }

    /**
     * Full overload: write {@code table} partitioned by {@code partitionColumns},
     * excluding {@code excludeColumns} from the written rows. Pass an empty list to
     * write every column — e.g. the enrichment engine, whose output has no
     * {@code __src_id} to strip.
     */
    public static List<PartitionOutput> write(Connection conn, String table,
                                              String databaseDir, String outputFormat,
                                              String compression, String baseName,
                                              List<String> partitionColumns,
                                              List<String> excludeColumns)
            throws Exception {

        OutputFormat fmt = OutputFormat.resolve(outputFormat);
        String  outputFileName = baseName + "_out" + fmt.extension();

        new File(databaseDir).mkdirs();
        String workerTag   = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path   stagingPath = Paths.get(databaseDir, ".staging", workerTag);
        Files.createDirectories(stagingPath);
        String stagingDir  = stagingPath.toString().replace("\\", "/");

        List<PartitionOutput> outputs;

        try (Statement stmt = conn.createStatement()) {
            String partBy = String.join(", ", partitionColumns);
            StringBuilder copyOpts = new StringBuilder("FORMAT ").append(fmt.copyToken())
                    .append(", PARTITION_BY (").append(partBy).append("), OVERWRITE_OR_IGNORE 1");
            if (fmt.supportsCompression() && compression != null && !compression.isBlank())
                copyOpts.append(", COMPRESSION ").append(compression);

            String projection = (excludeColumns == null || excludeColumns.isEmpty())
                    ? "SELECT * FROM " + table
                    : "SELECT * EXCLUDE (" + String.join(", ", excludeColumns) + ") FROM " + table;
            stmt.execute(String.format("COPY (%s) TO '%s' (%s)", projection, stagingDir, copyOpts));

            // Collect the staged partition files in one walk, then reveal each under its
            // stable name. The reveal (a cross-dir rename into place + an atomic same-dir
            // rename) is what dominates write cost when the partition fan-out is large, so
            // for many files we fan it out across the common pool; each file targets a
            // distinct partition directory, so the renames don't contend. Output order is
            // irrelevant (callers key by partition), so parallel collection is safe.
            List<Path> stagedFiles;
            try (Stream<Path> staged = Files.walk(stagingPath)) {
                stagedFiles = staged.filter(Files::isRegularFile)
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            }
            final Path stagingRoot = stagingPath;
            Stream<Path> revealStream = stagedFiles.size() >= REVEAL_PARALLEL_THRESHOLD
                    ? stagedFiles.parallelStream() : stagedFiles.stream();
            outputs = revealStream
                    .map(src -> reveal(src, stagingRoot, databaseDir, outputFileName))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

            try (Stream<Path> cleanup = Files.walk(stagingPath)) {
                cleanup.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        return outputs;
    }

    /**
     * Reveal one staged partition file under its stable {@code <baseName>_out.<ext>}
     * name: move it into the final partition directory as a unique temp, then
     * atomically rename within that directory (a same-dir rename is atomic on every
     * platform, unlike a cross-dir one). The temp name embeds the staged file name so
     * concurrent reveals into the same partition directory never collide on the temp.
     */
    private static PartitionOutput reveal(Path src, Path stagingRoot,
                                          String databaseDir, String outputFileName) {
        Path rel      = stagingRoot.relativize(src);
        Path dstFinal = Paths.get(databaseDir).resolve(rel).resolveSibling(outputFileName);
        Path dstTemp  = dstFinal.resolveSibling(outputFileName + "." + src.getFileName() + ".tmp");
        try {
            Files.createDirectories(dstFinal.getParent());
            Files.move(src, dstTemp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(dstTemp, dstFinal,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            String partition = rel.getParent().toString().replace("\\", "/");
            return new PartitionOutput(partition, dstFinal.toString(), Files.size(dstFinal));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
