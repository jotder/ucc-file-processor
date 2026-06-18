package com.gamma.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ComponentStore} (T19): create / replace / delete registry components, jailed + atomic, with the
 * id canonicalised to the in-file identity. Probes the {@code ConfigCodec.toToon} round-trip per type.
 */
class ComponentStoreTest {

    @Test
    void writeReadsBackWithIdStampedAsName(@TempDir Path root) throws Exception {
        ComponentStore store = new ComponentStore(root);
        store.write("transform", "redact-pii", Map.of("sql", "SELECT * EXCLUDE (ssn) FROM rows"));

        ComponentRegistry.Component c = store.get("transform", "redact-pii").orElseThrow();
        assertEquals("transform", c.type());
        assertEquals("redact-pii", c.name());                       // id stamped as name
        assertEquals("redact-pii", c.content().get("name"));
        assertEquals("SELECT * EXCLUDE (ssn) FROM rows", c.content().get("sql"));
        // written under registry/transforms/<id>.toon
        assertTrue(c.path().toString().replace('\\', '/').endsWith("transforms/redact-pii.toon"));
        assertTrue(Files.exists(c.path()));
    }

    @Test
    void listAndDeleteAcrossTypes(@TempDir Path root) throws Exception {
        ComponentStore store = new ComponentStore(root);
        store.write("grammar", "pipe", Map.of("delimiter", "|"));
        store.write("grammar", "comma", Map.of("delimiter", ","));
        store.write("sink", "warehouse", Map.of("format", "PARQUET"));

        assertEquals(2, store.list("grammar").size());
        assertEquals(1, store.list("sink").size());
        assertEquals(0, store.list("transform").size());

        assertTrue(store.delete("grammar", "pipe"));
        assertEquals(1, store.list("grammar").size());
        assertFalse(store.get("grammar", "pipe").isPresent());
        assertFalse(store.delete("grammar", "ghost"));             // already absent
    }

    @Test
    void replaceOverwritesInPlace(@TempDir Path root) throws Exception {
        ComponentStore store = new ComponentStore(root);
        store.write("sink", "wh", Map.of("format", "CSV"));
        store.write("sink", "wh", Map.of("format", "PARQUET"));   // replace
        assertEquals(1, store.list("sink").size());
        assertEquals("PARQUET", store.get("sink", "wh").orElseThrow().content().get("format"));
    }

    @Test
    void schemaComponentRoundTripsItsTabularFields(@TempDir Path root) throws Exception {
        // probe: a schema's tabular raw.fields must survive the toToon→load round-trip
        ComponentStore store = new ComponentStore(root);
        Map<String, Object> schema = Map.of(
                "partitionKey", "EVENT_DATE",
                "raw", Map.of("name", "orders", "format", "CSV",
                        "fields", List.of(
                                Map.of("name", "ID", "selector", "0", "type", "VARCHAR"),
                                Map.of("name", "AMT", "selector", "1", "type", "DOUBLE"))));
        store.write("schema", "orders", schema);

        ComponentRegistry.Component c = store.get("schema", "orders").orElseThrow();
        assertEquals("EVENT_DATE", c.content().get("partitionKey"));
        Object raw = c.content().get("raw");
        assertInstanceOf(Map.class, raw);
        Object fields = ((Map<?, ?>) raw).get("fields");
        assertInstanceOf(List.class, fields);
        assertEquals(2, ((List<?>) fields).size(), "both tabular field rows survive the round-trip");
    }

    @Test
    void rejectsUnknownTypeUnsafeIdAndConnection(@TempDir Path root) {
        ComponentStore store = new ComponentStore(root);
        assertThrows(IllegalArgumentException.class, () -> store.list("bogus"));
        assertThrows(IllegalArgumentException.class, () -> store.list("connection"));   // has its own CRUD
        assertThrows(IllegalArgumentException.class, () -> store.write("grammar", "../escape", Map.of("x", 1)));
        assertThrows(IllegalArgumentException.class, () -> store.write("grammar", "bad/slash", Map.of("x", 1)));
    }
}
