package com.gamma.acquire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Content checksums via the JDK only — known vectors for "hello" across the supported algorithms. */
class ChecksumsTest {

    private static Path file(Path dir, String content) throws Exception {
        Path p = dir.resolve("f.dat");
        Files.writeString(p, content);
        return p;
    }

    @Test
    void knownVectorsForHello(@TempDir Path dir) throws Exception {
        Path f = file(dir, "hello");
        assertEquals("5d41402abc4b2a76b9719d911017c592", Checksums.of(f, "MD5"));
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", Checksums.of(f, "SHA256"));
        assertEquals(Checksums.of(f, "SHA256"), Checksums.of(f, "SHA-256"), "dash-insensitive");
        assertEquals("3610a686", Checksums.of(f, "CRC32"));
    }

    @Test
    void defaultsToSha256AndDetectsChange(@TempDir Path dir) throws Exception {
        Path f = file(dir, "hello");
        assertEquals(Checksums.of(f, "SHA256"), Checksums.of(f, null), "null algorithm ⇒ SHA-256");
        Files.writeString(f, "hello!");
        assertNotEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", Checksums.of(f, "SHA256"));
    }

    @Test
    void unknownAlgorithmFails(@TempDir Path dir) throws Exception {
        assertThrows(java.io.IOException.class, () -> Checksums.of(file(dir, "x"), "NOPE-512"));
    }
}
