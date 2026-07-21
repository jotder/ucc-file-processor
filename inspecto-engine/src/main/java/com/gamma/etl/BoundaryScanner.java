package com.gamma.etl;

import com.univocity.parsers.csv.CsvParser;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Cheap head-window scan that resolves the adaptive "messy file" knobs
 * ({@code skip_junk_lines}, {@code skip_tail_columns}) into concrete parameters DuckDB's native
 * {@code read_csv} can consume — so files that previously had to use the slow Java parser
 * (notably SQL*Plus dumps with a variable banner/password/error preamble) can be parsed natively.
 *
 * <p>It reads only the file's head (bounded by a window cap), reusing the same column-count +
 * echoed-header heuristic as {@link CsvIngester}, and returns:
 * <ul>
 *   <li>{@code skip} — the number of physical lines to skip ({@code skip_header_lines} + the header
 *       row when {@code has_header} + any leading junk/blank lines before the first data row), to be
 *       passed verbatim as {@code read_csv(skip=…)};</li>
 *   <li>{@code physicalWidth} — the column count of the first data row, used to declare the
 *       {@code read_csv} column set wide enough to tolerate {@code skip_tail_columns} extra trailing
 *       columns (the wanted selectors are projected; the extras are simply not referenced);</li>
 *   <li>{@code resolved} — {@code false} when no data row is found within the window (the caller then
 *       falls back to the Java parser, so nothing regresses).</li>
 * </ul>
 *
 * <p>This scan is read-only and side-effect free; it never throws (an I/O failure yields an
 * unresolved result so the caller falls back).
 */
public final class BoundaryScanner {

    /** Safety bound on lines scanned when {@code skip_junk_lines = -1} (unlimited). */
    private static final int UNLIMITED_WINDOW = 1_000_000;

    private BoundaryScanner() {}

    /** Result of a head-window scan; see {@link BoundaryScanner}. */
    public record BoundaryScan(boolean resolved, int skip, int physicalWidth) {}

    @SuppressWarnings("unchecked")
    public static BoundaryScan scan(File file, Map<String, Object> schemaConfig, PipelineConfig cfg) {
        int maxSelector = maxSelector(schemaConfig);
        int headerOffset = cfg.csv().skipHeaderLines() + (cfg.csv().hasHeader() ? 1 : 0);
        BoundaryScan unresolved = new BoundaryScan(false, headerOffset, maxSelector + 1);

        int skipJunk   = cfg.csv().skipJunkLines();
        int maxJunk    = skipJunk < 0 ? Integer.MAX_VALUE : skipJunk;
        int windowCap  = skipJunk < 0 ? UNLIMITED_WINDOW : Math.max(skipJunk, 1024);

        CsvParser parser = CsvIngester.buildParser(cfg.csv().delimiter());

        try (InputStream rawIs = new FileInputStream(file);
             InputStream is = Compression.decompress(file, rawIs, 1 << 20);
             BufferedReader br = new BufferedReader(
                     new InputStreamReader(is, charset(cfg.csv().encoding())), 1 << 20)) {

            for (int i = 0; i < cfg.csv().skipHeaderLines(); i++) br.readLine();

            String[] headerTokens = null;
            if (cfg.csv().hasHeader()) {
                String h = br.readLine();
                if (h == null) return unresolved;     // empty file
                headerTokens = parser.parseLine(h);
            }

            // No junk scan requested → first non-blank line is the first data row; skip stays at the
            // header offset (read_csv's ignore_errors drops any interspersed blank/short lines), and
            // we only read forward far enough to measure the physical column width.
            if (maxJunk <= 0) {
                String line;
                int read = 0;
                while (read < windowCap && (line = br.readLine()) != null) {
                    read++;
                    if (line.trim().isEmpty()) continue;
                    String[] probe = parser.parseLine(line);
                    int width = (probe != null) ? probe.length : maxSelector + 1;
                    return new BoundaryScan(true, headerOffset, width);
                }
                return unresolved;
            }

            // Adaptive junk scan: count every physical line consumed (incl. blanks) until the first
            // line that has enough columns AND does not echo the header.
            int physicalConsumed = 0;
            int junkCount = 0;
            String line;
            while (junkCount < maxJunk && physicalConsumed < windowCap && (line = br.readLine()) != null) {
                physicalConsumed++;
                if (line.trim().isEmpty()) continue;        // blank: consumed, not junk-counted
                String[] probe = parser.parseLine(line);
                if (probe != null && probe.length > maxSelector && !isEchoLine(probe, headerTokens)) {
                    int skip = headerOffset + (physicalConsumed - 1);   // data line itself is not skipped
                    return new BoundaryScan(true, skip, probe.length);
                }
                junkCount++;
            }
            return unresolved;       // no data row found within the window → Java fallback
        } catch (Exception e) {
            return unresolved;
        }
    }

    @SuppressWarnings("unchecked")
    private static int maxSelector(Map<String, Object> schemaConfig) {
        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) schemaConfig.get("raw")).get("fields");
        int max = 0;
        for (Map<String, Object> f : fields) {
            int s = Integer.parseInt(String.valueOf(f.get("selector")));
            if (s > max) max = s;
        }
        return max;
    }

    /** A line echoing the header (≥50% case-insensitive cell match) is junk, not data. */
    private static boolean isEchoLine(String[] probe, String[] headerTokens) {
        if (headerTokens == null || headerTokens.length == 0) return false;
        int checkLen = Math.min(probe.length, headerTokens.length);
        if (checkLen == 0) return false;
        int matches = 0;
        for (int i = 0; i < checkLen; i++) {
            String p = probe[i] == null ? "" : probe[i].trim();
            String h = headerTokens[i] == null ? "" : headerTokens[i].trim();
            if (p.equalsIgnoreCase(h)) matches++;
        }
        return (double) matches / checkLen >= 0.5;
    }

    /** Map a grammar {@code encoding} value to a {@link Charset}; blank/unknown ⇒ UTF-8. */
    static Charset charset(String encoding) {
        if (encoding == null || encoding.isBlank()) return StandardCharsets.UTF_8;
        return switch (encoding.toLowerCase().replace("_", "-")) {
            case "latin-1", "latin1", "iso-8859-1" -> StandardCharsets.ISO_8859_1;
            case "utf-16"   -> StandardCharsets.UTF_16;
            case "utf-16le" -> StandardCharsets.UTF_16LE;
            case "utf-16be" -> StandardCharsets.UTF_16BE;
            case "us-ascii", "ascii" -> StandardCharsets.US_ASCII;
            default -> {
                try { yield Charset.forName(encoding); }
                catch (Exception e) { yield StandardCharsets.UTF_8; }
            }
        };
    }
}
