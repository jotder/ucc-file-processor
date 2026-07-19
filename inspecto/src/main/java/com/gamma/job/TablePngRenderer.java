package com.gamma.job;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a tabular dataset-export result as a PNG table image (BI-4). JDK-native only —
 * {@link Graphics2D} on a {@link BufferedImage} + {@link ImageIO}, which is headless-safe (no
 * display needed). A PNG is a snapshot, not an export: rows are capped at {@link #MAX_ROWS} with
 * a "+N more row(s)" footer; CSV remains the full-export format.
 */
final class TablePngRenderer {

    /** Snapshot cap — beyond this the image gets a "+N more row(s)" footer instead of more rows. */
    static final int MAX_ROWS = 50;

    private static final int MAX_COL_WIDTH = 320;   // px, incl. padding; longer cells get an ellipsis
    private static final int PAD_X = 10;            // horizontal cell padding
    private static final int ROW_H = 26;
    private static final int MARGIN = 16;
    private static final String ELLIPSIS = "…";

    private static final Color BG = Color.WHITE;
    private static final Color TEXT = new Color(0x33, 0x33, 0x33);
    private static final Color MUTED = new Color(0x77, 0x77, 0x77);
    private static final Color GRID = new Color(0xD8, 0xD8, 0xD8);
    private static final Color HEADER_BG = new Color(0xF2, 0xF2, 0xF2);

    private TablePngRenderer() {}

    /** Render {@code rows} (header = union of row keys, first-seen order) as a table image at {@code out}. */
    static void render(String title, List<Map<String, Object>> rows, Path out) throws IOException {
        Set<String> header = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) header.addAll(r.keySet());
        List<String> cols = new ArrayList<>(header);
        List<Map<String, Object>> body = rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows;
        int overflow = rows.size() - body.size();

        Font plain = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        Font bold = plain.deriveFont(Font.BOLD);
        Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 15);

        // Measure with a scratch image — FontMetrics needs a Graphics, not a display.
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D mg = scratch.createGraphics();
        FontMetrics fmPlain = mg.getFontMetrics(plain);
        FontMetrics fmBold = mg.getFontMetrics(bold);
        FontMetrics fmTitle = mg.getFontMetrics(titleFont);

        int[] widths = new int[cols.size()];
        for (int c = 0; c < cols.size(); c++) {
            int w = fmBold.stringWidth(cols.get(c));
            for (Map<String, Object> r : body) w = Math.max(w, fmPlain.stringWidth(cell(r, cols.get(c))));
            widths[c] = Math.min(w + 2 * PAD_X, MAX_COL_WIDTH);
        }
        String titleLine = title + " · " + DateTimeFormatter.ISO_LOCAL_DATE_TIME
                .format(LocalDateTime.now().withNano(0));
        int tableW = Math.max(1, java.util.stream.IntStream.of(widths).sum());
        int imgW = 2 * MARGIN + Math.max(tableW, fmTitle.stringWidth(titleLine));
        int titleH = fmTitle.getHeight() + 8;
        int footerRows = overflow > 0 ? 1 : 0;
        int imgH = 2 * MARGIN + titleH + (1 + body.size() + footerRows) * ROW_H;
        mg.dispose();

        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BG);
            g.fillRect(0, 0, imgW, imgH);

            g.setColor(TEXT);
            g.setFont(titleFont);
            g.drawString(titleLine, MARGIN, MARGIN + fmTitle.getAscent());

            int top = MARGIN + titleH;                       // table top edge
            int bottom = top + (1 + body.size()) * ROW_H;    // grid bottom (footer stays outside)

            g.setColor(HEADER_BG);
            g.fillRect(MARGIN, top, tableW, ROW_H);

            // Header + body text, per column with ellipsis truncation.
            int x = MARGIN;
            for (int c = 0; c < cols.size(); c++) {
                int max = widths[c] - 2 * PAD_X;
                g.setColor(TEXT);
                g.setFont(bold);
                g.drawString(fit(cols.get(c), g.getFontMetrics(), max),
                        x + PAD_X, top + (ROW_H + g.getFontMetrics().getAscent()) / 2 - 2);
                g.setFont(plain);
                FontMetrics fm = g.getFontMetrics();
                for (int r = 0; r < body.size(); r++) {
                    int y = top + (r + 1) * ROW_H + (ROW_H + fm.getAscent()) / 2 - 2;
                    g.drawString(fit(cell(body.get(r), cols.get(c)), fm, max), x + PAD_X, y);
                }
                x += widths[c];
            }

            // Gridlines + border.
            g.setColor(GRID);
            g.setStroke(new BasicStroke(1f));
            for (int r = 0; r <= body.size() + 1; r++)
                g.drawLine(MARGIN, top + r * ROW_H, MARGIN + tableW, top + r * ROW_H);
            x = MARGIN;
            for (int c = 0; c <= cols.size(); c++) {
                g.drawLine(x, top, x, bottom);
                if (c < cols.size()) x += widths[c];
            }

            if (overflow > 0) {
                g.setColor(MUTED);
                g.setFont(plain);
                g.drawString("+" + overflow + " more row(s) — export as csv for the full result",
                        MARGIN, bottom + (ROW_H + g.getFontMetrics().getAscent()) / 2 - 2);
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(img, "png", out.toFile());
    }

    private static String cell(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? "" : String.valueOf(v);
    }

    /** Truncate {@code s} with an ellipsis so it fits in {@code maxWidth} px. */
    private static String fit(String s, FontMetrics fm, int maxWidth) {
        if (fm.stringWidth(s) <= maxWidth) return s;
        int len = s.length();
        while (len > 0 && fm.stringWidth(s.substring(0, len) + ELLIPSIS) > maxWidth) len--;
        return s.substring(0, len) + ELLIPSIS;
    }
}
