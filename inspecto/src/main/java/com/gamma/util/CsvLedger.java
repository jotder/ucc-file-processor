package com.gamma.util;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.function.Function;

/**
 * A generic <b>append-only, header-bearing CSV ledger</b> — the one implementation of the
 * pattern previously copy-pasted across the Stage-1 batch audit, the Stage-2 enrichment
 * audit, and the job audit: check-exists, write-header-on-first-create, append formatted
 * rows. The row type {@code T} is mapped to its CSV line by an injected codec, so each
 * ledger keeps its exact on-disk column order and per-column quoting — the bytes written
 * are identical to the pre-consolidation writers.
 *
 * <p>{@link #append} and {@link #appendAll} are {@code synchronized} per ledger so
 * concurrent producers append whole rows. Durability is deliberately <em>not</em> this
 * class's job — the fsync'd "did it finish" guarantee lives in
 * {@link com.gamma.etl.CommitLog}, which stays separate.
 *
 * @param <T> the row type; the codec renders one row as one CSV line (no terminator)
 */
public final class CsvLedger<T> {

    private final String path;
    private final String header;
    private final Function<T, String> codec;

    /**
     * @param path   the ledger file (parent directory must exist — ledgers never create it,
     *               matching the original writers' fail-fast behaviour)
     * @param header the header line written when the file is first created
     * @param codec  renders a row as its CSV line; injected so column order/quoting stay
     *               with the row type's owner, not the ledger
     */
    public CsvLedger(String path, String header, Function<T, String> codec) {
        this.path = path;
        this.header = header;
        this.codec = codec;
    }

    /** Append one row (header first when the file does not exist yet). */
    public synchronized void append(T row) {
        appendAll(java.util.List.of(row));
    }

    /** Append rows contiguously under one file-open (header first on a fresh file). */
    public synchronized void appendAll(Iterable<? extends T> rows) {
        boolean exists = new java.io.File(path).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(path, true))) {
            if (!exists) pw.println(header);
            for (T row : rows) pw.println(codec.apply(row));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Make a value safe inside a double-quoted CSV field (the one canonical {@code q()}). */
    public static String q(String v) {
        return v == null ? "" : v.replace('"', '\'');
    }
}
