package com.gamma.notify;

/**
 * Lifecycle state of an in-app {@link Notification}. Unlike an append-only event, a notification is a
 * mutable record: it starts {@link #UNREAD}, is dimmed to {@link #READ} when the user views it, and is
 * removed from the active feed (the user's "delete") by moving to {@link #ARCHIVED}.
 *
 * @since 4.4.0
 */
public enum NotificationState {
    /** New and highlighted; counts toward the unread badge. */
    UNREAD,
    /** Seen by the user; dimmed in the feed, no longer counted. */
    READ,
    /** Removed from the active feed (the user's "delete"); kept out of {@code recent()}. */
    ARCHIVED
}
