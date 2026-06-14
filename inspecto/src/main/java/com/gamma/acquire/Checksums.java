package com.gamma.acquire;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;

/**
 * Content checksums for content-based duplicate detection (Data Acquisition roadmap Phase C) — computed with
 * the JDK only ({@link MessageDigest} / {@link CRC32}), <b>zero new dependencies</b>. Streams the file in 64&nbsp;KB
 * chunks so a large file never lands in memory. Returns a lowercase hex string.
 *
 * <p>Algorithm names are config-friendly: {@code MD5}, {@code SHA1}, {@code SHA256} (default), {@code SHA512},
 * {@code CRC32} — with or without the dash ({@code SHA-256} works too).
 */
public final class Checksums {

    private static final int BUFFER = 64 * 1024;

    private Checksums() {}

    /** Compute {@code algorithm} over {@code file}, returning lowercase hex. */
    public static String of(Path file, String algorithm) throws IOException {
        String algo = normalize(algorithm);
        if (algo.equals("CRC32")) return crc32(file);
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Unsupported checksum algorithm: " + algorithm, e);
        }
    }

    /** Map a config-friendly name to a JDK algorithm id ({@code SHA256} → {@code SHA-256}); default SHA-256. */
    static String normalize(String algorithm) {
        if (algorithm == null || algorithm.isBlank()) return "SHA-256";
        return switch (algorithm.trim().toUpperCase().replace("-", "")) {
            case "MD5"    -> "MD5";
            case "SHA1"   -> "SHA-1";
            case "SHA256" -> "SHA-256";
            case "SHA512" -> "SHA-512";
            case "CRC32"  -> "CRC32";
            default       -> algorithm.trim();   // pass through; MessageDigest will reject if unknown
        };
    }

    private static String crc32(Path file) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
        }
        return Long.toHexString(crc.getValue());
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
