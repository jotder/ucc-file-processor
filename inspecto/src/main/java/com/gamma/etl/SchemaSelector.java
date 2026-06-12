package com.gamma.etl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Selects the schema and output table for an input file in a multi-schema pipeline.
 *
 * <p>Two-pass strategy (in order):
 * <ol>
 *   <li><b>File-pattern fast path</b> — match the file path against each schema's
 *       {@code file_pattern} glob in insertion order (no I/O, first match wins).</li>
 *   <li><b>Column-count probe</b> — open the file, scan up to 200 non-blank lines,
 *       take the maximum column count, look it up in {@code schemaByColCount}.</li>
 * </ol>
 *
 * <p>Schemas whose {@code file_pattern} is blank or absent are skipped in pass 1
 * and are only reachable via the column-count probe in pass 2.
 *
 * <p>The insertion order of {@code schemaByColCount} (a {@link LinkedHashMap})
 * determines match priority in both passes — declare the most specific schemas first.
 */
public final class SchemaSelector {

    /**
     * The result of a successful schema selection.
     *
     * @param schema schema config map (contains {@code raw}, {@code mapping}, etc.)
     * @param table  target DuckDB / output table name
     */
    public record Selection(Map<String, Object> schema, String table) {}

    // ── schema maps (insertion-order iteration is the priority order) ─────────

    private final LinkedHashMap<Integer, Map<String, Object>> schemaByColCount;
    private final LinkedHashMap<Integer, PathMatcher>         patternByColCount;
    private final LinkedHashMap<Integer, String>              tableByColCount;

    /** Delimiter character used for column-count probing. */
    private final char delimiter;
    /** Lines to skip before the column-name row (from {@code csv_settings.skip_header_lines}). */
    private final int skipHeaderLines;

    // ── construction ──────────────────────────────────────────────────────────

    /**
     * Construct a selector from pre-populated schema maps.
     *
     * <p>Typically called by {@link PipelineConfig#load(String)} after parsing the
     * {@code processing.schemas[]} array.
     *
     * @param schemaByColCount  column-count → schema config map (LinkedHashMap, insertion-order)
     * @param patternByColCount column-count → PathMatcher for the fast-path glob (may be absent)
     * @param tableByColCount   column-count → output table name
     * @param delimiter         CSV delimiter for the column-count probe parser
     * @param skipHeaderLines   lines to skip before data in the probe
     */
    public SchemaSelector(
            LinkedHashMap<Integer, Map<String, Object>> schemaByColCount,
            LinkedHashMap<Integer, PathMatcher>         patternByColCount,
            LinkedHashMap<Integer, String>              tableByColCount,
            String delimiter,
            int    skipHeaderLines) {

        this.schemaByColCount  = schemaByColCount;
        this.patternByColCount = patternByColCount;
        this.tableByColCount   = tableByColCount;
        this.skipHeaderLines   = skipHeaderLines;
        this.delimiter         = delimiter.charAt(0);
    }

    /** Returns {@code true} when this selector has at least one schema registered. */
    public boolean hasSchemas() {
        return !schemaByColCount.isEmpty();
    }

    /**
     * A read-only view of every registered schema paired with its target table, in priority
     * (insertion) order. Used by the metadata catalog to enumerate the event tables a
     * multi-schema source emits without re-parsing config; not used on the ingest hot path.
     */
    public List<Selection> entries() {
        List<Selection> out = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Object>> e : schemaByColCount.entrySet()) {
            out.add(new Selection(e.getValue(), tableByColCount.get(e.getKey())));
        }
        return out;
    }

    // ── selection ─────────────────────────────────────────────────────────────

    /**
     * Select a schema for {@code inputFile}.
     *
     * @param inputFile the file about to be processed
     * @return the matching {@link Selection}
     * @throws IllegalStateException if no schema matches
     * @throws IOException           on probe I/O failure
     */
    public Selection select(File inputFile) throws IOException {
        // Pass 1 — file-pattern fast path (no I/O, insertion-order priority)
        for (Map.Entry<Integer, PathMatcher> e : patternByColCount.entrySet()) {
            if (e.getValue().matches(inputFile.toPath())) {
                int key = e.getKey();
                return new Selection(schemaByColCount.get(key), tableByColCount.get(key));
            }
        }

        // Pass 2 — column-count probe
        int probed = probeColumnCount(inputFile);
        Map<String, Object> schema = schemaByColCount.get(probed);
        if (schema != null)
            return new Selection(schema, tableByColCount.get(probed));

        throw new IllegalStateException(
                "No schema matched for " + inputFile.getName() +
                        " (probed " + probed + " column(s); configured: " +
                        schemaByColCount.keySet() + ")");
    }

    // ── column-count probe ────────────────────────────────────────────────────

    /**
     * Open the file (GZip-transparent), skip {@code skipHeaderLines} pre-header
     * lines, then scan up to 200 non-blank lines and return the <em>maximum</em>
     * column count seen.
     *
     * <p>Taking the maximum rather than the first line handles files where:
     * <ul>
     *   <li>A SQL*Plus preamble (ORA-* messages, banner text) precedes data with
     *       fewer tokens than the data rows.</li>
     *   <li>The first data line is shorter due to trailing null columns.</li>
     * </ul>
     */
    private int probeColumnCount(File file) throws IOException {
        int maxCols = 0;
        int scanned = 0;
        try (InputStream rawIs = new FileInputStream(file);
             InputStream is    = file.getName().endsWith(".gz")
                                 ? new GZIPInputStream(rawIs) : rawIs;
             BufferedReader br = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {

            for (int i = 0; i < skipHeaderLines; i++)
                if (br.readLine() == null) return 0;

            String line;
            while ((line = br.readLine()) != null && scanned < 200) {
                if (line.trim().isEmpty()) continue;
                scanned++;
                int cols = countColumns(line);
                if (cols > maxCols) maxCols = cols;
            }
        }
        return maxCols;
    }

    // ── column counting ───────────────────────────────────────────────────────

    /**
     * Count columns by scanning for delimiter characters.
     *
     * <p>Uses a plain character scan rather than the univocity CSV parser, which
     * can crash with {@link ArrayIndexOutOfBoundsException} or
     * {@link NullPointerException} when rows contain thousands of characters
     * (e.g. 537-column CDR files where a single row may exceed 20,000 chars).
     *
     * <p>Quote detection is intentionally omitted here because the probe parser
     * already has it disabled ({@code setQuoteDetectionEnabled(false)}), so a
     * bare character scan produces the same column count as the full parse would.
     *
     * @param line a raw CSV line (non-blank)
     * @return number of fields (delimiter occurrences + 1)
     */
    private int countColumns(String line) {
        int count = 1;
        for (int i = 0, len = line.length(); i < len; i++)
            if (line.charAt(i) == delimiter) count++;
        return count;
    }

    // ── factory helpers (used by PipelineConfig.load) ─────────────────────────

    /**
     * Register one schema entry into the three maps.
     * Used by {@link PipelineConfig} during config loading.
     */
    public static void register(
            LinkedHashMap<Integer, Map<String, Object>> schemaByColCount,
            LinkedHashMap<Integer, PathMatcher>         patternByColCount,
            LinkedHashMap<Integer, String>              tableByColCount,
            int colCount,
            String filePattern,
            Map<String, Object> schemaCfg,
            String table) {

        schemaByColCount.put(colCount, schemaCfg);
        tableByColCount.put(colCount, table);
        if (filePattern != null && !filePattern.isBlank())
            patternByColCount.put(colCount,
                    FileSystems.getDefault().getPathMatcher(filePattern));
    }
}
