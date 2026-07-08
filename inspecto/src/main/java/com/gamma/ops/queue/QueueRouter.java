package com.gamma.ops.queue;

import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * Picks the concrete assignee when an object is routed to a {@link Queue} (INC-4), per the queue's
 * {@link Queue.Routing} strategy. Pure aside from the persistent round-robin cursor it advances through
 * the {@link QueueStore}; the caller supplies the load function used by {@link Queue.Routing#LEAST_LOADED}.
 *
 * @since 4.9.0
 */
public final class QueueRouter {

    private QueueRouter() {}

    /**
     * The member this assignment should go to, or empty when the queue has no members or is
     * {@link Queue.Routing#MANUAL} (the caller must then require an explicit assignee).
     *
     * @param queue    the target queue
     * @param store    holds the round-robin cursor (advanced for {@code ROUND_ROBIN})
     * @param openLoad open-object count for a candidate member (used only by {@code LEAST_LOADED})
     */
    public static Optional<String> pick(Queue queue, QueueStore store, ToIntFunction<String> openLoad) {
        List<String> members = queue.members();
        if (members.isEmpty()) return Optional.empty();
        return switch (queue.routing()) {
            case MANUAL -> Optional.empty();
            case ROUND_ROBIN -> Optional.of(members.get(store.advanceCursor(queue.id(), members.size())));
            case LEAST_LOADED -> {
                String best = members.getFirst();
                int bestLoad = openLoad.applyAsInt(best);
                for (String m : members.subList(1, members.size())) {
                    int load = openLoad.applyAsInt(m);
                    if (load < bestLoad) { best = m; bestLoad = load; }   // ties keep the earlier member
                }
                yield Optional.of(best);
            }
        };
    }
}
