package com.gamma.notify;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link NotificationStore} — the lean default, mirroring {@link com.gamma.ops.InMemoryObjectStore}.
 * A single user's feed is low-volume, so a map keyed by id (capped to a bounded number of most-recent
 * entries) is ample; durable retention is a future DB backend's job.
 *
 * <p>Thread-safe: all access is {@code synchronized} on the map.
 *
 * @since 4.4.0
 */
public final class InMemoryNotificationStore implements NotificationStore {

    /** Bound the feed so a long-running service can't grow it without limit (oldest active entries drop). */
    private static final int MAX_ENTRIES = 1000;

    private final LinkedHashMap<String, Notification> byId = new LinkedHashMap<>();

    @Override
    public synchronized Notification add(Notification n) {
        byId.put(n.id(), n);
        if (byId.size() > MAX_ENTRIES) {
            var it = byId.keySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }   // evict oldest (insertion order)
        }
        return n;
    }

    @Override
    public synchronized Optional<Notification> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public synchronized List<Notification> recent(int limit) {
        List<Notification> active = new ArrayList<>();
        for (Notification n : byId.values())
            if (n.state() != NotificationState.ARCHIVED) active.add(n);
        active.sort(Comparator.comparingLong(Notification::ts).reversed());   // newest-first
        return new ArrayList<>(active.subList(0, Math.min(Math.max(0, limit), active.size())));
    }

    @Override
    public synchronized long unreadCount() {
        return byId.values().stream().filter(n -> n.state() == NotificationState.UNREAD).count();
    }

    @Override
    public synchronized boolean hasActiveDuplicate(String dedupeKey) {
        if (dedupeKey == null || dedupeKey.isBlank()) return false;
        for (Notification n : byId.values())
            if (n.state() == NotificationState.UNREAD && dedupeKey.equals(n.dedupeKey())) return true;
        return false;
    }

    @Override
    public synchronized Optional<Notification> markRead(String id) {
        Notification n = byId.get(id);
        if (n == null) return Optional.empty();
        Notification read = n.asRead(System.currentTimeMillis());
        byId.put(id, read);
        return Optional.of(read);
    }

    @Override
    public synchronized int markAllRead() {
        int changed = 0;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Notification> e : byId.entrySet()) {
            if (e.getValue().state() == NotificationState.UNREAD) {
                e.setValue(e.getValue().asRead(now));
                changed++;
            }
        }
        return changed;
    }

    @Override
    public synchronized boolean archive(String id) {
        Notification n = byId.get(id);
        if (n == null) return false;
        byId.put(id, n.asArchived());
        return true;
    }

    @Override
    public synchronized int countPrunable(long cutoffMs) {
        int n = 0;
        for (Notification x : byId.values()) if (x.ts() < cutoffMs) n++;
        return n;
    }

    @Override
    public synchronized int prune(long cutoffMs) {
        int before = byId.size();
        byId.values().removeIf(x -> x.ts() < cutoffMs);
        return before - byId.size();
    }

    /** Current entry count, including archived (diagnostics/tests). */
    public synchronized int size() {
        return byId.size();
    }
}
