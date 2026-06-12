package com.gamma.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvTest {

    @Test
    void readIntoMapsHeaderToValuesInOrder(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.csv");
        Files.writeString(f, "a,b,c\n1,2,3\n4,5,6\n", StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        Csv.readInto(f, out);
        assertEquals(2, out.size());
        assertEquals(List.of("a", "b", "c"), List.copyOf(out.get(0).keySet()));   // ordered
        assertEquals("6", out.get(1).get("c"));
    }

    @Test
    void shortRowsPadWithEmptyStrings(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.csv");
        Files.writeString(f, "a,b,c\n1,2\n", StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        Csv.readInto(f, out);
        assertEquals("", out.get(0).get("c"));
    }

    @Test
    void backslashesAreLiteralNotEscapes(@TempDir Path dir) throws Exception {
        // The reason this class exists: opencsv's default parser strips '\' from Windows paths.
        Path f = dir.resolve("t.csv");
        Files.writeString(f, "path\n\"C:\\db\\out.csv\"\n", StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        Csv.readInto(f, out);
        assertEquals("C:\\db\\out.csv", out.get(0).get("path"));
    }

    @Test
    void emptyOrHeaderlessFileYieldsNoRows(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.csv");
        Files.writeString(f, "", StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        Csv.readInto(f, out);
        assertTrue(out.isEmpty());
    }
}
