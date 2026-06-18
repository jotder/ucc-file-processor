package com.gamma.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link FlowStore}: persist / read / delete / list authored {@code *_flow.toon}, jailed + atomic (T19). */
class FlowStoreTest {

    private static FlowGraph flow(String name) {
        return new FlowGraph(name, true,
                List.of(FlowNode.of("acq", "acquisition"),
                        new FlowNode("sink", "sink.persistent", "Out", null,
                                Map.of(FlowStores.CONFIG_STORE, "out"), null)),
                List.of(FlowEdge.data("acq", "sink")));
    }

    @Test
    void writeReadDeleteRoundTrip(@TempDir Path root) throws Exception {
        FlowStore store = new FlowStore(root);
        store.write("orders_flow", flow("orders_flow"));
        assertTrue(Files.exists(root.resolve("orders_flow.toon")));

        FlowGraph back = store.get("orders_flow").orElseThrow();
        assertEquals("orders_flow", back.name());
        assertEquals(2, back.nodes().size());
        assertEquals("out", back.byId().get("sink").cfg(FlowStores.CONFIG_STORE));

        assertTrue(store.exists("orders_flow"));
        assertTrue(store.delete("orders_flow"));
        assertFalse(store.exists("orders_flow"));
        assertFalse(store.delete("orders_flow"));   // already gone
    }

    @Test
    void listsAuthoredFlows(@TempDir Path root) throws Exception {
        FlowStore store = new FlowStore(root);
        store.write("a", flow("a"));
        store.write("b", flow("b"));
        List<FlowGraph> all = store.list();
        assertEquals(2, all.size());
        assertEquals(List.of("a", "b"), all.stream().map(FlowGraph::name).sorted().toList());
    }

    @Test
    void rejectsUnsafeIds(@TempDir Path root) {
        FlowStore store = new FlowStore(root);
        assertThrows(IllegalArgumentException.class, () -> store.exists("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.write("bad/slash", flow("x")));
    }
}
