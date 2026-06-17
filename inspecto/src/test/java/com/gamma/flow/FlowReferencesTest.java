package com.gamma.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FlowReferences} (T8 — reference scan / safe-delete) and the {@link ComponentRegistry#referencedPaths}
 * primitive (T7 — the component files a flow's mtime fingerprint must include).
 */
class FlowReferencesTest {

    private static FlowNode useNode(String id, String type, String use) {
        return new FlowNode(id, type, null, null, Map.of(), use);
    }

    @Test
    void scansUseRefsAndAnswersReferencedBy() {
        FlowGraph a = new FlowGraph("flow_a", true,
                List.of(useNode("acq", "acquisition", "connection/sftp-prod"),
                        useNode("p", "parser", "grammar/pipe")), List.of());
        FlowGraph b = new FlowGraph("flow_b", true,
                List.of(useNode("p", "parser", "grammar/pipe"),
                        FlowNode.of("m", "transform.map")), List.of());

        assertEquals(Map.of("acq", "connection/sftp-prod", "p", "grammar/pipe"), FlowReferences.uses(a));
        assertEquals(Set.of("connection/sftp-prod", "grammar/pipe"), FlowReferences.referencedComponents(a));

        // "what references this?" — the safe-delete guard
        assertEquals(List.of("flow_a", "flow_b"), FlowReferences.referencedBy("grammar/pipe", List.of(a, b)));
        assertEquals(List.of("flow_a"), FlowReferences.referencedBy("connection/sftp-prod", List.of(a, b)));
        assertTrue(FlowReferences.isReferenced("grammar/pipe", List.of(a, b)));
        assertFalse(FlowReferences.isReferenced("schema/nope", List.of(a, b)));   // unreferenced ⇒ safe to delete
        assertTrue(FlowReferences.referencedBy(null, List.of(a, b)).isEmpty());
    }

    @Test
    void registryResolvesReferencedComponentPaths(@TempDir Path root) throws Exception {
        Path gdir = root.resolve("grammars");
        Files.createDirectories(gdir);
        Files.writeString(gdir.resolve("pipe.toon"), "name: pipe\ndelimiter: \"|\"\n");
        ComponentRegistry reg = ComponentRegistry.scan(root);

        FlowGraph g = new FlowGraph("f", true,
                List.of(useNode("p", "parser", "grammar/pipe"),
                        useNode("x", "parser", "ingester/com.acme.Plugin"),   // unresolvable class ref → contributes nothing
                        FlowNode.of("m", "transform.map")), List.of());

        Set<Path> paths = reg.referencedPaths(g);
        assertEquals(1, paths.size());
        assertTrue(paths.iterator().next().endsWith(Path.of("grammars", "pipe.toon")));
    }
}
