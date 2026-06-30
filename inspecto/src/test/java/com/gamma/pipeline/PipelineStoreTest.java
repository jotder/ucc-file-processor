package com.gamma.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link PipelineStore}: persist / read / delete / list authored {@code *_flow.toon}, jailed + atomic (T19). */
class PipelineStoreTest {

    private static PipelineGraph flow(String name) {
        return new PipelineGraph(name, true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        new PipelineNode("sink", "sink.persistent", "Out", null,
                                Map.of(PipelineStores.CONFIG_STORE, "out"), null)),
                List.of(PipelineEdge.data("acq", "sink")));
    }

    @Test
    void writeReadDeleteRoundTrip(@TempDir Path root) throws Exception {
        PipelineStore store = new PipelineStore(root);
        store.write("orders_flow", flow("orders_flow"));
        assertTrue(Files.exists(root.resolve("orders_flow.toon")));

        PipelineGraph back = store.get("orders_flow").orElseThrow();
        assertEquals("orders_flow", back.name());
        assertEquals(2, back.nodes().size());
        assertEquals("out", back.byId().get("sink").cfg(PipelineStores.CONFIG_STORE));

        assertTrue(store.exists("orders_flow"));
        assertTrue(store.delete("orders_flow"));
        assertFalse(store.exists("orders_flow"));
        assertFalse(store.delete("orders_flow"));   // already gone
    }

    @Test
    void listsAuthoredFlows(@TempDir Path root) throws Exception {
        PipelineStore store = new PipelineStore(root);
        store.write("a", flow("a"));
        store.write("b", flow("b"));
        List<PipelineGraph> all = store.list();
        assertEquals(2, all.size());
        assertEquals(List.of("a", "b"), all.stream().map(PipelineGraph::name).sorted().toList());
    }

    @Test
    void rejectsUnsafeIdsOnWriteButTreatsThemAsAbsentOnRead(@TempDir Path root) {
        PipelineStore store = new PipelineStore(root);
        // writes stay strict — an unsafe id is rejected loudly
        assertThrows(IllegalArgumentException.class, () -> store.write("bad/slash", flow("x")));
        // reads are tolerant — an unsafe/unresolvable id is simply "not present" (no throw),
        // so the read control-plane routes answer 404 rather than 500.
        assertFalse(store.exists("../escape"));
        assertFalse(store.exists("__nope__"));
        assertTrue(store.get("../escape").isEmpty());
    }
}
