package com.gamma.pipeline.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T13 — {@link FileLander}: the adapter's land-then-ack. The file is durably present in the staging dir
 * before the ack runs, and no {@code .tmp} residue is left behind.
 */
class FileLanderTest {

    @TempDir Path staging;

    @Test
    void landsFileThenAcksAfterTheFileIsVisible() throws Exception {
        String[] seenByAck = {null};
        // the ack reads the final path — it can only succeed if the rename happened before the ack
        Path landed = FileLander.land(staging, "micro-0001.csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8),
                () -> {
                    try { seenByAck[0] = Files.readString(staging.resolve("micro-0001.csv")); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });

        assertEquals(staging.resolve("micro-0001.csv"), landed);
        assertTrue(Files.exists(landed));
        assertEquals("a,b\n1,2\n", Files.readString(landed));
        assertEquals("a,b\n1,2\n", seenByAck[0], "ack ran after the file was durably visible");

        // no temp residue
        try (var s = Files.list(staging)) {
            List<String> names = s.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("micro-0001.csv"), names);
            assertTrue(names.stream().noneMatch(n -> n.contains(".tmp-")));
        }
    }

    @Test
    void worksWithoutAnAckCallback() throws Exception {
        Path landed = FileLander.land(staging, "m.bin", new byte[]{1, 2, 3}, null);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(landed));
    }
}
