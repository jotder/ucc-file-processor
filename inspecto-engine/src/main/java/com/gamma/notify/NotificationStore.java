package com.gamma.notify;

import java.util.List;
import java.util.Optional;

/**
 * Persistence seam for in-app {@link Notification}s — the feed behind the "bell icon". Mirrors the
 * {@link com.gamma.ops.ObjectStore} pattern (mutable records with real state transitions), not the
 * append-only {@link com.gamma.event.EventStore}: a notification is marked read and archived over its
 * life. Two backends sit behind it — {@link InMemoryNotificationStore} (the lean default) and a future
 * DuckDB-backed store — selected at startup; routes depend only on this interface.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #recent(int)} returns active (non-{@link NotificationState#ARCHIVED}) notifications
 *       newest-first.</li>
 *   <li>{@link #unreadCount()} counts {@link NotificationState#UNREAD} only.</li>
 *   <li>Implementations must be thread-safe — notifications are appended from the event dispatcher
 *       (a virtual-thread worker) and mutated from HTTP request threads.</li>
 * </ul>
 *
 * @since 4.4.0
 */
public interface NotificationStore extends AutoCloseable {

    /** Append a new notification and return it. */
    Notification add(Notification n);

    /** The notification with this id, or empty. */
    Optional<Notification> get(String id);

    /** The newest {@code limit} active (non-archived) notifications, newest-first. */
    List<Notification> recent(int limit);

    /** Count of {@link NotificationState#UNREAD} notifications — the badge count. */
    long unreadCount();

    /** {@code true} when an active (unread) notification with this {@code dedupeKey} already exists —
     *  the collapse check the dispatcher uses to avoid repeating an identical alert. */
    boolean hasActiveDuplicate(String dedupeKey);

    /** Mark one notification read; empty if no such id. */
    Optional<Notification> markRead(String id);

    /** Mark every unread notification read; returns how many changed. */
    int markAllRead();

    /** Archive one notification (the user's "delete"); {@code false} if no such id. */
    boolean archive(String id);

    /** Count notifications created before {@code cutoffMs} (epoch millis), whatever their state — the
     *  {@code notification_prune} dry-run preview. */
    int countPrunable(long cutoffMs);

    /** Permanently forget notifications created before {@code cutoffMs} (epoch millis), whatever their
     *  read/archived state — deliberate time-based forgetting, like the ledger / run-log prunes.
     *  Returns how many were removed. */
    int prune(long cutoffMs);

    /** Release resources (e.g. a DB connection). Idempotent; no-op for in-memory. */
    @Override
    default void close() {}
}
