package com.gamma.etl;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Groups matched files by resolved schema/table, then greedily packs each group
 * into {@link Batch}es honoring {@code maxFiles} OR {@code maxBytes} (whichever
 * trips first). A file larger than {@code maxBytes} forms a batch of one.
 *
 * <p>Pure and side-effect free apart from reading {@link File#length()}; the
 * schema resolution is injected via {@link SchemaResolver} so it is unit-testable
 * without a {@link PipelineConfig}.
 */
public final class BatchPlanner {

    private BatchPlanner() {}

    /** Resolves the schema/table for one file (wraps {@code SchemaSelector.select} or a single schema). */
    @FunctionalInterface
    public interface SchemaResolver {
        SchemaSelector.Selection resolve(File file) throws IOException;
    }

    /**
     * Plan batches from the given files.
     *
     * @param files        candidate files (already filtered for duplicates)
     * @param resolver      schema/table resolver
     * @param maxFiles      max member files per batch (>= 1)
     * @param maxBytes      max summed bytes per batch (>= 1)
     * @param runTimestamp  run timestamp embedded in each batch id
     * @return batches, grouped by schema/table, in deterministic order
     * @throws IOException if schema resolution fails
     */
    public static List<Batch> plan(List<File> files, SchemaResolver resolver,
                                   int maxFiles, long maxBytes, String runTimestamp)
            throws IOException {

        // Group by table key (insertion-ordered for determinism), preserving each
        // file's resolved selection. Files sorted by path so packing is reproducible.
        List<File> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(f -> f.toPath().toAbsolutePath().toString()));

        LinkedHashMap<String, List<File>> byKey = new LinkedHashMap<>();
        Map<File, SchemaSelector.Selection> selByFile = new HashMap<>();
        for (File f : sorted) {
            SchemaSelector.Selection sel = resolver.resolve(f);
            String key = (sel.table() != null && !sel.table().isBlank()) ? sel.table() : "default";
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
            selByFile.put(f, sel);
        }

        List<Batch> batches = new ArrayList<>();
        int seq = 1;
        for (Map.Entry<String, List<File>> group : byKey.entrySet()) {
            String key  = group.getKey();
            String slug = key.replaceAll("[^A-Za-z0-9]+", "_");

            List<Batch.Member> current = new ArrayList<>();
            long currentBytes = 0;
            for (File f : group.getValue()) {
                long bytes = f.length();
                boolean wouldExceed = !current.isEmpty()
                        && (current.size() >= maxFiles || currentBytes + bytes > maxBytes);
                if (wouldExceed) {
                    batches.add(buildBatch(runTimestamp, slug, seq++, key, current, selByFile));
                    current = new ArrayList<>();
                    currentBytes = 0;
                }
                current.add(new Batch.Member(f, current.size(), bytes, selByFile.get(f)));
                currentBytes += bytes;
            }
            if (!current.isEmpty())
                batches.add(buildBatch(runTimestamp, slug, seq++, key, current, selByFile));
        }
        return batches;
    }

    private static Batch buildBatch(String ts, String slug, int seq, String table,
                                    List<Batch.Member> members,
                                    Map<File, SchemaSelector.Selection> selByFile) {
        // Re-index srcId from 0 within the final batch (members were added with running index).
        List<Batch.Member> reindexed = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            Batch.Member m = members.get(i);
            reindexed.add(new Batch.Member(m.file(), i, m.bytes(), m.selection()));
        }
        String batchId = String.format("%s_%s_%04d", ts, slug, seq);
        String schemaName = schemaNameOf(reindexed.get(0).selection());
        return new Batch(batchId, schemaName, "default".equals(table) ? null : table, reindexed);
    }

    @SuppressWarnings("unchecked")
    private static String schemaNameOf(SchemaSelector.Selection sel) {
        Object raw = sel.schema().get("raw");
        if (raw instanceof Map<?, ?> rawMap && rawMap.get("name") != null)
            return String.valueOf(rawMap.get("name"));
        return "schema";
    }
}
