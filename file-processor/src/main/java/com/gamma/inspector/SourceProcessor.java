package com.gamma.inspector;

import com.gamma.util.DuckDbUtil;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import dev.toonformat.jtoon.JToon;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * ETL entry point for the file-processor pipeline.
 *
 * <p>Reads a {@code .toon} pipeline config, scans the configured inbox directory
 * for CSV / CSV.GZ files, and for each unprocessed file:
 * <ol>
 *   <li>Ingests raw rows into a per-worker in-process DuckDB instance</li>
 *   <li>Applies type casts and mapping rules from the schema config</li>
 *   <li>Writes partitioned output (CSV or Parquet) to the database directory</li>
 *   <li>Optionally registers the output files in a DuckLake PostgreSQL catalog</li>
 *   <li>Appends an audit row to the status CSV</li>
 * </ol>
 *
 * <p>Run via: {@code java -jar file-processor.jar <pipeline.toon>}
 */
public class SourceProcessor {

    // ── result carriers ──

    /**
     * Counts returned from a single-file ingestion pass.
     *
     * @param parsedRows        rows successfully ingested into raw_input
     * @param errorRows         rows rejected in the appender loop (too few columns)
     * @param junkCandidateRows non-blank lines evaluated during junk detection that did
     *                          not qualify as data rows; used to detect wrong-schema files
     *                          where all rows fail column-count before reaching the appender
     */
    record IngestResult(long parsedRows, long errorRows, long junkCandidateRows) {
    }

    /**
     * Output file paths and their sizes in bytes, one entry per partition file.
     */
    record TransformResult(List<String> outputPaths, List<Long> outputSizes) {
        /**
         * Sentinel returned when no output is produced (e.g. on early failure).
         */
        static TransformResult empty() {
            return new TransformResult(List.of(), List.of());
        }
    }

    // ── shared config (loaded once, read-only during processing) ──

    private static Map<String, Object> pipelineConfig;
    private static Map<String, Object> schemaConfig;                       // legacy single-schema
    // Multi-schema: populated from processing.schemas[] array (keyed by column_count)
    private static final Map<Integer, Map<String, Object>> schemaByColCount = new LinkedHashMap<>();
    private static final Map<Integer, PathMatcher> patternByColCount = new LinkedHashMap<>();
    private static final Map<Integer, String> tableByColCount = new LinkedHashMap<>();

    // ── entry point ──

    /**
     * Validates the command-line argument, loads configs, and starts inbox polling.
     *
     * @param args args[0] — path to the pipeline {@code .toon} file
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SourceProcessor <pipeline_config_path>");
            System.exit(1);
        }
        loadConfigs(args[0]);
        pollInbox();
    }

    // ── config loading ──

    /**
     * Parses the pipeline {@code .toon} file, then loads the schema {@code .toon} file
     * referenced inside it. Both maps are stored in static fields for thread-safe
     * read-only access during processing.
     *
     * @param pipelineConfigPath filesystem path to the pipeline config file
     * @throws FileNotFoundException if either config file is missing
     */
    private static void loadConfigs(String pipelineConfigPath) throws IOException {
        File configFile = new File(pipelineConfigPath);
        if (!configFile.exists())
            throw new FileNotFoundException("Pipeline config not found: " + pipelineConfigPath);
        pipelineConfig = (Map<String, Object>)
                JToon.decode(Files.readString(configFile.toPath(), StandardCharsets.UTF_8));

        validateDirs(pipelineConfigPath);

        Map<String, Object> processing = (Map<String, Object>) pipelineConfig.get("processing");

        List<Map<String, Object>> schemaDefs = (List<Map<String, Object>>) processing.get("schemas");
        if (schemaDefs != null && !schemaDefs.isEmpty()) {
            for (Map<String, Object> entry : schemaDefs) {
                int colCount = Integer.parseInt(String.valueOf(entry.get("column_count")));
                String schemaPath = (String) entry.get("schema_file");
                String table = (String) entry.get("table");
                String filePattern = (String) entry.get("file_pattern");
                File schemaFile = new File(schemaPath);
                if (!schemaFile.exists())
                    throw new FileNotFoundException("Schema file not found: " + schemaPath);

                Map<String, Object> cfg = (Map<String, Object>)
                        JToon.decode(Files.readString(schemaFile.toPath(), StandardCharsets.UTF_8));
                schemaByColCount.put(colCount, cfg);
                tableByColCount.put(colCount, table);
                if (filePattern != null && !filePattern.isBlank())
                    patternByColCount.put(colCount,
                            FileSystems.getDefault().getPathMatcher(filePattern));
            }
            System.out.printf("[CONFIG] Loaded %d schema(s): col counts %s%n",
                    schemaDefs.size(),
                    schemaByColCount.keySet().stream().map(String::valueOf)
                            .collect(Collectors.joining(", ")));
        } else {
            // Legacy single-schema path
            String schemaPath = (String) processing.get("schema_file");
            File schemaFile = new File(schemaPath);
            if (!schemaFile.exists())
                throw new FileNotFoundException("Schema file not found: " + schemaPath);
            schemaConfig = (Map<String, Object>)
                    JToon.decode(Files.readString(schemaFile.toPath(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Validates that all configured directories ({@code database}, {@code backup},
     * {@code temp}, {@code errors}, {@code quarantine}) lie outside the poll directory.
     * Mixing them with the inbox causes the poll walk to pick up non-input files and
     * makes it harder to reason about the directory layout.
     *
     * @throws IllegalArgumentException if any dir is nested inside {@code dirs.poll}
     */
    private static void validateDirs(String configPath) {
        Map<String, Object> dirs = (Map<String, Object>) pipelineConfig.get("dirs");
        Path poll = Paths.get((String) dirs.get("poll")).toAbsolutePath().normalize();

        String[] managed = {"database", "backup", "temp", "errors", "quarantine", "markers"};
        for (String key : managed) {
            Object val = dirs.get(key);
            if (val == null) continue;
            Path dir = Paths.get(val.toString()).toAbsolutePath().normalize();
            if (dir.startsWith(poll))
                throw new IllegalArgumentException(String.format(
                        "Config error in %s: dirs.%s (%s) must be outside the poll directory (%s)",
                        configPath, key, dir, poll));
        }
    }

    // ── inbox polling ──

    /**
     * Recursively walks the poll directory, matches files against the configured
     * glob pattern, and submits each match to a fixed thread-pool for parallel
     * processing. The {@code errors/} sub-directory is excluded from the scan to
     * prevent error CSV files from being picked up as input.
     */
    private static void pollInbox() throws Exception {
        Map<String, Object> dirs = (Map<String, Object>) pipelineConfig.get("dirs");
        Map<String, Object> processing = (Map<String, Object>) pipelineConfig.get("processing");

        String pollDir = (String) dirs.get("poll");
        int threads = Integer.parseInt(String.valueOf(processing.getOrDefault("threads", 4)));
        String pattern = (String) processing.getOrDefault("file_pattern", "glob:**/*.{csv,csv.gz}");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher(pattern);

        Path root = Paths.get(pollDir).toAbsolutePath();
        Path errorsDir = Paths.get((String) dirs.getOrDefault("errors", pollDir + "/errors")).toAbsolutePath();
        Path quarantineDir = Paths.get((String) dirs.getOrDefault("quarantine", pollDir + "/quarantine")).toAbsolutePath();
        if (!Files.exists(root)) Files.createDirectories(root);

        cleanupStaleMarkers();
        System.out.println("Polling " + root.toAbsolutePath() + " with " + threads + " thread(s)...");

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(errorsDir))      // skip error CSVs
                    .filter(p -> !p.startsWith(quarantineDir))  // skip quarantined files
                    .filter(matcher::matches)
                    .forEach(p -> futures.add(executor.submit(() -> processFile(p.toFile()))));
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);
    }

    // ── per-file worker ──

    /**
     * Full processing lifecycle for one input file: duplicate check → ingest →
     * transform → DuckLake registration → marker file creation → status update.
     * Errors are caught, logged, and written to the status CSV without crashing
     * other workers.
     *
     * @param inputFile the CSV or CSV.GZ file to process
     */
    private static void processFile(File inputFile) {
        LocalDateTime startTime = LocalDateTime.now();
        String status = "SUCCESS";
        String errorMsg = "";
        IngestResult ingestResult = new IngestResult(0, 0, 0);
        TransformResult transformResult = TransformResult.empty();

        try {
            if (isAlreadyProcessed(inputFile)) {
                System.out.println("Skipping already processed: " + inputFile.getName());
                return;
            }

            System.out.printf("[%s] Processing: %s (%.1f MB)%n", startTime.format(DuckDbUtil.DT_FMT),
                    inputFile.getName(), inputFile.length() / 1_048_576.0);

            // Select the right schema for this file (multi-schema dispatch), or fall back
            // to the single legacy schemaConfig when schemas[] was not configured.
//            @SuppressWarnings("unchecked")
            final Map<String, Object> selectedSchema;
            final String selectedTable;
            if (!schemaByColCount.isEmpty()) {
                var sel = selectSchema(inputFile);
                selectedSchema = sel.getKey();
                selectedTable = sel.getValue();
                System.out.printf("[%s] [%s] Schema: %s → table: %s%n", ts(), inputFile.getName(),
                        ((Map<String, Object>) selectedSchema.get("raw")).get("name"), selectedTable);
            } else {
                selectedSchema = schemaConfig;
                selectedTable = null;
            }

            // Each worker gets its own temp-file DuckDB to avoid native-library global-state conflicts when multiple
            // threads run concurrently.
            CsvParser parser = createParser();
            File tempDb = DuckDbUtil.tempDbFile("duckdb_worker_");
            try (Connection conn = DriverManager.getConnection(DuckDbUtil.jdbcUrl(tempDb))) {

                // Unreadable file (corrupt, bad GZip, wrong encoding) → quarantine immediately.
                System.out.printf("[%s] [%s] Ingest: starting...%n", ts(), inputFile.getName());
                long ingestStart = System.currentTimeMillis();
                try {
                    ingestResult = ingestRawData(inputFile, conn, parser, selectedSchema);
                    System.out.printf("[%s] [%s] Ingest: done — %,d rows, %,d errors (%,d ms)%n",
                            ts(), inputFile.getName(), ingestResult.parsedRows(), ingestResult.errorRows(),
                            System.currentTimeMillis() - ingestStart);
                } catch (IOException e) {
                    quarantineFile(inputFile, "unreadable", false);
                    status = "QUARANTINED_UNREADABLE";
                    errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    return;  // skip transformation and marker; finally still runs status update
                }

                // 0 good rows but content was seen → likely a wrong-schema file. This covers two cases:
                //   (a) rows reached the appender but failed column-count → errorRows > 0
                //   (b) rows were rejected in junk detection (too few cols to be data) → junkCandidateRows > 0
                if (ingestResult.parsedRows() == 0
                        && (ingestResult.errorRows() > 0 || ingestResult.junkCandidateRows() > 0)) {
                    quarantineFile(inputFile, "field_mismatch", ingestResult.errorRows() > 0);
                    status = "QUARANTINED_MISMATCH";
                    errorMsg = ingestResult.errorRows() > 0
                            ? String.format("0 valid rows; %d row(s) rejected (field mismatch)",
                            ingestResult.errorRows())
                            : String.format("0 valid rows; %d content line(s) failed column-count in junk scan",
                            ingestResult.junkCandidateRows());
                    return;  // skip transformation and marker; finally still runs status update
                }

                System.out.printf("[%s] [%s] Transform: starting...%n", ts(), inputFile.getName());
                long xformStart = System.currentTimeMillis();
                transformResult = executeTransformation(inputFile, conn, selectedSchema, selectedTable);
                long totalBytes = transformResult.outputSizes().stream().mapToLong(Long::longValue).sum();
                System.out.printf("[%s] [%s] Transform: done — %d file(s), %.1f MB output (%,d ms)%n",
                        ts(), inputFile.getName(), transformResult.outputPaths().size(),
                        totalBytes / 1_048_576.0, System.currentTimeMillis() - xformStart);
            } finally {
                DuckDbUtil.deleteTempDb(tempDb);
            }

            registerInDuckLake(transformResult.outputPaths(), selectedTable);
            createMarkerFile(inputFile);
            backupFile(inputFile);

        } catch (Exception e) {
            status = "FAILED";
            errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            e.printStackTrace();
        } finally {
            LocalDateTime endTime = LocalDateTime.now();
            long durationMs = Duration.between(startTime, endTime).toMillis();
            System.out.printf("[%s] Done: %s | status=%s | parsed=%d | errors=%d | duration=%dms%n",
                    endTime.format(DuckDbUtil.DT_FMT), inputFile.getName(), status,
                    ingestResult.parsedRows(), ingestResult.errorRows(), durationMs);
            updateStatus(inputFile.getName(), status, ingestResult, transformResult,
                    startTime, endTime, durationMs, errorMsg);
        }
    }

    // ── helpers ──

    /**
     * Builds a {@link CsvParser} configured from {@code csv_settings} in the pipeline
     * config. Quote detection is disabled so embedded quotes in raw data do not
     * confuse the parser.
     */
    private static CsvParser createParser() {
        Map<String, Object> csvSettings =
                (Map<String, Object>) ((Map<String, Object>) pipelineConfig.get("processing")).get("csv_settings");
        CsvParserSettings settings = new CsvParserSettings();
        String delim = (String) csvSettings.getOrDefault("delimiter", ",");
        settings.getFormat().setDelimiter(delim.charAt(0));
        settings.setMaxColumns(10_000);
        settings.setMaxCharsPerColumn(1_000_000);
        settings.setQuoteDetectionEnabled(false);
        settings.getFormat().setQuote('\0');
        return new CsvParser(settings);
    }

    /**
     * Returns {@code true} if a marker file already exists in {@code dirs.markers} for
     * the given input file, indicating it was processed in a previous run.
     * No-ops when duplicate_check is disabled.
     */
    private static boolean isAlreadyProcessed(File file) {
        Map<String, Object> dupCheck =
                (Map<String, Object>) ((Map<String, Object>) pipelineConfig.get("processing")).get("duplicate_check");
        if (!(boolean) dupCheck.get("enabled")) return false;
        return Files.exists(getMarkerPath(file));
    }

    // ── schema selection (multi-schema) ──

    /**
     * Selects the schema and output table for the given file.
     *
     * <p>Strategy (in order):
     * <ol>
     *   <li>Match the file path against each schema's {@code file_pattern} glob (fast, zero I/O)</li>
     *   <li>Probe the column count from the file header and look up by {@code column_count}</li>
     * </ol>
     *
     * @return a Map.Entry where key = schema config map, value = target table name
     * @throws IllegalStateException if no schema matches
     */
    private static Map.Entry<Map<String, Object>, String> selectSchema(File inputFile)
            throws IOException {
        // 1. File-pattern match (fast path — no I/O)
        for (Map.Entry<Integer, PathMatcher> e : patternByColCount.entrySet()) {
            if (e.getValue().matches(inputFile.toPath())) {
                int key = e.getKey();
                return Map.entry(schemaByColCount.get(key), tableByColCount.get(key));
            }
        }
        // 2. Column-count probe (open file, read one line)
        int probed = probeColumnCount(inputFile);
        Map<String, Object> schema = schemaByColCount.get(probed);
        if (schema != null)
            return Map.entry(schema, tableByColCount.get(probed));
        throw new IllegalStateException(
                "No schema matched for " + inputFile.getName() +
                        " (probed " + probed + " column(s); configured: " + schemaByColCount.keySet() + ")");
    }

    /**
     * Opens the file (GZip-transparent), skips {@code skip_header_lines} pre-header lines,
     * then scans up to 200 non-blank lines and returns the <em>maximum</em> column count seen.
     *
     * <p>Taking the maximum rather than just the first non-blank line handles files where:
     * <ul>
     *   <li>The header spans multiple physical lines (SQL*Plus wraps long column-name rows)</li>
     *   <li>A SQL*Plus preamble (ORA-* messages, banner text) precedes the data</li>
     * </ul>
     * The first genuine data line will always have the highest column count in the sample.
     */
    private static int probeColumnCount(File file) throws IOException {
        Map<String, Object> csvSettings = (Map<String, Object>)
                ((Map<String, Object>) pipelineConfig.get("processing")).get("csv_settings");
        int skipHeader = Integer.parseInt(
                String.valueOf(csvSettings.getOrDefault("skip_header_lines", 0)));
        CsvParser probe = createParser();
        int maxCols = 0;
        int scanned = 0;
        try (InputStream rawIs = new FileInputStream(file);
             InputStream is = file.getName().endsWith(".gz") ? new GZIPInputStream(rawIs) : rawIs;
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            for (int i = 0; i < skipHeader; i++)
                if (br.readLine() == null) return 0;
            String line;
            while ((line = br.readLine()) != null && scanned < 200) {
                if (line.trim().isEmpty()) continue;
                scanned++;
                String[] tokens = probe.parseLine(line);
                if (tokens != null && tokens.length > maxCols)
                    maxCols = tokens.length;
            }
        }
        return maxCols;
    }

    // ── raw ingestion ──

    /**
     * Streams the raw file into the worker DuckDB as a {@code raw_input} VARCHAR table.
     *
     * <p>Processing steps:
     * <ol>
     *   <li>Skip fixed header lines, then read the column-name row</li>
     *   <li>Adaptively skip junk / echo lines: up to {@code skip_junk_lines} lines,
     *       or unlimited when {@code skip_junk_lines: -1} (fully dynamic mode)</li>
     *   <li>Buffer the last {@code skip_tail_lines} rows so footer lines are silently dropped</li>
     *   <li>Write malformed rows (too few columns) to {@code errors/<basename>_errors.csv}</li>
     *   <li>Delete the error file if no errors occurred</li>
     * </ol>
     *
     * @return counts of successfully ingested and rejected rows
     */
    private static IngestResult ingestRawData(File file, Connection conn, CsvParser parser,
                                              Map<String, Object> schemaConfig) throws Exception {

        // ── 1. Read CSV settings from pipeline config ──
        Map<String, Object> processing = (Map<String, Object>) pipelineConfig.get("processing");
        Map<String, Object> csvSettings = (Map<String, Object>) processing.get("csv_settings");

        // skip_junk_lines: fixed count of post-header noise lines to skip before data begins.
        // -1 = unlimited (fully dynamic): scan forward until the first valid data row,
        //      however far down it sits — handles SQL*Plus dumps where preamble length
        //      varies (e.g. ORA-28002 password-expiry warnings add extra lines).
        int maxJunkLines = Integer.parseInt(String.valueOf(csvSettings.getOrDefault("skip_junk_lines", 0)));
        if (maxJunkLines < 0) maxJunkLines = Integer.MAX_VALUE;

        // skip_header_lines: fixed lines before the column-name row (e.g. report titles).
        int skipHeader = Integer.parseInt(String.valueOf(csvSettings.getOrDefault("skip_header_lines", 0)));

        // skip_tail_lines: trailing footer lines at EOF to silently discard (e.g. SQL*Plus "N rows selected" summary line).
        int skipTailLines = Integer.parseInt(String.valueOf(csvSettings.getOrDefault("skip_tail_lines", 0)));

        // skip_tail_columns: trailing columns to strip from every row before validation
        //                    (e.g. an extra trailing delimiter that adds a phantom empty column).
        int skipTailCols = Integer.parseInt(String.valueOf(csvSettings.getOrDefault("skip_tail_columns", 0)));

        // ── 2. Derive the highest column index required by the schema ──
        // maxSelector is the largest zero-based column index referenced by any field.
        // A row must have at least maxSelector+1 tokens to be considered valid.
        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) schemaConfig.get("raw")).get("fields");
        int maxSelector = fields.stream()
                .mapToInt(f -> Integer.parseInt(String.valueOf(f.get("selector"))))
                .max().orElse(0);

        // ── 3. Prepare the per-file error CSV ──
        // Rows that fail column-count validation are written here instead of being silently dropped.
        // The file is deleted at the end if no errors occurred, keeping the errors dir clean.
        Map<String, Object> dirsMap = (Map<String, Object>) pipelineConfig.get("dirs");
        String pollDir = (String) dirsMap.get("poll");
        String baseName = stripExtensions(file.getName());
        Path errorDir = Paths.get((String) dirsMap.getOrDefault("errors", pollDir + "/errors")).toAbsolutePath();
        Files.createDirectories(errorDir);
        Path errorFilePath = errorDir.resolve(baseName + "_errors.csv");

        long parsedRows = 0;
        long errorRows = 0;
        long junkCandidateRows = 0;  // non-blank lines evaluated in the junk scan that never became data rows

        try (InputStream rawIs = new FileInputStream(file);
             // Transparent GZip decompression — plain CSV and .csv.gz are handled identically below.
             InputStream is = file.getName().endsWith(".gz") ? new GZIPInputStream(rawIs) : rawIs;
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
             PrintWriter errOut = new PrintWriter(new FileWriter(errorFilePath.toFile()))) {

            errOut.println("line_number,reason,raw_line");

            // ── 4. Skip fixed pre-header lines ──
            // These are report-title or metadata lines that appear before the column-name row.
            for (int i = 0; i < skipHeader; i++) br.readLine();

            // ── 5. Read the column-name header row (when present) ──
            // has_header defaults to true. Set false for header-less files: skips consuming
            // the first data row as a header, which would silently drop it and cause
            // single-row files to quarantine with "0 valid rows".
            // When false, headerTokens stays null; the echo-line check in step 7 is
            // already guarded by (headerTokens != null) so junk-scanning still works.
            boolean hasHeader = Boolean.parseBoolean(
                    String.valueOf(csvSettings.getOrDefault("has_header", "true")));
            String[] headerTokens = null;
            if (hasHeader) {
                String headerLine = br.readLine();
                if (headerLine == null) throw new IOException("Empty file: " + file.getName());
                headerTokens = parser.parseLine(headerLine);
            }

            // ── 6. Create the raw_input staging table in DuckDB ──
            // All columns are VARCHAR at this stage; type casting happens in executeTransformation.
            // The table is dropped first so the same worker connection can be reused safely.
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS raw_input");
                StringBuilder ddl = new StringBuilder("CREATE TABLE raw_input (");
                for (int i = 0; i < fields.size(); i++) {
                    ddl.append('"').append(fields.get(i).get("name")).append("\" VARCHAR");
                    if (i < fields.size() - 1) ddl.append(", ");
                }
                ddl.append(')');
                stmt.execute(ddl.toString());
            }

            // ── 7. Adaptive junk / echo-line detection ──
            // After the column-name header, SQL*Plus and similar tools sometimes emit:
            //   (a) a repeat / echo of the header line (query-echo mode)
            //   (b) a separator line of dashes
            //   (c) extra blank lines
            // We peek ahead up to maxJunkLines non-blank lines, discarding any that:
            //   • have too few columns to be a data row (≤ maxSelector tokens), OR
            //   • look like a re-echo of the header (≥ 50% of tokens match case-insensitively).
            // The first line that passes both checks is saved as firstDataLine and fed into
            // the main appender loop without being read again.
            // junkCandidateRows counts non-blank lines consumed here that were NOT data;
            // this lets the caller flag wrong-schema files where every row fails the check.

            String firstDataLine = null;
            if (maxJunkLines > 0) {
                int junkCount = 0;
                String peekLine;
                while (junkCount < maxJunkLines && (peekLine = br.readLine()) != null) {
                    if (peekLine.trim().isEmpty()) continue;
                    junkCandidateRows++;   // tentatively count this non-blank line as junk
                    String[] probe = parser.parseLine(peekLine);
                    if (probe != null && probe.length > maxSelector) {
                        boolean isEchoLine = false;
                        if (headerTokens != null && headerTokens.length > 0) {
                            int checkLen = Math.min(probe.length, headerTokens.length);
                            int matchCount = 0;
                            for (int i = 0; i < checkLen; i++) {
                                String p = probe[i] == null ? "" : probe[i].trim();
                                String h = headerTokens[i] == null ? "" : headerTokens[i].trim();
                                if (p.equalsIgnoreCase(h)) matchCount++;
                            }
                            isEchoLine = checkLen > 0 && (double) matchCount / checkLen >= 0.5;
                        }
                        if (!isEchoLine) {
                            // This is a real data row — hand it off to the appender loop.
                            firstDataLine = peekLine;
                            junkCandidateRows--;   // un-count: it turned out to be data, not junk
                            break;
                        }
                    }
                    junkCount++;
                }
            }

            // ── 8. Main appender loop — stream rows into DuckDB ──
            DuckDBConnection duckConn = (DuckDBConnection) conn;
            try (DuckDBAppender appender = duckConn.createAppender("", "raw_input")) {

                // Sliding tail buffer: holds the last skipTailLines non-blank lines.
                // We only release a line for processing once the buffer overflows, so
                // the final skipTailLines lines are never passed to the appender —
                // they are the SQL*Plus footer (row-count line, blank separator, etc.).
                ArrayDeque<Map.Entry<Long, String>> tailBuffer = new ArrayDeque<>();
                long lineNum = 0;
                String rawLine;

                // firstDataLine from the junk-scan is injected as the very first iteration
                // so it goes through the same tail-buffer and validation logic as all other rows.
                boolean hasPending = firstDataLine != null;

                while (true) {
                    if (hasPending) {
                        rawLine = firstDataLine;
                        hasPending = false;
                    } else {
                        rawLine = br.readLine();
                        if (rawLine == null) break;
                    }
                    lineNum++;
                    if (rawLine.trim().isEmpty()) continue;

                    // Tail-buffer gate: enqueue the incoming line; only dequeue (and process)
                    // once the buffer has grown past skipTailLines, ensuring the last N lines are never written to DuckDB.
                    String line;
                    long procLineNum;
                    if (skipTailLines > 0) {
                        tailBuffer.addLast(Map.entry(lineNum, rawLine));
                        if (tailBuffer.size() <= skipTailLines) continue;  // buffer not full yet
                        var head = tailBuffer.removeFirst();
                        line = head.getValue();
                        procLineNum = head.getKey();
                    } else {
                        line = rawLine;
                        procLineNum = lineNum;
                    }

                    String[] row = parser.parseLine(line);
                    if (row == null) continue;

                    // Strip trailing phantom columns produced by a dangling delimiter
                    // (e.g. every row ends with "," giving an extra empty token at the end).
                    if (skipTailCols > 0 && row.length > maxSelector + 1)
                        row = Arrays.copyOf(row, Math.max(row.length - skipTailCols, maxSelector + 1));

                    // Reject rows that still don't have enough columns after trimming.
                    // These are written to the error CSV for investigation rather than silently dropped.
                    if (row.length <= maxSelector) {
                        String reason = String.format("Insufficient columns (expected >%d, found %d)", maxSelector, row.length);
                        errOut.printf("%d,\"%s\",\"%s\"%n",
                                procLineNum, reason, line.replace("\"", "'"));
                        errorRows++;
                        continue;
                    }

                    // Write the row into DuckDB using the schema-defined column selectors.
                    // selector is a zero-based column index; missing columns default to empty string.
                    appender.beginRow();
                    for (Map<String, Object> f : fields) {
                        int idx = Integer.parseInt(String.valueOf(f.get("selector")));
                        appender.append(idx < row.length ? row[idx] : "");
                    }
                    appender.endRow();
                    parsedRows++;

                    // Progress heartbeat every 1M rows — useful for large files (500K+ rows)
                    // where the appender loop is the dominant wall-clock contributor.
                    if (parsedRows % 1_000_000 == 0)
                        System.out.printf("[%s] [%s] Ingest:   %,d rows loaded...%n", ts(), file.getName(), parsedRows);
                }
                // Lines still in tailBuffer at EOF are the file footer — silently discarded.
            }
        }

        // ── 9. Clean up error file if unused ──
        // Avoids leaving empty error CSVs that would clutter the errors/ directory.
        if (errorRows == 0) Files.deleteIfExists(errorFilePath);

        return new IngestResult(parsedRows, errorRows, junkCandidateRows);
    }

    // ── transformation & output ──

    /**
     * Applies mapping rules and type casts to {@code raw_input}, writes partitioned
     * output to the database directory, and returns the final file paths and sizes.
     *
     * <p>A two-stage write strategy is used to avoid DuckDB's AVX2 page-fault crash on
     * Windows: data is first materialised into a {@code transformed} table, then
     * {@code COPY TO} writes to a UUID-tagged staging directory. Each staged file is
     * renamed to {@code <source_basename>_out.<ext>} in the final partition directory.
     *
     * @param inputFile original source file (used to derive the output filename)
     * @param conn      worker-local DuckDB connection containing {@code raw_input}
     * @return paths and byte-sizes of all output files written
     */
    private static TransformResult executeTransformation(File inputFile, Connection conn,
                                                         Map<String, Object> schemaConfig,
                                                         String tableName) throws Exception {
        Map<String, Object> processing = (Map<String, Object>) pipelineConfig.get("processing");
        Map<String, Object> csvSettings = (Map<String, Object>) processing.get("csv_settings");
        List<String> dateFormats = (List<String>) csvSettings.get("date_formats");
        List<String> tsFormats = (List<String>) csvSettings.get("timestamp_formats");

        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) schemaConfig.get("raw")).get("fields");
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (Map<String, Object> f : fields)
            fieldTypes.put((String) f.get("name"), (String) f.get("type"));

        List<Map<String, String>> rules =
                (List<Map<String, String>>) ((Map<String, Object>) schemaConfig.get("mapping")).get("rules");
        String partitionKey = (String) schemaConfig.get("partitionKey");

        // Resolve output format (CSV or PARQUET) and compression (PARQUET only)
        Map<String, Object> outputCfg = (Map<String, Object>) pipelineConfig.get("output");
        String format = outputCfg != null
                ? String.valueOf(outputCfg.getOrDefault("format", "CSV")).toUpperCase()
                : "CSV";
        boolean isParquet = "PARQUET".equals(format);
        String compression = isParquet ? (String) outputCfg.getOrDefault("compression", "snappy") : null;

        // Output filename: <source_basename>_out.csv|.parquet
        String baseName = stripExtensions(inputFile.getName());
        String ext = isParquet ? ".parquet" : ".csv";
        String outputFileName = baseName + "_out" + ext;

        // Build the typed SELECT
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < rules.size(); i++) {
            Map<String, String> rule = rules.get(i);
            String source = rule.get("sourceExpression");
            String target = rule.get("targetColumn");
            String transformType = rule.getOrDefault("transformType", "DIRECT");

            if ("CONCAT_DT".equals(transformType)) {
                // Two source columns separated by '|': date-part | time-part
                // Concatenate with a space, then parse as TIMESTAMP using configured formats.
                String[] parts = source.split("\\|", 2);
                String dateCol = "raw_input.\"" + parts[0] + '"';
                String timeCol = "raw_input.\"" + parts[1] + '"';
                appendCoalesce(select, dateCol + " || ' ' || " + timeCol, tsFormats, "TIMESTAMP");
            } else {
                String col = "raw_input.\"" + source + '"';
                String type = fieldTypes.getOrDefault(source, "VARCHAR");
                switch (type) {
                    case "TIMESTAMP" -> appendCoalesce(select, col, tsFormats, "TIMESTAMP");
                    case "DATE" -> appendCoalesce(select, col, dateFormats, "DATE");
                    case "DOUBLE" -> select.append("TRY_CAST(").append(col).append(" AS DOUBLE)");
                    default -> select.append(col);
                }
            }
            select.append(" AS \"").append(target).append('"');
            if (i < rules.size() - 1) select.append(", ");
        }

        // Partition columns derived from the partition key.
        // buildPartitionExpr resolves the raw-input expression for partitionKey by scanning
        // the mapping rules — handles both DIRECT and CONCAT_DT sources correctly.
        if (partitionKey != null && !partitionKey.isEmpty()) {
            String castExpr = buildPartitionExpr(partitionKey, rules, fieldTypes, dateFormats, tsFormats);
            select.append(", YEAR(").append(castExpr).append(")::VARCHAR AS year");
            select.append(", LPAD(MONTH(").append(castExpr).append(")::VARCHAR, 2, '0') AS month");
            select.append(", LPAD(DAY(").append(castExpr).append(")::VARCHAR, 2, '0') AS day");
        } else {
            select.append(", '1900' AS year, '01' AS month, '01' AS day");
        }
        select.append(" FROM raw_input");

        String baseDbDir = (String) ((Map<String, Object>) pipelineConfig.get("dirs")).get("database");
        // When a tableName is given (multi-schema), each table lands in its own subdirectory.
        String databaseDir = (tableName != null && !tableName.isBlank())
                ? Paths.get(baseDbDir, tableName).toString()
                : baseDbDir;
        new File(databaseDir).mkdirs();

        // Unique staging subdirectory per worker — avoids concurrent partition conflicts
        // and sidesteps the DuckDB FILENAME_PATTERN AVX2 crash on Windows.
        String workerTag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path stagingPath = Paths.get(databaseDir, ".staging", workerTag);
        Files.createDirectories(stagingPath);
        String stagingDir = stagingPath.toString().replace("\\", "/");

        List<String> outputPaths = new ArrayList<>();
        List<Long> outputSizes = new ArrayList<>();

        try (Statement stmt = conn.createStatement()) {
            // Materialize first to avoid the DuckDB native memcpy page-fault triggered
            // when a combined COPY(%s) TO hits an AVX2 page boundary on large files.
            System.out.printf("[%s] [%s] Transform: materializing rows into DuckDB...%n", ts(), inputFile.getName());
            long matStart = System.currentTimeMillis();
            stmt.execute("CREATE TABLE transformed AS " + select);
            System.out.printf("[%s] [%s] Transform: materialized (%,d ms), writing partitions...%n",
                    ts(), inputFile.getName(), System.currentTimeMillis() - matStart);

            // Build COPY TO option string; compression only for PARQUET
            StringBuilder copyOpts = new StringBuilder("FORMAT ").append(format)
                    .append(", PARTITION_BY (year, month, day), OVERWRITE_OR_IGNORE 1");
            if (isParquet && compression != null && !compression.isBlank())
                copyOpts.append(", COMPRESSION ").append(compression);

            long copyStart = System.currentTimeMillis();
            stmt.execute(String.format("COPY transformed TO '%s' (%s)", stagingDir, copyOpts));
            System.out.printf("[%s] [%s] Transform: partitions written (%,d ms), moving to final location...%n",
                    ts(), inputFile.getName(), System.currentTimeMillis() - copyStart);

            // Rename each staged file to the final partition directory using the
            // source-file-based name so the output is self-describing.
            try (Stream<Path> staged = Files.walk(stagingPath)) {
                staged.filter(Files::isRegularFile).forEach(src -> {
                    Path rel = stagingPath.relativize(src);
                    Path dst = Paths.get(databaseDir).resolve(rel).resolveSibling(outputFileName);
                    try {
                        Files.createDirectories(dst.getParent());
                        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                        outputPaths.add(dst.toString());
                        outputSizes.add(Files.size(dst));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }

            // Clean up empty staging tree
            try (Stream<Path> cleanup = Files.walk(stagingPath)) {
                cleanup.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }

        return new TransformResult(outputPaths, outputSizes);
    }

    // ── DuckLake registration (optional) ─────────────────────────────────────

    /**
     * Inserts newly written Parquet files into the DuckLake catalog.
     *
     * <p>Skipped entirely when {@code output.ducklake.enabled} is {@code false} in the
     * pipeline config. Any connectivity or SQL failure is caught and printed to
     * {@code stderr} — it is intentionally non-fatal so ETL success is not affected
     * by catalog availability.
     *
     * @param outputPaths absolute paths of the Parquet files to register
     */
    private static void registerInDuckLake(List<String> outputPaths, String tableName) {
        if (outputPaths.isEmpty()) return;
        Map<String, Object> outputCfg = (Map<String, Object>) pipelineConfig.get("output");
        if (outputCfg == null) return;
        Map<String, Object> dlCfg = (Map<String, Object>) outputCfg.get("ducklake");
        if (dlCfg == null) return;
        if (!Boolean.parseBoolean(String.valueOf(dlCfg.getOrDefault("enabled", false)))) return;

        String catalogUrl = (String) dlCfg.get("catalog_url");
        String dataPath = (String) dlCfg.get("data_path");
        String schema = (String) dlCfg.getOrDefault("schema", "main");
        String table = (tableName != null) ? tableName : (String) dlCfg.get("table");

        System.out.printf("DuckLake: registering %d file(s) into %s.%s ...%n",
                outputPaths.size(), schema, table);
        try {
            File lakeDb = DuckDbUtil.tempDbFile("duckdb_lake_");
            try (Connection conn = DriverManager.getConnection(DuckDbUtil.jdbcUrl(lakeDb));
                 Statement stmt = conn.createStatement()) {

                stmt.execute("INSTALL ducklake FROM core");
                stmt.execute("LOAD ducklake");
                stmt.execute(String.format(
                        "ATTACH 'ducklake:%s' AS lake (DATA_PATH '%s')",
                        catalogUrl, dataPath.replace("\\", "/")));
                stmt.execute("CREATE SCHEMA IF NOT EXISTS lake.\"" + schema + '"');

                String firstPath = outputPaths.get(0).replace("\\", "/");
                stmt.execute(String.format(
                        "CREATE TABLE IF NOT EXISTS lake.\"%s\".\"%s\" AS" +
                                " SELECT * FROM read_parquet('%s') LIMIT 0",
                        schema, table, firstPath));

                String pathList = outputPaths.stream()
                        .map(p -> '\'' + p.replace("\\", "/") + '\'')
                        .collect(Collectors.joining(", ", "[", "]"));
                stmt.execute(String.format(
                        "INSERT INTO lake.\"%s\".\"%s\" SELECT * FROM read_parquet(%s)",
                        schema, table, pathList));

                System.out.printf("DuckLake: OK — %d file(s) registered in %s.%s%n",
                        outputPaths.size(), schema, table);
            } finally {
                DuckDbUtil.deleteTempDb(lakeDb);
            }
        } catch (Exception e) {
            System.err.println("DuckLake registration failed (non-fatal): " + e.getMessage());
        }
    }

    // ── backup ────────────────────────────────────────────────────────────────

    /**
     * Moves the successfully processed source file into the backup directory,
     * preserving its path relative to the poll root so the backup mirrors the
     * inbox structure.
     *
     * <p>No-ops when {@code dirs.backup} is absent or blank in the pipeline config.
     *
     * <p>Example — file at {@code inbox/adjustment/subdir/feed.csv.gz} with
     * {@code backup: backup/adjustment} is moved to
     * {@code backup/adjustment/subdir/feed.csv.gz}.
     *
     * @param inputFile the source file that was just successfully processed
     */
    private static void backupFile(File inputFile) throws IOException {
        Map<String, Object> dirs = (Map<String, Object>) pipelineConfig.get("dirs");
        String backupDir = (String) dirs.get("backup");
        if (backupDir == null || backupDir.isBlank()) return;

        String pollDir = (String) dirs.get("poll");
        Path pollPath = Paths.get(pollDir).toAbsolutePath().normalize();
        Path filePath = inputFile.toPath().toAbsolutePath().normalize();
        Path relPath = pollPath.relativize(filePath);   // e.g. subdir/feed.csv.gz

        Path dst = Paths.get(backupDir).resolve(relPath);
        Files.createDirectories(dst.getParent());
        Files.move(filePath, dst, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("[%s] [%s] Backup: moved → %s%n", ts(), inputFile.getName(), dst);
    }

    // ── marker file ───────────────────────────────────────────────────────────

    /**
     * Creates a zero-byte sentinel file in {@code dirs.markers}, mirroring the input
     * file's path relative to the poll root, to prevent re-processing on the next cycle.
     * Parent directories are created as needed.
     * No-ops when {@code duplicate_check.enabled} is {@code false}.
     */
    private static void createMarkerFile(File file) throws IOException {
        Map<String, Object> dupCheck =
                (Map<String, Object>) ((Map<String, Object>) pipelineConfig.get("processing")).get("duplicate_check");
        if (!(boolean) dupCheck.get("enabled")) return;
        Path marker = getMarkerPath(file);
        Files.createDirectories(marker.getParent());
        Files.createFile(marker);
    }

    // ── marker helpers ────────────────────────────────────────────────────────

    /**
     * Computes the marker path for an input file inside {@code dirs.markers}.
     *
     * <p>The marker mirrors the file's path relative to {@code dirs.poll}:
     * a file at {@code poll/20200403/feed.csv.gz} produces a marker at
     * {@code markers/20200403/feed.csv.gz<marker_extension>}.
     */

    private static Path getMarkerPath(File file) {
        Map<String, Object> dirs = (Map<String, Object>) pipelineConfig.get("dirs");
        Map<String, Object> dupCheck = (Map<String, Object>)
                ((Map<String, Object>) pipelineConfig.get("processing")).get("duplicate_check");
        String markersDir = (String) dirs.get("markers");
        String markerExt = (String) dupCheck.get("marker_extension");
        Path poll = Paths.get((String) dirs.get("poll")).toAbsolutePath().normalize();
        Path filePath = file.toPath().toAbsolutePath().normalize();
        Path rel = poll.relativize(filePath);              // e.g. 20200403/feed.csv.gz
        Path base = Paths.get(markersDir).toAbsolutePath();
        if (rel.getParent() != null) base = base.resolve(rel.getParent());
        return base.resolve(rel.getFileName().toString() + markerExt);
    }

    /**
     * Deletes marker files older than {@code duplicate_check.retention_days} (default 90)
     * from {@code dirs.markers}, then prunes empty subdirectories left behind.
     * Runs once at the start of each poll cycle so the markers dir does not grow unbounded.
     * Silently no-ops when {@code dirs.markers} is absent or duplicate_check is disabled.
     */

    private static void cleanupStaleMarkers() {
        Map<String, Object> dirs = (Map<String, Object>) pipelineConfig.get("dirs");
        String markersDir = (String) dirs.get("markers");
        if (markersDir == null || markersDir.isBlank()) return;

        Map<String, Object> dupCheck = (Map<String, Object>)
                ((Map<String, Object>) pipelineConfig.get("processing")).get("duplicate_check");
        if (!(boolean) dupCheck.get("enabled")) return;

        int retentionDays = Integer.parseInt(
                String.valueOf(dupCheck.getOrDefault("retention_days", 90)));
        Path markerRoot = Paths.get(markersDir).toAbsolutePath();
        if (!Files.exists(markerRoot)) return;

        Instant cutoff = Instant.now().minusSeconds((long) retentionDays * 86400L);
        System.out.printf("[MARKER] Cleaning up markers older than %d days in %s%n",
                retentionDays, markerRoot);

        int[] counts = {0, 0};  // [deleted, errors]
        try (Stream<Path> walk = Files.walk(markerRoot)) {
            walk.filter(Files::isRegularFile).forEach(marker -> {
                try {
                    if (Files.getLastModifiedTime(marker).toInstant().isBefore(cutoff)) {
                        Files.delete(marker);
                        counts[0]++;
                    }
                } catch (IOException e) {
                    System.err.printf("[WARN] Could not check/delete marker %s: %s%n",
                            marker, e.getMessage());
                    counts[1]++;
                }
            });
        } catch (IOException e) {
            System.err.printf("[WARN] Could not walk markers dir %s: %s%n", markerRoot, e.getMessage());
            return;
        }

        // Prune empty subdirs left behind after marker deletion (bottom-up)
        try (Stream<Path> walk = Files.walk(markerRoot)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(Files::isDirectory)
                    .filter(p -> !p.equals(markerRoot))
                    .forEach(dir -> {
                        try {
                            Files.delete(dir);
                        } catch (DirectoryNotEmptyException ignored) {
                        } catch (IOException e) {
                            System.err.printf("[WARN] Could not remove marker subdir %s: %s%n",
                                    dir, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.printf("[WARN] Could not prune empty marker subdirs: %s%n", e.getMessage());
        }

        System.out.printf("[MARKER] Cleanup complete — deleted: %d  errors: %d%n",
                counts[0], counts[1]);
    }

    // ── quarantine ────────────────────────────────────────────────────────────

    /**
     * Moves {@code inputFile} into the quarantine tree, preserving its path relative
     * to the poll root so files from different sources remain grouped by origin.
     *
     * <p>Target layout: {@code <poll_dir>/quarantine/<relative_source_path>/<subDir>/<filename>}
     *
     * <p>Example — file at {@code poll/providerA/20240101/feed.csv.gz} is quarantined to
     * {@code poll/quarantine/providerA/20240101/field_mismatch/feed.csv.gz}.
     * Files dropped directly into the poll root land in
     * {@code poll/quarantine/<subDir>/<filename>}.
     *
     * <p>When {@code includeErrorCsv} is {@code true}, the companion
     * {@code errors/<basename>_errors.csv} (if present) is moved to the same quarantine
     * directory so the rejection evidence stays co-located with the bad file.
     *
     * @param inputFile       the file to quarantine
     * @param subDir          {@code "field_mismatch"} or {@code "unreadable"}
     * @param includeErrorCsv whether to also relocate the companion error CSV
     */
    private static void quarantineFile(File inputFile, String subDir, boolean includeErrorCsv)
            throws IOException {
        Map<String, Object> dirsMap = (Map<String, Object>) pipelineConfig.get("dirs");
        String pollDir = (String) dirsMap.get("poll");
        String quarantineDir = (String) dirsMap.getOrDefault("quarantine", pollDir + "/quarantine");
        String errorsDir = (String) dirsMap.getOrDefault("errors", pollDir + "/errors");

        // Compute the file's parent path relative to the poll root so the quarantine
        // directory mirrors the source's subdirectory structure.
        Path pollPath = Paths.get(pollDir).toAbsolutePath().normalize();
        Path fileParent = inputFile.toPath().toAbsolutePath().normalize().getParent();
        Path relativeParent = pollPath.relativize(fileParent);   // e.g. "providerA/20240101"

        // Guard against path traversal: relativize can produce ".." segments if the
        // input file somehow lives outside the poll root (misconfiguration or symlink).
        if (relativeParent.startsWith(".."))
            throw new IOException("Input file is not under poll root — cannot quarantine safely: " + inputFile);

        // <quarantine_dir>/<relative_parent>/<reason>/filename
        Path qDir = Paths.get(quarantineDir).toAbsolutePath().resolve(relativeParent).resolve(subDir);
        Files.createDirectories(qDir);

        Path dst = qDir.resolve(inputFile.getName());
        Files.move(inputFile.toPath(), dst, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("Quarantined [%s]: %s → %s%n", subDir, inputFile.getName(), dst);

        if (includeErrorCsv) {
            String baseName = stripExtensions(inputFile.getName());
            Path errorCsv = Paths.get(errorsDir).toAbsolutePath().resolve(baseName + "_errors.csv");
            if (Files.exists(errorCsv))
                Files.move(errorCsv, qDir.resolve(errorCsv.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ── status log ────────────────────────────────────────────────────────────

    /**
     * Appends one audit row to the status CSV file (thread-safe via {@code synchronized}).
     * Creates the file with a header row on first write.
     *
     * <p>Columns: {@code start_time, end_time, filename, status, parsed_rows,
     * error_rows, output_paths, output_sizes_bytes, duration_ms, error}
     *
     * <p>Multiple output paths and sizes are separated by {@code ;} and quoted to keep
     * the CSV parseable.
     */
    private synchronized static void updateStatus(
            String fileName, String status,
            IngestResult ingest, TransformResult transform,
            LocalDateTime startTime, LocalDateTime endTime,
            long durationMs, String error) {

        String statusPath = (String) ((Map<String, Object>) pipelineConfig.get("dirs")).get("status_file");
        boolean exists = new File(statusPath).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(statusPath, true))) {
            if (!exists)
                pw.println("start_time,end_time,filename,status," +
                        "parsed_rows,error_rows,output_paths,output_sizes_bytes,duration_ms,error");

            String paths = String.join(";", transform.outputPaths())
                    .replace('"', '\'');
            String sizes = transform.outputSizes().stream()
                    .map(String::valueOf).collect(Collectors.joining(";"));
            pw.printf("%s,%s,%s,%s,%d,%d,\"%s\",\"%s\",%d,\"%s\"%n",
                    startTime.format(DuckDbUtil.DT_FMT),
                    endTime.format(DuckDbUtil.DT_FMT),
                    fileName,
                    status,
                    ingest.parsedRows(),
                    ingest.errorRows(),
                    paths,
                    sizes,
                    durationMs,
                    error == null ? "" : error.replace('"', '\''));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ── SQL builder helpers ───────────────────────────────────────────────────

    /**
     * Appends a COALESCE(TRY_STRPTIME(...), ...)::TYPE expression to the builder.
     */
    private static void appendCoalesce(StringBuilder sb, String col, List<String> formats, String castType) {
        sb.append("COALESCE(");
        for (int j = 0; j < formats.size(); j++) {
            sb.append("TRY_STRPTIME(").append(col).append(", '").append(formats.get(j)).append("')");
            if (j < formats.size() - 1) sb.append(", ");
        }
        sb.append(")::").append(castType);
    }

    /**
     * Resolves the raw-input SQL expression for {@code partitionKey} by scanning the
     * mapping rules for the rule whose {@code targetColumn} matches.
     *
     * <p>Handles both {@code DIRECT} sources (including CONCAT_DT) so that the partition
     * columns are computed from the correct raw columns regardless of schema variant.
     * Falls back to {@code raw_input."partitionKey"} when no matching rule is found.
     */
    private static String buildPartitionExpr(String partitionKey,
                                             List<Map<String, String>> rules,
                                             Map<String, String> fieldTypes,
                                             List<String> dateFormats,
                                             List<String> tsFormats) {
        for (Map<String, String> rule : rules) {
            if (!partitionKey.equals(rule.get("targetColumn"))) continue;
            String source = rule.get("sourceExpression");
            String transformType = rule.getOrDefault("transformType", "DIRECT");
            if ("CONCAT_DT".equals(transformType)) {
                String[] parts = source.split("\\|", 2);
                String dateCol = "raw_input.\"" + parts[0] + '"';
                String timeCol = "raw_input.\"" + parts[1] + '"';
                StringBuilder sb = new StringBuilder();
                appendCoalesce(sb, dateCol + " || ' ' || " + timeCol, tsFormats, "TIMESTAMP");
                return sb.toString();
            } else {
                String col = "raw_input.\"" + source + '"';
                String type = fieldTypes.getOrDefault(source, "VARCHAR");
                return buildCastExpr(col, type, dateFormats, tsFormats);
            }
        }
        // Fallback: assume partitionKey is a raw column (original single-schema behaviour)
        String col = "raw_input.\"" + partitionKey + '"';
        String type = fieldTypes.getOrDefault(partitionKey, "VARCHAR");
        return buildCastExpr(col, type, dateFormats, tsFormats);
    }

    /**
     * Builds a typed cast expression for the partition key column.
     */
    private static String buildCastExpr(String col, String type, List<String> dateFormats, List<String> tsFormats) {
        StringBuilder sb = new StringBuilder();
        switch (type) {
            case "TIMESTAMP" -> {
                appendCoalesce(sb, col, tsFormats, "TIMESTAMP");
            }
            case "DATE" -> {
                appendCoalesce(sb, col, dateFormats, "DATE");
            }
            default -> sb.append(col);
        }
        return sb.toString();
    }

    /**
     * Strips .gz then the remaining extension: adj_DATE_20200403.csv.gz → adj_DATE_20200403
     */
    private static String stripExtensions(String fileName) {
        return fileName.replaceAll("\\.gz$", "").replaceAll("\\.[^.]+$", "");
    }

    /**
     * Returns the current timestamp string for inline progress messages.
     */
    private static String ts() {
        return LocalDateTime.now().format(DuckDbUtil.DT_FMT);
    }
}
