package com.gamma.util;

import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import dev.toonformat.jtoon.JToon;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;

/**
 * Generates a {@code <source>_schema.toon} and {@code <source>_pipeline.toon}
 * by inspecting a sample CSV file with DuckDB's type-inference engine.
 *
 * <p>Usage (standalone):
 * <pre>
 *   java -cp file-processor.jar com.gamma.util.SchemaExtractor \
 *        &lt;source_name&gt; &lt;sample_csv&gt; &lt;gen_config.toon&gt;
 * </pre>
 *
 * Or via MainApp:
 * <pre>
 *   java -jar file-processor.jar create-schema &lt;source_name&gt; &lt;sample_csv&gt; &lt;gen_config.toon&gt;
 * </pre>
 *
 * <p>The generation config ({@code gen_config.toon}) supplies CSV parsing hints
 * and optional type-pattern overrides:
 * <pre>
 *   csv_settings:
 *     delimiter: ","
 *     skip_header_lines: 0
 *     skip_junk_lines: 13
 *     skip_tail_lines: 2
 *     skip_tail_columns: 0
 *     date_formats[2]: %d-%b-%y, "%d-%b-%Y %H:%M:%S"
 *     timestamp_formats[2]: %d-%b-%y, "%d-%b-%Y %H:%M:%S"
 *   type_patterns:
 *     dates: [TRADE_DATE, VALUE_DATE]
 *     timestamps: [CREATED_TIME, UPDATED_TIME]
 * </pre>
 */
public class SchemaExtractor {

    private static String delimiter      = ",";
    private static int skipHeaderLines   = 0;
    private static int skipJunkLines     = 0;
    private static int skipTailLines     = 0;
    private static int skipTailColumns   = 0;
    private static List<String> dateFormats      = new ArrayList<>(List.of("%d-%b-%y"));
    private static List<String> timestampFormats = new ArrayList<>(List.of("%d-%b-%y"));
    private static List<String> dateCols         = new ArrayList<>();
    private static List<String> timestampCols    = new ArrayList<>();

    // ── entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: SchemaExtractor <source_name> <sample_csv_file> <generation_config_path>");
            System.exit(1);
        }
        run(args[0], args[1], args[2]);
    }

    /**
     * Core logic — callable from {@link MainApp} or directly.
     *
     * @param sourceName    logical name for the data source (used as prefix for output files)
     * @param samplePath    path to a representative CSV file
     * @param genConfigPath path to the generation {@code .toon} config
     */
    public static void run(String sourceName, String samplePath, String genConfigPath) throws Exception {
        loadGenConfig(genConfigPath);

        File inputFile = new File(samplePath);

        // DuckDB setup — in-memory, type inference only
        Class.forName("org.duckdb.DuckDBDriver");
        Connection conn = DriverManager.getConnection("jdbc:duckdb:");
        Statement stmt = conn.createStatement();

        CsvParserSettings settings = new CsvParserSettings();
        settings.getFormat().setDelimiter(delimiter.charAt(0));
        settings.setMaxColumns(10_000);
        settings.setMaxCharsPerColumn(1_000_000);
        settings.setQuoteDetectionEnabled(false);
        settings.getFormat().setQuote('\0');
        CsvParser csvParser = new CsvParser(settings);

        String[] headers;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(inputFile), StandardCharsets.UTF_8))) {

            // Skip junk lines that appear before the header
            for (int i = 0; i < skipHeaderLines; i++)
                br.readLine();

            String headerLine = br.readLine();
            if (headerLine == null) {
                System.err.println("Empty file — cannot extract schema.");
                return;
            }
            headers = csvParser.parseLine(headerLine);

            // Skip junk lines after the header row
            for (int i = 0; i < skipJunkLines; i++) br.readLine();

            // Write a small sample (≤ 1000 rows) to a temp file so DuckDB can read it
            File tempSample = File.createTempFile("sample_" + sourceName, ".csv");
            tempSample.deleteOnExit();
            try (PrintWriter pw = new PrintWriter(new FileWriter(tempSample))) {
                pw.println(String.join(delimiter, headers));
                String line;
                int count = 0;
                while ((line = br.readLine()) != null && count < 1_000) {
                    if (!line.trim().isEmpty()) {
                        pw.println(line);
                        count++;
                    }
                }
            }

            // ── type inference via DuckDB ──────────────────────────────────────
            System.out.println("Detecting types with DuckDB for source: " + sourceName);
            String viewSql = String.format(
                    "CREATE VIEW sample_view AS SELECT * FROM read_csv_auto('%s', delim='%s')",
                    tempSample.getAbsolutePath().replace("\\", "/"), delimiter);
            stmt.execute(viewSql);

            ResultSet rs = stmt.executeQuery("DESCRIBE sample_view");
            List<Map<String, String>> fields = new ArrayList<>();
            String potentialPartitionKey = "";
            int colIdx = 0;
            while (rs.next()) {
                String rawName  = colIdx < headers.length ? headers[colIdx] : "COLUMN_" + colIdx;
                String colName  = rawName.trim()
                        .replace(" ", "_").replace("-", "_")
                        .replace("(", "").replace(")", "");
                String duckType = rs.getString("column_type");
                String toonType = mapType(duckType, colName);

                if (potentialPartitionKey.isEmpty()
                        && (toonType.equals("DATE") || toonType.equals("TIMESTAMP"))) {
                    potentialPartitionKey = colName;
                }

                Map<String, String> field = new LinkedHashMap<>();
                field.put("name",     colName);
                field.put("selector", String.valueOf(colIdx));
                field.put("type",     toonType);
                fields.add(field);
                colIdx++;
            }

            // Resolve output directory alongside the gen-config file
            File genConfigFile = new File(genConfigPath);
            String outputDir = genConfigFile.getParent() != null ? genConfigFile.getParent() : ".";

            // ── 1. Generate Schema Config ──────────────────────────────────────
            Map<String, Object> schemaConfig = new LinkedHashMap<>();
            schemaConfig.put("partitionKey", potentialPartitionKey);

            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("name",   sourceName);
            raw.put("format", "CSV");
            raw.put("fields", fields);
            schemaConfig.put("raw", raw);

            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("canonicalName", sourceName);
            mapping.put("rawName",       sourceName);

            List<Map<String, String>> rules = new ArrayList<>();
            for (Map<String, String> f : fields) {
                Map<String, String> rule = new LinkedHashMap<>();
                rule.put("targetColumn",    f.get("name"));
                rule.put("sourceExpression", f.get("name"));
                rule.put("transformType",   "DIRECT");
                rules.add(rule);
            }
            mapping.put("rules", rules);
            schemaConfig.put("mapping", mapping);

            String schemaPath = outputDir + "/" + sourceName + "_schema.toon";
            Files.writeString(Paths.get(schemaPath), JToon.encode(schemaConfig), StandardCharsets.UTF_8);
            System.out.println("Generated schema : " + schemaPath);

            // ── 2. Generate Pipeline Config ────────────────────────────────────
            Map<String, Object> pipelineConfig = new LinkedHashMap<>();
            pipelineConfig.put("name",    sourceName.toUpperCase() + "_ETL");
            pipelineConfig.put("version", 1);

            // Full dirs block — all managed directories included so the pipeline
            // is ready to use with SourceProcessor without manual editing.
            Map<String, String> dirs = new LinkedHashMap<>();
            dirs.put("poll",       "inbox/"     + sourceName);
            dirs.put("database",   "database/"  + sourceName);
            dirs.put("backup",     "backup/"    + sourceName);
            dirs.put("temp",       "temp/"      + sourceName);
            dirs.put("errors",     "errors/"    + sourceName);
            dirs.put("quarantine", "quarantine/" + sourceName);
            dirs.put("status_file", "status.csv");
            pipelineConfig.put("dirs", dirs);

            Map<String, String> output = new LinkedHashMap<>();
            output.put("format",      "PARQUET");
            output.put("compression", "snappy");
            pipelineConfig.put("output", output);

            Map<String, Object> processing = new LinkedHashMap<>();
            processing.put("threads",      4);
            processing.put("file_pattern", "glob:**/*.{csv,csv.gz}");

            Map<String, Object> dupCheck = new LinkedHashMap<>();
            dupCheck.put("enabled",          true);
            dupCheck.put("marker_extension", ".processed");
            processing.put("duplicate_check", dupCheck);

            // Use a relative path so the pipeline is portable when deployed via package.ps1
            processing.put("schema_file", "config/" + sourceName + "/" + sourceName + "_schema.toon");

            Map<String, Object> csvSettings = new LinkedHashMap<>();
            csvSettings.put("delimiter",        delimiter);
            csvSettings.put("skip_header_lines", skipHeaderLines);
            csvSettings.put("skip_junk_lines",   skipJunkLines);
            csvSettings.put("skip_tail_lines",   skipTailLines);
            csvSettings.put("skip_tail_columns", skipTailColumns);
            csvSettings.put("date_formats",      dateFormats);
            csvSettings.put("timestamp_formats", timestampFormats);
            processing.put("csv_settings", csvSettings);

            pipelineConfig.put("processing", processing);

            String pipelinePath = outputDir + "/" + sourceName + "_pipeline.toon";
            Files.writeString(Paths.get(pipelinePath), JToon.encode(pipelineConfig), StandardCharsets.UTF_8);
            System.out.println("Generated pipeline: " + pipelinePath);
        }
    }

    // ── gen-config loading ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void loadGenConfig(String configPath) throws IOException {
        File configFile = new File(configPath);
        if (!configFile.exists()) return;

        Map<String, Object> config = (Map<String, Object>)
                JToon.decode(Files.readString(configFile.toPath(), StandardCharsets.UTF_8));

        if (config.containsKey("csv_settings")) {
            Map<String, Object> csv = (Map<String, Object>) config.get("csv_settings");
            if (csv.containsKey("delimiter"))          delimiter         = (String) csv.get("delimiter");
            if (csv.containsKey("skip_header_lines"))  skipHeaderLines   = toInt(csv.get("skip_header_lines"));
            if (csv.containsKey("skip_junk_lines"))    skipJunkLines     = toInt(csv.get("skip_junk_lines"));
            if (csv.containsKey("skip_tail_lines"))    skipTailLines     = toInt(csv.get("skip_tail_lines"));
            if (csv.containsKey("skip_tail_columns"))  skipTailColumns   = toInt(csv.get("skip_tail_columns"));
            if (csv.containsKey("date_formats"))       dateFormats       = (List<String>) csv.get("date_formats");
            if (csv.containsKey("timestamp_formats"))  timestampFormats  = (List<String>) csv.get("timestamp_formats");
        }
        if (config.containsKey("type_patterns")) {
            Map<String, Object> patterns = (Map<String, Object>) config.get("type_patterns");
            if (patterns.containsKey("dates"))      dateCols      = (List<String>) patterns.get("dates");
            if (patterns.containsKey("timestamps")) timestampCols = (List<String>) patterns.get("timestamps");
        }
    }

    private static int toInt(Object val) {
        return Integer.parseInt(String.valueOf(val));
    }

    // ── DuckDB → toon type mapping ─────────────────────────────────────────────

    private static String mapType(String duckType, String colName) {
        String n = colName.toUpperCase();

        // Explicit column-name overrides from gen config take highest priority
        if (dateCols.stream().anyMatch(d -> n.contains(d.toUpperCase())))      return "DATE";
        if (timestampCols.stream().anyMatch(t -> n.contains(t.toUpperCase()))) return "TIMESTAMP";

        // Heuristic name-based fallback
        if (n.contains("DATE") || n.contains("TIME") || n.contains("EXPIRE") || n.contains("APPLY"))
            return "TIMESTAMP";

        // DuckDB-inferred type
        if (duckType == null) return "VARCHAR";
        String dt = duckType.toUpperCase();
        if (dt.contains("INT") || dt.contains("LONG") || dt.contains("DECIMAL")
                || dt.contains("DOUBLE") || dt.contains("FLOAT")) return "DOUBLE";
        if (dt.contains("TIMESTAMP")) return "TIMESTAMP";
        if (dt.contains("DATE"))      return "DATE";
        return "VARCHAR";
    }
}
