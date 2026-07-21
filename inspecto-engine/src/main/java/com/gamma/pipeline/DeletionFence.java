package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deletion fence (§3.8 rule 4). Pipelines and jobs share one store and touch different slices at
 * different times; concurrent <b>append/read</b> on disjoint slices is safe. <b>The one hazard is
 * deletion</b> — a {@code maintenance}/delete job that removes a slice another driver is reading or
 * writing. This fence detects exactly that race: a delete of a store that has an <em>active</em>
 * producer or consumer right now.
 *
 * <p>Keyed by sink kind (§3.1): only a store that <b>rests on disk</b> ({@code sink.persistent} /
 * {@code sink.materialized}) can be a deletion hazard, so a store with no resting producer (e.g. a
 * {@code sink.view}, which persists nothing and is re-derived on demand) is never a conflict. A delete
 * is <em>clear</em> when the store's producers/consumers are all idle — the "quiet window" the model
 * relies on. This is pure over the IR + a running-set; the live wiring ({@code CollectorService}) supplies
 * the running flows and surfaces a {@code STORE_DELETE_CONFLICT} event/alert per conflict.
 */
@PublicApi(since = "4.3.0")
public final class DeletionFence {

    private DeletionFence() {}

    /** A delete of {@code store} that races at least one active (running) producer/consumer of that resting store. */
    public record Conflict(String store, List<String> activeProducers, List<String> activeConsumers) {
        public Conflict {
            activeProducers = List.copyOf(activeProducers);
            activeConsumers = List.copyOf(activeConsumers);
        }
    }

    /** The fence consultation seam injected into the job runtime: conflicts from deleting {@code stores} now. */
    @FunctionalInterface
    public interface Guard {
        List<Conflict> check(Collection<String> stores);
    }

    /**
     * Conflicts that would result from deleting {@code targetStores} given the configured {@code flows} and
     * the names of the flows currently {@code running}. A store with no resting producer is skipped (no
     * bytes to delete); otherwise a conflict is reported when any resting producer — or any consumer — of
     * that store is currently running.
     */
    public static List<Conflict> check(Collection<String> targetStores,
                                       Collection<PipelineGraph> flows, Set<String> running) {
        Map<String, List<String>> restingProducers = new LinkedHashMap<>();
        Map<String, List<String>> consumers = new LinkedHashMap<>();
        for (PipelineGraph g : flows) {
            for (PipelineStores.Produced p : PipelineStores.producedStores(g)) {
                if (p.restsOnDisk())
                    restingProducers.computeIfAbsent(p.store(), k -> new ArrayList<>()).add(g.name());
            }
            for (String s : PipelineStores.consumed(g)) {
                consumers.computeIfAbsent(s, k -> new ArrayList<>()).add(g.name());
            }
        }
        List<Conflict> out = new ArrayList<>();
        for (String store : targetStores) {
            List<String> producers = restingProducers.getOrDefault(store, List.of());
            if (producers.isEmpty()) continue;                       // nothing rests on disk → no deletion hazard
            List<String> activeProducers = producers.stream().filter(running::contains).toList();
            List<String> activeConsumers = consumers.getOrDefault(store, List.of()).stream()
                    .filter(running::contains).toList();
            if (!activeProducers.isEmpty() || !activeConsumers.isEmpty())
                out.add(new Conflict(store, activeProducers, activeConsumers));
        }
        return out;
    }
}
