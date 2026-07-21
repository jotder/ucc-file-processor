package com.gamma.job;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Renders a tabular dataset-export result as a single-page PDF (BI-4). JDK-native only, no PDF
 * library on the classpath (offline build — see docs/BACKLOG.md BI-4): this is the "PNG-wrapped-
 * in-PDF" fallback the backlog calls for, not general-purpose PDF authoring. It reuses
 * {@link TablePngRenderer#renderImage} for layout, then hand-writes a minimal single-object PDF
 * (one Page, one Image XObject, FlateDecode + DeviceRGB) — no text layer, no fonts embedded.
 */
final class PdfRenderer {

    /** 1 image pixel = 1/{@code PX_PER_PT} PDF point, keeping the page a reasonable print size. */
    private static final double PX_PER_PT = 1.5;

    private PdfRenderer() {}

    static void render(String title, List<Map<String, Object>> rows, Path out) throws IOException {
        BufferedImage img = TablePngRenderer.renderImage(title, rows);
        int w = img.getWidth(), h = img.getHeight();

        byte[] rgb = new byte[w * h * 3];
        int p = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                rgb[p++] = (byte) ((argb >> 16) & 0xFF);
                rgb[p++] = (byte) ((argb >> 8) & 0xFF);
                rgb[p++] = (byte) (argb & 0xFF);
            }
        }
        byte[] deflated = deflate(rgb);

        double ptW = w / PX_PER_PT, ptH = h / PX_PER_PT;
        String content = "q " + fmt(ptW) + " 0 0 " + fmt(ptH) + " 0 0 cm /Im0 Do Q";
        byte[] contentBytes = content.getBytes(StandardCharsets.US_ASCII);

        List<byte[]> objects = new ArrayList<>();
        objects.add(obj("<< /Type /Catalog /Pages 2 0 R >>"));
        objects.add(obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"));
        objects.add(obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + fmt(ptW) + " " + fmt(ptH)
                + "] /Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>"));
        objects.add(streamObj("<< /Length " + contentBytes.length + " >>", contentBytes));
        objects.add(streamObj("<< /Type /XObject /Subtype /Image /Width " + w + " /Height " + h
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length "
                + deflated.length + " >>", deflated));

        try (OutputStream os = java.nio.file.Files.newOutputStream(out)) {
            writePdf(os, objects);
        }
    }

    private static byte[] obj(String dict) {
        return dict.getBytes(StandardCharsets.US_ASCII);
    }

    /** Marker wrapper so {@link #writePdf} knows to emit a stream body after the dictionary. */
    private static byte[] streamObj(String dict, byte[] streamBody) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.writeBytes(dict.getBytes(StandardCharsets.US_ASCII));
        bos.writeBytes("\nstream\n".getBytes(StandardCharsets.US_ASCII));
        bos.writeBytes(streamBody);
        bos.writeBytes("\nendstream".getBytes(StandardCharsets.US_ASCII));
        return bos.toByteArray();
    }

    private static void writePdf(OutputStream os, List<byte[]> objects) throws IOException {
        List<Integer> offsets = new ArrayList<>();
        StringBuilder header = new StringBuilder("%PDF-1.4\n");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(body.size());
            body.write((i + 1 + " 0 obj\n").getBytes(StandardCharsets.US_ASCII));
            body.write(objects.get(i));
            body.write("\nendobj\n".getBytes(StandardCharsets.US_ASCII));
        }
        int xrefStart = body.size();
        StringBuilder xref = new StringBuilder();
        xref.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int off : offsets) xref.append(String.format("%010d 00000 n \n", off));
        xref.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\n")
                .append("startxref\n").append(xrefStart).append("\n%%EOF");
        body.write(xref.toString().getBytes(StandardCharsets.US_ASCII));
        os.write(body.toByteArray());
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(bos, new Deflater(Deflater.BEST_SPEED))) {
            dos.write(data);
        }
        return bos.toByteArray();
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
