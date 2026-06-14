package com.gamma.acquire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-E: size + etag-checksum verification of a fetched file against its listing metadata. */
class IntegrityCheckerTest {

    private static RemoteFile listing(long size, String etag) {
        return new RemoteFile("f.csv", "f.csv", size, Instant.now(), etag, null, null);
    }

    @Test
    void passesWhenSizeMatchesAndNoEtag(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("f.csv");
        Files.writeString(f, "hello");
        assertTrue(IntegrityChecker.verify(listing(5, null), f, "SHA-256").ok());
    }

    @Test
    void failsOnSizeMismatch(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("f.csv");
        Files.writeString(f, "hello");
        IntegrityChecker.Result r = IntegrityChecker.verify(listing(99, null), f, "SHA-256");
        assertFalse(r.ok());
        assertTrue(r.detail().contains("size mismatch"));
    }

    @Test
    void skipsSizeCheckWhenListingHasNoSize(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("f.csv");
        Files.writeString(f, "hello");
        assertTrue(IntegrityChecker.verify(listing(RemoteFile.SIZE_UNKNOWN, null), f, "SHA-256").ok());
    }

    @Test
    void verifiesQuotedEtagChecksum(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("f.csv");
        Files.writeString(f, "hello");
        String md5 = Checksums.of(f, "MD5");
        assertTrue(IntegrityChecker.verify(listing(5, "\"" + md5 + "\""), f, "MD5").ok(), "quoted etag is unwrapped");
        assertFalse(IntegrityChecker.verify(listing(5, "deadbeef"), f, "MD5").ok(), "wrong etag fails");
    }
}
