package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte-exact characterization of {@link TransformCompiler}. These assertions pin the
 * SQL string each transform/partition type emits, so the extraction of expression
 * generation out of {@link DataTransformer} stays behaviour-preserving forever — any
 * future change that alters the emitted SQL must update these intentionally.
 *
 * <p>Formats are fixed to a single date pattern ({@code %Y-%m-%d}) and a single
 * timestamp pattern ({@code %Y-%m-%d %H:%M:%S}) so the COALESCE chains are deterministic.
 */
class TransformCompilerTest {

    private static PipelineConfig cfg(Path dir) throws Exception {
        return TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema())
                .dateFormats("\"%Y-%m-%d\"")
                .tsFormats("\"%Y-%m-%d %H:%M:%S\"")
                .load();
    }

    private static final Map<String, String> TYPES = Map.of(
            "X", "VARCHAR", "D", "DATE", "T", "TIMESTAMP", "A", "DOUBLE");

    private static Map<String, String> rule(String src, String tgt, String type) {
        return Map.of("sourceExpression", src, "targetColumn", tgt, "transformType", type);
    }

    // ── data columns ────────────────────────────────────────────────────────────

    @Test
    void directVarcharIsBareReference(@TempDir Path dir) throws Exception {
        assertEquals("\"raw_input\".\"X\"",
                TransformCompiler.dataColumn(rule("X", "OUT", "DIRECT"), TYPES, "raw_input", cfg(dir)));
    }

    @Test
    void directDoubleUsesTryCast(@TempDir Path dir) throws Exception {
        assertEquals("TRY_CAST(\"raw_input\".\"A\" AS DOUBLE)",
                TransformCompiler.dataColumn(rule("A", "OUT", "DIRECT"), TYPES, "raw_input", cfg(dir)));
    }

    @Test
    void directDateWrapsVarcharThenStrptime(@TempDir Path dir) throws Exception {
        assertEquals("COALESCE(TRY_STRPTIME(CAST(\"raw_input\".\"D\" AS VARCHAR), '%Y-%m-%d'))::DATE",
                TransformCompiler.dataColumn(rule("D", "OUT", "DIRECT"), TYPES, "raw_input", cfg(dir)));
    }

    @Test
    void directTimestampWrapsVarcharThenStrptime(@TempDir Path dir) throws Exception {
        assertEquals("COALESCE(TRY_STRPTIME(CAST(\"raw_input\".\"T\" AS VARCHAR), '%Y-%m-%d %H:%M:%S'))::TIMESTAMP",
                TransformCompiler.dataColumn(rule("T", "OUT", "DIRECT"), TYPES, "raw_input", cfg(dir)));
    }

    @Test
    void blankTransformTypeIsDirect(@TempDir Path dir) throws Exception {
        // "nothing mentioned means DIRECT": a blank transformType cell → pass-through cast.
        assertEquals("\"raw_input\".\"X\"",
                TransformCompiler.dataColumn(rule("X", "OUT", ""), TYPES, "raw_input", cfg(dir)));
    }

    @Test
    void absentTransformTypeIsDirect(@TempDir Path dir) throws Exception {
        // An omitted transformType key (2-column rule) → DIRECT.
        assertEquals("\"raw_input\".\"X\"",
                TransformCompiler.dataColumn(Map.of("sourceExpression", "X", "targetColumn", "OUT"),
                        TYPES, "raw_input", cfg(dir)));
    }

    @Test
    void directIsCaseInsensitive(@TempDir Path dir) throws Exception {
        assertEquals("\"raw_input\".\"X\"",
                TransformCompiler.dataColumn(rule("X", "OUT", " direct "), TYPES, "raw_input", cfg(dir)));
    }

    @Test
    void exprPassesThroughVerbatim(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        // EXPR emits the sourceExpression as-is; unqualified columns resolve against raw_input.
        assertEquals("UPPER(TRIM(X))",
                TransformCompiler.dataColumn(rule("UPPER(TRIM(X))", "OUT", "EXPR"), TYPES, "raw_input", cfg));
        assertEquals("CASE WHEN E = '0' THEN 'OK' ELSE 'FAIL' END",
                TransformCompiler.dataColumn(rule("CASE WHEN E = '0' THEN 'OK' ELSE 'FAIL' END", "R", "EXPR"),
                        TYPES, "raw_input", cfg));
    }

    @Test
    void unknownTransformTypeThrows(@TempDir Path dir) throws Exception {
        // A non-blank, unrecognised type (typo) fails fast rather than silently degrading to DIRECT.
        PipelineConfig cfg = cfg(dir);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                TransformCompiler.dataColumn(rule("X", "OUT", "EXPER"), TYPES, "raw_input", cfg));
        assertTrue(e.getMessage().contains("EXPER"), e.getMessage());
    }

    @Test
    void concatDtJoinsDateAndTime(@TempDir Path dir) throws Exception {
        assertEquals("COALESCE(TRY_STRPTIME(\"raw_input\".\"D\" || ' ' || \"raw_input\".\"T\", "
                        + "'%Y-%m-%d %H:%M:%S'))::TIMESTAMP",
                TransformCompiler.dataColumn(rule("D|T", "TS", "CONCAT_DT"), TYPES, "raw_input", cfg(dir)));
    }

    @Test
    void filenameDateExtractsEmbeddedDate(@TempDir Path dir) throws Exception {
        assertEquals("TRY_STRPTIME(regexp_extract(\"raw_input\".\"F\", 'data_([0-9]{8})', 1), '%Y%m%d')::DATE",
                TransformCompiler.dataColumn(rule("F|data_|%Y%m%d", "EVENT_DATE", "FILENAME_DATE"),
                        TYPES, "raw_input", cfg(dir)));
    }

    @Test
    void filenameDateRejectsNonEventDateTarget(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        assertThrows(IllegalArgumentException.class, () ->
                TransformCompiler.dataColumn(rule("F|data_|%Y%m%d", "OTHER", "FILENAME_DATE"),
                        TYPES, "raw_input", cfg));
    }

    // ── partition columns ─────────────────────────────────────────────────────────

    @Test
    void partitionVarchar(@TempDir Path dir) throws Exception {
        assertEquals("\"raw_input\".\"C\"",
                TransformCompiler.partitionColumn(new PartitionDef("c", "C", PartitionDef.Type.VARCHAR),
                        "raw_input", TYPES, cfg(dir)));
    }

    @Test
    void partitionDoubleAndInteger(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        assertEquals("TRY_CAST(\"raw_input\".\"N\" AS DOUBLE)",
                TransformCompiler.partitionColumn(new PartitionDef("n", "N", PartitionDef.Type.DOUBLE),
                        "raw_input", TYPES, cfg));
        assertEquals("TRY_CAST(\"raw_input\".\"N\" AS INTEGER)",
                TransformCompiler.partitionColumn(new PartitionDef("n", "N", PartitionDef.Type.INTEGER),
                        "raw_input", TYPES, cfg));
    }

    @Test
    void partitionDateComponents(@TempDir Path dir) throws Exception {
        // A non-TIMESTAMP source (DT is untyped → VARCHAR) parses via date_formats.
        PipelineConfig cfg = cfg(dir);
        String dateExpr = "COALESCE(TRY_STRPTIME(CAST(\"raw_input\".\"DT\" AS VARCHAR), '%Y-%m-%d'))::DATE";
        assertEquals("YEAR(" + dateExpr + ")::VARCHAR",
                TransformCompiler.partitionColumn(new PartitionDef("year", "DT", PartitionDef.Type.DATE_YEAR),
                        "raw_input", TYPES, cfg));
        assertEquals("LPAD(MONTH(" + dateExpr + ")::VARCHAR, 2, '0')",
                TransformCompiler.partitionColumn(new PartitionDef("month", "DT", PartitionDef.Type.DATE_MONTH),
                        "raw_input", TYPES, cfg));
        assertEquals("LPAD(DAY(" + dateExpr + ")::VARCHAR, 2, '0')",
                TransformCompiler.partitionColumn(new PartitionDef("day", "DT", PartitionDef.Type.DATE_DAY),
                        "raw_input", TYPES, cfg));
    }

    @Test
    void partitionTimestampSourceUsesTimestampFormats(@TempDir Path dir) throws Exception {
        // A TIMESTAMP-typed source (T) must parse via timestamp_formats, not date_formats — otherwise
        // a value like "2018-04-09-00.00.00" fails a date-only parse and lands in the 1900 sentinel.
        PipelineConfig cfg = cfg(dir);
        String tsExpr = "COALESCE(TRY_STRPTIME(CAST(\"raw_input\".\"T\" AS VARCHAR), "
                + "'%Y-%m-%d %H:%M:%S'))::TIMESTAMP";
        assertEquals("YEAR(" + tsExpr + ")::VARCHAR",
                TransformCompiler.partitionColumn(new PartitionDef("year", "T", PartitionDef.Type.DATE_YEAR),
                        "raw_input", TYPES, cfg));
        assertEquals("LPAD(MONTH(" + tsExpr + ")::VARCHAR, 2, '0')",
                TransformCompiler.partitionColumn(new PartitionDef("month", "T", PartitionDef.Type.DATE_MONTH),
                        "raw_input", TYPES, cfg));
    }
}
