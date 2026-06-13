package com.gamma.ops;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * In-memory {@link ObjectStore} — the <b>lean default</b> ({@code -Dobjects.backend=memory}), mirroring
 * {@link com.gamma.event.InMemoryEventStore} as the zero-extra-infrastructure backend so the Alert
 * Center works out of the box and tests stay light. Operational objects are low-volume (one per fired
 * alert / opened issue), so a plain map keyed by id is ample; durable retention is
 * {@code DbObjectStore}'s job.
 *
 * <p>Thread-safe: all access is {@code synchronized} on the map.
 *
 * @since 4.3.0
 */
@com.gamma.api.PublicApi(since = "4.3.0")
public final class InMemoryObjectStore implements ObjectStore {

    private final Map<String, OperationalObject> byId = new LinkedHashMap<>();

    @Override
    public synchronized OperationalObject create(OperationalObject obj) {
        if (byId.containsKey(obj.id()))
            throw new IllegalStateException("object already exists: " + obj.id());
        byId.put(obj.id(), obj);
        return obj;
    }

    @Override
    public synchronized Optional<OperationalObject> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public synchronized OperationalObject update(OperationalObject obj) {
        if (!byId.containsKey(obj.id()))
            throw new NoSuchElementException("no object with id '" + obj.id() + "'");
        byId.put(obj.id(), obj);
        return obj;
    }

    @Override
    public synchronized List<OperationalObject> query(ObjectQuery query) {
        List<OperationalObject> matched = new ArrayList<>();
        for (OperationalObject o : byId.values()) {
            if (query.matches(o)) matched.add(o);
        }
        matched.sort(Comparator.comparingLong(OperationalObject::createdAt).reversed());   // newest-first
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.limit(), matched.size());
        return new ArrayList<>(matched.subList(from, to));
    }

    /** Current object count (diagnostics/tests). */
    public synchronized int size() {
        return byId.size();
    }
}
