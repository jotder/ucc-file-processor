package com.gamma.etl;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * The parse plan both CSV ingesters derive from a schema config — computed once per ingest,
 * previously duplicated between {@link CsvIngester} (Java parser) and {@link DuckDbCsvIngester}
 * (native {@code read_csv}): each field's raw-column selector index, the widest selector, and
 * the per-file error-CSV location.
 *
 * <p>Hoisting the selector parse out of the row loop matters: the pre-hoist code did
 * {@code Integer.parseInt} per row × column (24M calls on a 2M×12 file).
 *
 * @param fields      the schema's {@code raw.fields} maps, in declared order
 * @param selectorIdx the raw-column index feeding output column {@code i}
 * @param maxSelector the widest selector — rows with fewer columns are rejected
 */
record ParserSpec(List<Map<String, Object>> fields, int[] selectorIdx, int maxSelector) {

    /** Derive the parse plan from a schema config's {@code raw.fields}. */
    @SuppressWarnings("unchecked")
    static ParserSpec fromSchema(Map<String, Object> schemaConfig) {
        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) schemaConfig.get("raw")).get("fields");
        int[] selectorIdx = new int[fields.size()];
        int maxSelector = 0;
        for (int i = 0; i < fields.size(); i++) {
            int sel = Integer.parseInt(String.valueOf(fields.get(i).get("selector")));
            selectorIdx[i] = sel;
            if (sel > maxSelector) maxSelector = sel;
        }
        return new ParserSpec(fields, selectorIdx, maxSelector);
    }

    /** Physical column count declared to the native reader ({@code maxSelector + 1}). */
    int physicalCols() {
        return maxSelector + 1;
    }

    /** The per-file reject ledger: {@code <dirs.errors>/<basename>_errors.csv}. */
    static Path errorFile(File file, PipelineConfig cfg) {
        return Paths.get(cfg.dirs().errors()).toAbsolutePath()
                .resolve(CsvIngester.stripExtensions(file.getName()) + "_errors.csv");
    }
}
