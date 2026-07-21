package com.gamma.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Tees {@link System#out} and {@link System#err} into a timestamped log file.
 *
 * <p>Call {@link #configure(String, String, String)} once at pipeline startup.
 * When {@code logDir} is {@code null} or blank the call is a no-op — console-only
 * output continues as normal.
 *
 * <p>The log file is named {@code <pipelineName>_log_<timestamp>.log}, created
 * fresh on each run (never appended).  All output produced <em>after</em> this
 * call is written to both the console and the log file simultaneously.
 *
 * <p>Extracted from {@code com.gamma.inspector.CollectorProcessor} (core) where the
 * {@code setupLogFile()} method and inner {@code TeeOutputStream} class lived.
 */
public final class LogSetup {

    /** The open file stream — kept alive for the lifetime of the JVM. */
    private static FileOutputStream logFileStream;

    private LogSetup() {}

    /**
     * Configure log-file tee output.
     *
     * @param logDir       directory for the log file (created if absent);
     *                     {@code null} or blank → no-op
     * @param pipelineName pipeline identifier for the filename prefix
     *                     (e.g. {@code "voucher_unknown_etl"})
     * @param runTimestamp compact timestamp suffix shared with the status file
     *                     (e.g. {@code "20260526_085252"})
     * @throws IOException if the log directory or file cannot be created
     */
    public static void configure(String logDir, String pipelineName, String runTimestamp)
            throws IOException {
        if (logDir == null || logDir.isBlank()) return;

        Path logPath = Paths.get(logDir, pipelineName + "_log_" + runTimestamp + ".log");
        Files.createDirectories(logPath.getParent());

        logFileStream = new FileOutputStream(logPath.toFile(), false);   // fresh each run
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(
                new TeeOutputStream(originalOut, logFileStream), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(
                new TeeOutputStream(originalErr, logFileStream), true, StandardCharsets.UTF_8));
        System.out.printf("[LOG]    Log file    : %s%n", logPath);
    }

    // ── TeeOutputStream ───────────────────────────────────────────────────────

    /**
     * Multiplexes writes to two {@link OutputStream}s simultaneously.
     *
     * <p>Used to route {@link System#out} / {@link System#err} to both the original
     * console stream and the run log file.  Thread safety is provided by the outer
     * {@link PrintStream} wrapper (which synchronises per method call).
     */
    static final class TeeOutputStream extends OutputStream {
        private final OutputStream primary;
        private final OutputStream secondary;

        TeeOutputStream(OutputStream primary, OutputStream secondary) {
            this.primary   = primary;
            this.secondary = secondary;
        }

        @Override
        public void write(int b) throws IOException {
            primary.write(b);
            secondary.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            primary.write(b, off, len);
            secondary.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            primary.flush();
            secondary.flush();
        }

        @Override
        public void close() throws IOException {
            try { primary.close(); } finally { secondary.close(); }
        }
    }
}
