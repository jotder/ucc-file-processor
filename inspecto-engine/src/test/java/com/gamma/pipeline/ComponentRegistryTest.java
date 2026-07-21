package com.gamma.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ComponentRegistry} (T6): scanning {@code registry/<type>/<name>.toon} into a {@code use:}-resolvable
 * index keyed by in-file identity, and the local-overrides-component overlay that powers dedup.
 */
class ComponentRegistryTest {

    private static void writeComponent(Path root, String dir, String file, String toon) throws Exception {
        Path d = root.resolve(dir);
        Files.createDirectories(d);
        Files.writeString(d.resolve(file), toon);
    }

    @Test
    void scanIndexesByInFileIdentityAndResolvesUseRefs(@TempDir Path root) throws Exception {
        writeComponent(root, "grammars", "pipe-delimited.toon", "name: pipe-delimited\ndelimiter: \"|\"\nhas_header: true\n");
        writeComponent(root, "transforms", "daily.toon", "name: daily-partition\npartitionKey: EVENT_DATE\n"); // name != filename
        writeComponent(root, "schemas", "cdr.toon", "name: cdr-v3\nnote: hello\n");                            // name != filename
        writeComponent(root, "connections", "sftp.toon", "id: sftp-prod\nhost: example.com\n");               // id, not name

        ComponentRegistry reg = ComponentRegistry.scan(root);

        // resolves by in-file identity (type from the dir, name from file content) — not the filename
        assertTrue(reg.isKnown("grammar/pipe-delimited"));
        assertEquals("|", reg.resolve("grammar/pipe-delimited").orElseThrow().content().get("delimiter"));
        assertTrue(reg.isKnown("transform/daily-partition"));
        assertFalse(reg.isKnown("transform/daily"));      // filename stem is not the identity when name: is present
        assertTrue(reg.isKnown("schema/cdr-v3"));
        assertTrue(reg.isKnown("connection/sftp-prod"));  // id: used as the name when no name:
        assertFalse(reg.isKnown("grammar/missing"));

        assertEquals(1, reg.ofType("grammar").size());
        assertEquals(4, reg.all().size());
    }

    @Test
    void effectiveConfigOverlaysLocalOverComponent(@TempDir Path root) throws Exception {
        writeComponent(root, "grammars", "g.toon", "name: pipe\ndelimiter: \"|\"\nhas_header: true\n");
        ComponentRegistry reg = ComponentRegistry.scan(root);

        // a node references the grammar but overrides has_header locally + adds a local-only key
        PipelineNode node = new PipelineNode("p", "parser", null, null, Map.of("has_header", false, "extra", "x"), "grammar/pipe");
        Map<String, Object> eff = reg.effectiveConfig(node);
        assertEquals("|", eff.get("delimiter"));     // inherited from the component
        assertEquals(false, eff.get("has_header"));  // local override wins
        assertEquals("x", eff.get("extra"));         // local-only key kept

        // no use: ⇒ local config unchanged
        assertEquals(Map.of("k", 1), reg.effectiveConfig(PipelineNode.of("q", "parser", Map.of("k", 1))));

        // unresolved use: ⇒ degrades to local config (caller flags the dangling ref)
        PipelineNode dangling = new PipelineNode("r", "parser", null, null, Map.of("k", 2), "grammar/nope");
        assertEquals(Map.of("k", 2), reg.effectiveConfig(dangling));
    }

    @Test
    void emptyRegistryResolvesNothing() {
        ComponentRegistry reg = ComponentRegistry.empty();
        assertFalse(reg.isKnown("grammar/x"));
        assertTrue(reg.resolve("grammar/x").isEmpty());
        assertTrue(reg.all().isEmpty());
    }
}
