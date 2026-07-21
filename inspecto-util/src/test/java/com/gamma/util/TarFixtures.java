package com.gamma.util;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Builds small tar/tar.gz fixtures for the CLI-tool tests. */
final class TarFixtures {

    private TarFixtures() {}

    /** Write a (gzipped when the name says so) tar at {@code archive} with the given name→content members. */
    static void writeTar(Path archive, Map<String, String> members) throws IOException {
        Files.createDirectories(archive.getParent());
        try (OutputStream fo = Files.newOutputStream(archive);
             OutputStream zo = archive.getFileName().toString().endsWith(".gz")
                     || archive.getFileName().toString().endsWith(".tgz")
                     ? new GzipCompressorOutputStream(fo) : fo;
             TarArchiveOutputStream tar = new TarArchiveOutputStream(zo)) {
            for (Map.Entry<String, String> m : members.entrySet()) {
                byte[] bytes = m.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(m.getKey());
                entry.setSize(bytes.length);
                tar.putArchiveEntry(entry);
                tar.write(bytes);
                tar.closeArchiveEntry();
            }
        }
    }
}
