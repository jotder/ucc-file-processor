package com.gamma.exchange;

import com.gamma.util.AtomicFiles;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TOON read/write for the Exchange's two flat ledger files ({@code offers.toon}, {@code grants.toon}),
 * each holding a single {@code {<key>: [ … ]}} array of records. Reads tolerate a missing file (empty
 * list); writes are crash-safe ({@link AtomicFiles}). Small, package-private plumbing shared by
 * {@link Exchange}, {@link Offer} and {@link ShareGrant}.
 */
final class Ledger {

    private Ledger() {}

    /** Null-safe string field ({@code null} when absent). */
    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    /** Coerce a JToon-decoded value to a {@code long} (Number or numeric String); 0 when absent/unparseable. */
    static long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignore) {
                return 0L;
            }
        }
        return 0L;
    }

    /** The list of record maps under {@code key} in the TOON file at {@code file}; empty if the file is absent. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> read(Path file, String key) {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            Object decoded = JToon.decode(Files.readString(file, StandardCharsets.UTF_8));
            if (decoded instanceof Map<?, ?> top && top.get(key) instanceof List<?> rows) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object o : rows) if (o instanceof Map<?, ?> r) out.add((Map<String, Object>) r);
                return out;
            }
            return new ArrayList<>();
        } catch (IOException e) {
            throw new UncheckedIOException("reading exchange ledger " + file, e);
        }
    }

    /** Write {@code items} under {@code key} to {@code file}, atomically. */
    static void write(Path file, String key, List<Map<String, Object>> items) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(key, items);
        try {
            AtomicFiles.write(file, JToon.encode(doc).getBytes(StandardCharsets.UTF_8), ".exch-");
        } catch (IOException e) {
            throw new UncheckedIOException("writing exchange ledger " + file, e);
        }
    }
}
