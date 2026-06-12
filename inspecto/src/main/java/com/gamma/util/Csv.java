package com.gamma.util;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.RFC4180ParserBuilder;
import com.opencsv.exceptions.CsvValidationException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single home for <b>RFC4180 CSV reading</b>. Every CSV read in the platform must go
 * through here so the parser choice is made exactly once: the RFC4180 parser treats
 * backslashes as literal characters, whereas opencsv's default {@code CSVParser} treats
 * {@code '\'} as an escape character and silently strips it from Windows paths
 * ({@code "C:\db\out.csv"} → {@code "C:dbout.csv"}). That bug previously had to be fixed
 * in four separate copies of this code (audit readers, status store, pre-ETL utilities).
 *
 * <p>Matches the writers' quoting convention (fields wrapped in double quotes, embedded
 * quotes replaced with single quotes — see {@link CsvLedger#q}).
 */
public final class Csv {

    private Csv() {}

    /** An RFC4180 {@link CSVReader} over {@code reader} (caller closes; backslashes literal). */
    public static CSVReader reader(Reader reader) {
        return new CSVReaderBuilder(reader)
                .withCSVParser(new RFC4180ParserBuilder().build()).build();
    }

    /**
     * Append each data row of a header-bearing CSV to {@code out} as an ordered
     * header→value map (short rows pad with {@code ""}). Rows read before a mid-file
     * parse error remain in {@code out} — the caller decides how to report the failure.
     */
    public static void readInto(Path file, List<Map<String, String>> out)
            throws IOException, CsvValidationException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVReader csv = reader(r)) {
            String[] header = csv.readNext();
            if (header == null) return;
            String[] row;
            while ((row = csv.readNext()) != null) {
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 0; i < header.length; i++)
                    m.put(header[i], i < row.length ? row[i] : "");
                out.add(m);
            }
        }
    }
}
