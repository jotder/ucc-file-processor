package com.gamma.flow;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives how flows are <b>superimposed</b> over a shared data store, from config/metadata alone —
 * no {@code on_pipeline} name-coupling. The model (decided 2026-06-17, doc §3.8/§15 T4): a pipeline
 * and a downstream job/enrichment are <b>separate</b> {@link FlowGraph}s; each declares the data
 * store(s) it <b>produces</b> (a {@code sink} node's {@link #CONFIG_STORE}) and <b>consumes</b> (a
 * node's {@link #CONFIG_SOURCE_STORE}). The producer→consumer topology is then <em>derived</em> by
 * matching a consumer's source store to a producer's output store — so querying the configurations is
 * enough to understand how a job is layered over a pipeline's output (the join is the store, §3.8).
 *
 * <p>This is the engine-side basis for the combined pipeline+job visualisation (T24): the shared store
 * is the node where two flows meet.
 */
@PublicApi(since = "4.3.0")
public final class FlowStores {

    /** Config key on a {@code sink} node naming the data store it writes (the producer side). */
    public static final String CONFIG_STORE = "store";
    /** Config key on a node naming the data store it reads at rest (the consumer side; jobs/enrichment). */
    public static final String CONFIG_SOURCE_STORE = "source_store";

    private FlowStores() {}

    /** Data stores this graph produces — the {@code store} of every {@code sink} node that declares one. */
    public static Set<String> produced(FlowGraph g) {
        Set<String> out = new LinkedHashSet<>();
        for (Produced p : producedStores(g)) out.add(p.store());
        return out;
    }

    /**
     * Every store this graph produces, with the producing {@code sink} node id and its subtype — so the
     * deletion fence (§3.8 rule 4) and the visualiser can tell a <em>resting</em> store
     * ({@code sink.persistent}/{@code sink.materialized}) from a non-persistent {@code sink.view}. Sinks
     * are recognised by {@link NodeCategory#SINK} (not the literal type string), so the sink subtypes and
     * any plugin-contributed sink are all included; a sink with no {@link #CONFIG_STORE} (e.g. a
     * quarantine) is excluded.
     */
    public static List<Produced> producedStores(FlowGraph g) {
        List<Produced> out = new ArrayList<>();
        for (FlowNode n : g.nodes()) {
            if (!FlowNodeTypes.isCategory(n.type(), NodeCategory.SINK)) continue;
            Object s = n.cfg(CONFIG_STORE);
            if (s != null && !s.toString().isBlank()) out.add(new Produced(n.id(), s.toString(), n.type()));
        }
        return out;
    }

    /** A store a {@code sink} node produces, tagged with the sink subtype that declared it. */
    public record Produced(String node, String store, String sinkType) {
        /** Whether bytes actually rest for this store (persistent / materialized) vs a logical {@code sink.view}. */
        public boolean restsOnDisk() {
            return !sinkType.endsWith(".view");
        }
    }

    /** Data stores this graph consumes at rest — the {@code source_store} of every node that declares one. */
    public static Set<String> consumed(FlowGraph g) {
        Set<String> out = new LinkedHashSet<>();
        for (FlowNode n : g.nodes()) {
            Object s = n.cfg(CONFIG_SOURCE_STORE);
            if (s != null && !s.toString().isBlank()) out.add(s.toString());
        }
        return out;
    }

    /** A derived producer→consumer relationship over a shared store (a cross-flow {@code on_commit} link). */
    public record Link(String producer, String store, String consumer) {}

    /**
     * Superimpose the given flows: for every store a flow {@link #consumed(FlowGraph) consumes}, link it
     * to each flow that {@link #produced(FlowGraph) produces} that store. A flow is never linked to itself.
     * Returns links in (consumer, store, producer) discovery order.
     */
    public static List<Link> superimpose(Collection<FlowGraph> graphs) {
        Map<String, List<String>> producersByStore = new LinkedHashMap<>();
        for (FlowGraph g : graphs) {
            for (String store : produced(g)) {
                producersByStore.computeIfAbsent(store, k -> new ArrayList<>()).add(g.name());
            }
        }
        List<Link> links = new ArrayList<>();
        for (FlowGraph g : graphs) {
            for (String store : consumed(g)) {
                for (String producer : producersByStore.getOrDefault(store, List.of())) {
                    if (!producer.equals(g.name())) links.add(new Link(producer, store, g.name()));
                }
            }
        }
        return links;
    }
}
