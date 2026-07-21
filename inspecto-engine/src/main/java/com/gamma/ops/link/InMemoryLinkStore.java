package com.gamma.ops.link;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link LinkStore} — the lean default (mirrors {@link com.gamma.ops.InMemoryObjectStore} and
 * {@link com.gamma.event.InMemoryEventStore}). Append-only; reads return newest-first. All access is
 * guarded on the instance monitor (low-volume traffic), so it is safe to share across threads.
 *
 * @since 4.5.0
 */
@com.gamma.api.PublicApi(since = "4.5.0")
public final class InMemoryLinkStore implements LinkStore {

    /** Insertion-ordered; reads iterate in reverse for newest-first. Guarded by {@code this}. */
    private final List<ObjectLink> links = new ArrayList<>();

    @Override
    public synchronized ObjectLink add(ObjectLink link) {
        links.add(link);
        return link;
    }

    @Override
    public synchronized boolean remove(String from, String to, String relationship) {
        return links.removeIf(l -> l.fromId().equals(from) && l.toId().equals(to)
                && l.relationship().equalsIgnoreCase(relationship));
    }

    @Override
    public synchronized List<ObjectLink> incident(String objectId) {
        List<ObjectLink> out = new ArrayList<>();
        for (int i = links.size() - 1; i >= 0; i--) {
            ObjectLink l = links.get(i);
            if (l.fromId().equals(objectId) || l.toId().equals(objectId)) out.add(l);
        }
        return out;
    }

    @Override
    public synchronized List<ObjectLink> all(int limit) {
        int cap = Math.max(0, limit);
        List<ObjectLink> out = new ArrayList<>();
        for (int i = links.size() - 1; i >= 0 && out.size() < cap; i--) out.add(links.get(i));
        return out;
    }
}
