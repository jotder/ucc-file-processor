package com.gamma.exchange;

import com.gamma.util.AtomicFiles;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * On-disk layout + pointer discipline for snapshot-mode sharing (S2). An owner's refreshed Dataset lands
 * under {@code <_shared>/exchange/<owner>/<item>/<version>/snapshot.parquet}; a sibling {@code current.toon}
 * names the live version. Consumers only ever read through {@code current.toon} → the {@code <version>/}
 * dir, so a refresh is an <b>atomic pointer flip</b> — a reader never observes a half-written version.
 *
 * <p>Freshness ({@link SnapshotMeta}: version, row count, refresh time, Result Set columns) travels in
 * {@code current.toon} so a consumer sees it in the Exchange catalog without touching the owner's tree.
 */
public final class ExchangeSnapshots {

    private ExchangeSnapshots() {}

    /** The directory holding an offered item's versioned snapshots + {@code current.toon}. */
    public static Path itemDir(Path sharedDir, String owner, String item) {
        return sharedDir.resolve("exchange").resolve(owner).resolve(item);
    }

    private static Path currentFile(Path itemDir) {
        return itemDir.resolve("current.toon");
    }

    /** Freshness metadata for the live snapshot (the {@code current.toon} contents). */
    public record SnapshotMeta(String version, long rows, String refreshedAt, List<Map<String, Object>> columns) {

        public SnapshotMeta {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", version);
            m.put("rows", rows);
            m.put("refreshedAt", refreshedAt);
            m.put("columns", columns);
            return m;
        }

        @SuppressWarnings("unchecked")
        static SnapshotMeta fromMap(Map<String, Object> m) {
            List<Map<String, Object>> cols = new ArrayList<>();
            if (m.get("columns") instanceof List<?> list)
                for (Object o : list) if (o instanceof Map<?, ?> c) cols.add((Map<String, Object>) c);
            return new SnapshotMeta(Ledger.str(m, "version"), Ledger.asLong(m.get("rows")),
                    Ledger.str(m, "refreshedAt"), cols);
        }
    }

    /** The live snapshot's freshness, or empty when nothing has been published for the item yet. */
    @SuppressWarnings("unchecked")
    public static Optional<SnapshotMeta> readCurrent(Path itemDir) {
        Path file = currentFile(itemDir);
        if (!Files.exists(file)) return Optional.empty();
        try {
            Object decoded = JToon.decode(Files.readString(file, StandardCharsets.UTF_8));
            return decoded instanceof Map<?, ?> m
                    ? Optional.of(SnapshotMeta.fromMap((Map<String, Object>) m))
                    : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + file, e);
        }
    }

    /** The live snapshot's version directory (what a consumer's query globs), if a snapshot exists. */
    public static Optional<Path> currentDir(Path itemDir) {
        return readCurrent(itemDir).map(meta -> itemDir.resolve(meta.version()));
    }

    /** Atomically flip {@code current.toon} to {@code meta} (the reveal step of a refresh). */
    static void writeCurrent(Path itemDir, SnapshotMeta meta) {
        try {
            AtomicFiles.write(currentFile(itemDir),
                    JToon.encode(meta.toMap()).getBytes(StandardCharsets.UTF_8), ".current-");
        } catch (IOException e) {
            throw new UncheckedIOException("writing current.toon in " + itemDir, e);
        }
    }

    /**
     * Delete all but the {@code keep} newest version directories (and never the live {@code keepVersion}) —
     * bounds disk growth across refreshes. Best-effort: a version still being read is simply retried next time.
     */
    static void prune(Path itemDir, String keepVersion, int keep) {
        List<Path> versions;
        try (Stream<Path> s = Files.list(itemDir)) {
            versions = s.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return;   // best-effort
        }
        int kept = 0;
        for (Path v : versions) {
            String name = v.getFileName().toString();
            if (name.equals(keepVersion) || kept < keep) {
                kept++;
                continue;
            }
            deleteRecursively(v);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best-effort prune
                }
            });
        } catch (IOException ignore) {
            // best-effort prune
        }
    }
}
