package com.gamma.etl;

import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Streams a raw CSV (or CSV.GZ) file into a {@code raw_input} VARCHAR staging
 * table in the caller's DuckDB connection.
 *
 * <p>Processing steps (in order):
 * <ol>
 *   <li>Skip fixed pre-header lines ({@code skip_header_lines}).</li>
 *   <li>Optionally read the column-name row ({@code has_header}; default {@code true}).</li>
 *   <li>Adaptively skip junk / echo lines ({@code skip_junk_lines}; {@code -1} = unlimited).</li>
 *   <li>Buffer the last {@code skip_tail_lines} rows so footer lines are silently dropped.</li>
 *   <li>Reject rows with too few columns → write to {@code errors/<basename>_errors.csv}.</li>
 *   <li>Delete the error file if no errors occurred.</li>
 * </ol>
 *
 * <p>Extracted from {@link com.gamma.inspector.SourceProcessor#ingestRawData}.
 */
public final class CsvIngester {

    private static final Logger log = LoggerFactory.getLogger(CsvIngester.class);

    private CsvIngester() {}

    /**
     * Ingest {@code file} into {@code raw_input} in the supplied DuckDB connection.
     *
     * @param file        the CSV or CSV.GZ input file
     * @param conn        worker-local DuckDB connection (must be open)
     * @param schemaConfig the schema config map for this file (contains {@code raw.fields})
     * @param cfg         pipeline configuration
     * @return row counts for the caller to use in quarantine/status decisions
     * @throws Exception on DuckDB errors; {@link IOException} when the file is unreadable
     */
    public static IngestResult ingest(File file, Connection conn,
                                      Map<String, Object> schemaConfig,
                                      PipelineConfig cfg) throws Exception {
        return ingest(file, conn, schemaConfig, cfg, "raw_input");
    }

    /**
     * Ingest {@code file} into the table named {@code targetTable} in the supplied
     * DuckDB connection. Identical to the 4-arg overload but lets a batch member
     * stream into its own per-file staging table.
     */
    @SuppressWarnings("unchecked")
    public static IngestResult ingest(File file, Connection conn,
                                      Map<String, Object> schemaConfig,
                                      PipelineConfig cfg,
                                      String targetTable) throws Exception {
        // ── parse settings from config ────────────────────────────────────────
        int maxJunkLines = cfg.csv().skipJunkLines() < 0 ? Integer.MAX_VALUE : cfg.csv().skipJunkLines();
        int skipTailCols = cfg.csv().skipTailCols();

        // Row filters (parity with the native WHERE injection): rows are *filtered*, not rejected.
        RowFilter rowFilter = RowFilter.from(cfg);

        // ── derive the parse plan from the schema (hoisted out of the row loop) ──
        ParserSpec spec = ParserSpec.fromSchema(schemaConfig);
        List<Map<String, Object>> fields = spec.fields();
        int[] selectorIdx = spec.selectorIdx();
        int maxSelector   = spec.maxSelector();

        // ── prepare error CSV (created lazily — only if errors actually occur) ──
        Path errorFilePath = ParserSpec.errorFile(file, cfg);
        Path errorDir      = errorFilePath.getParent();

        CsvParser parser = buildParser(cfg.csv().delimiter());

        long parsedRows        = 0;
        long errorRows         = 0;
        long junkCandidateRows = 0;
        long ingestStartMs     = System.currentTimeMillis();

        // Opened lazily on the first rejected row — no file is created when there are no errors.
        PrintWriter errOut = null;

        try (InputStream rawIs = new FileInputStream(file);
             // For .gz files: buffer 8 MB of compressed data before the GZIPInputStream
             // so the decompressor reads in large chunks instead of 512-byte syscall bursts.
             InputStream is    = file.getName().endsWith(".gz")
                                 ? new GZIPInputStream(
                                         new BufferedInputStream(rawIs, 8 * 1024 * 1024))
                                 : rawIs;
             // 2 MB char buffer: ~500 refill calls per GB vs ~125,000 with the default 8 KB.
             BufferedReader br = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8), 2 * 1024 * 1024)) {

            // ── skip pre-header lines ─────────────────────────────────────────
            for (int i = 0; i < cfg.csv().skipHeaderLines(); i++) br.readLine();

            // ── optional column-name header row ───────────────────────────────
            // When has_header=false the first data line is treated as a row;
            // junk-scan is still active but the echo-line check is skipped.
            String[] headerTokens = null;
            if (cfg.csv().hasHeader()) {
                String headerLine = br.readLine();
                if (headerLine == null) throw new IOException("Empty file: " + file.getName());
                headerTokens = parser.parseLine(headerLine);
            }

            // ── create staging table ──────────────────────────────────────────
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS \"" + targetTable + "\"");
                StringBuilder ddl = new StringBuilder("CREATE TABLE \"" + targetTable + "\" (");
                for (int i = 0; i < fields.size(); i++) {
                    ddl.append('"').append(fields.get(i).get("name")).append("\" VARCHAR");
                    if (i < fields.size() - 1) ddl.append(", ");
                }
                ddl.append(')');
                stmt.execute(ddl.toString());
            }

            // ── adaptive junk / echo-line detection ───────────────────────────
            String firstDataLine = null;
            if (maxJunkLines > 0) {
                int junkCount = 0;
                String peekLine;
                while (junkCount < maxJunkLines && (peekLine = br.readLine()) != null) {
                    if (peekLine.trim().isEmpty()) continue;
                    junkCandidateRows++;
                    String[] probe = parser.parseLine(peekLine);
                    if (probe != null && probe.length > maxSelector) {
                        boolean isEchoLine = false;
                        if (headerTokens != null && headerTokens.length > 0) {
                            int checkLen   = Math.min(probe.length, headerTokens.length);
                            int matchCount = 0;
                            for (int i = 0; i < checkLen; i++) {
                                String p = probe[i] == null ? "" : probe[i].trim();
                                String h = headerTokens[i] == null ? "" : headerTokens[i].trim();
                                if (p.equalsIgnoreCase(h)) matchCount++;
                            }
                            isEchoLine = checkLen > 0 && (double) matchCount / checkLen >= 0.5;
                        }
                        if (!isEchoLine) {
                            firstDataLine = peekLine;
                            junkCandidateRows--;  // it's data, not junk
                            break;
                        }
                    }
                    junkCount++;
                }
            }

            // ── main appender loop ────────────────────────────────────────────
            DuckDBConnection duckConn = (DuckDBConnection) conn;
            try (DuckDBAppender appender = duckConn.createAppender("", targetTable)) {
                ArrayDeque<Map.Entry<Long, String>> tailBuffer = new ArrayDeque<>();
                long lineNum = 0;
                boolean hasPending = firstDataLine != null;

                while (true) {
                    String rawLine;
                    if (hasPending) {
                        rawLine   = firstDataLine;
                        hasPending = false;
                    } else {
                        rawLine = br.readLine();
                        if (rawLine == null) break;
                    }
                    lineNum++;
                    if (rawLine.trim().isEmpty()) continue;

                    // Tail-buffer gate
                    String line;
                    long   procLineNum;
                    if (cfg.csv().skipTailLines() > 0) {
                        tailBuffer.addLast(Map.entry(lineNum, rawLine));
                        if (tailBuffer.size() <= cfg.csv().skipTailLines()) continue;
                        var head = tailBuffer.removeFirst();
                        line        = head.getValue();
                        procLineNum = head.getKey();
                    } else {
                        line        = rawLine;
                        procLineNum = lineNum;
                    }

                    String[] row = parser.parseLine(line);
                    if (row == null) continue;

                    // Strip phantom trailing columns
                    if (skipTailCols > 0 && row.length > maxSelector + 1)
                        row = Arrays.copyOf(row,
                                Math.max(row.length - skipTailCols, maxSelector + 1));

                    // Row filter (include/exclude): filtered rows are silently dropped — not counted
                    // as parsed or rejected — matching the native WHERE-clause semantics.
                    if (rowFilter.active() && !rowFilter.keep(row)) continue;

                    // Reject rows with insufficient columns
                    if (row.length <= maxSelector) {
                        if (errOut == null) {
                            Files.createDirectories(errorDir);
                            errOut = new PrintWriter(new FileWriter(errorFilePath.toFile()));
                            errOut.println("line_number,reason,raw_line");
                        }
                        String reason = String.format(
                                "Insufficient columns (expected >%d, found %d)",
                                maxSelector, row.length);
                        errOut.printf("%d,\"%s\",\"%s\"%n",
                                procLineNum, reason, line.replace("\"", "'"));
                        errorRows++;
                        continue;
                    }

                    // Append row using the pre-computed selector indices.
                    appender.beginRow();
                    for (int idx : selectorIdx) {
                        appender.append(idx < row.length ? row[idx] : "");
                    }
                    appender.endRow();
                    parsedRows++;

                    if (parsedRows % 10_000_000 == 0) {
                        long elapsedMs  = System.currentTimeMillis() - ingestStartMs;
                        long rowsPerSec = elapsedMs > 0 ? parsedRows * 1000L / elapsedMs : 0;
                        log.info("[INGEST] [{}] {} rows | {} MB file | {} rows/s | {}s elapsed",
                                file.getName(),
                                String.format("%,d", parsedRows),
                                String.format("%.1f", file.length() / 1_048_576.0),
                                String.format("%,d", rowsPerSec),
                                elapsedMs / 1000);
                    }
                }
                // Lines remaining in tailBuffer at EOF are the file footer — silently discarded.
            }
        } finally {
            if (errOut != null) errOut.close();
        }

        return new IngestResult(parsedRows, errorRows, junkCandidateRows);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Build a lenient CsvParser for the given delimiter. */
    public static CsvParser buildParser(String delimiter) {
        CsvParserSettings s = new CsvParserSettings();
        s.getFormat().setDelimiter(delimiter.charAt(0));
        s.setMaxColumns(10_000);
        s.setMaxCharsPerColumn(1_000_000);
        s.setQuoteDetectionEnabled(false);
        s.getFormat().setQuote('"');
        s.getFormat().setQuoteEscape('"');
        return new CsvParser(s);
    }

    private static final java.util.regex.Pattern GZ_SUFFIX  = java.util.regex.Pattern.compile("\\.gz$");
    private static final java.util.regex.Pattern EXT_SUFFIX = java.util.regex.Pattern.compile("\\.[^.]+$");

    /** Strips {@code .gz} then the remaining extension. */
    public static String stripExtensions(String fileName) {
        return EXT_SUFFIX.matcher(GZ_SUFFIX.matcher(fileName).replaceAll(""))
                .replaceAll("");
    }

    /**
     * Row include/exclude filter for the Java parse path — the parity counterpart of
     * {@link DuckDbCsvIngester#filterWhere}. A row is kept when it matches <em>any</em> include
     * pattern (prefix or regex; empty include list ⇒ all included) <em>and</em> matches <em>no</em>
     * exclude pattern. Targets the physical column at {@code filter_target_column} (a missing cell is
     * treated as empty). Regexes use {@code Matcher.find} (contains-semantics), matching DuckDB's
     * {@code regexp_matches}.
     */
    private static final class RowFilter {

        private final int target;
        private final List<String> includePrefixes;
        private final List<String> excludePrefixes;
        /** Matchers are created once and {@code reset()} per row — an ingest is single-threaded,
         *  and a fresh {@code Matcher} allocation per row × pattern measurably adds up on
         *  filter-heavy multi-million-row files. */
        private final java.util.regex.Matcher[] includeM;
        private final java.util.regex.Matcher[] excludeM;

        private RowFilter(int target, List<String> includePrefixes, List<String> includeRegex,
                          List<String> excludePrefixes, List<String> excludeRegex) {
            this.target = target;
            this.includePrefixes = includePrefixes;
            this.excludePrefixes = excludePrefixes;
            this.includeM = compile(includeRegex);
            this.excludeM = compile(excludeRegex);
        }

        static RowFilter from(PipelineConfig cfg) {
            PipelineConfig.CsvSettings c = cfg.csv();
            return new RowFilter(c.filterTargetColumn(),
                    c.includePrefixes(), c.includeRegex(),
                    c.excludePrefixes(), c.excludeRegex());
        }

        private static java.util.regex.Matcher[] compile(List<String> patterns) {
            java.util.regex.Matcher[] out = new java.util.regex.Matcher[patterns.size()];
            for (int i = 0; i < patterns.size(); i++)
                out[i] = java.util.regex.Pattern.compile(patterns.get(i)).matcher("");
            return out;
        }

        boolean active() {
            return !includePrefixes.isEmpty() || includeM.length > 0
                || !excludePrefixes.isEmpty() || excludeM.length > 0;
        }

        boolean keep(String[] row) {
            String v = (target >= 0 && target < row.length && row[target] != null) ? row[target] : "";
            boolean includeActive = !includePrefixes.isEmpty() || includeM.length > 0;
            boolean included = !includeActive || matchesAny(v, includePrefixes, includeM);
            boolean excluded = matchesAny(v, excludePrefixes, excludeM);
            return included && !excluded;
        }

        private static boolean matchesAny(String v, List<String> prefixes,
                                          java.util.regex.Matcher[] matchers) {
            for (String p : prefixes) if (v.startsWith(p)) return true;
            for (java.util.regex.Matcher m : matchers) if (m.reset(v).find()) return true;
            return false;
        }
    }
}
