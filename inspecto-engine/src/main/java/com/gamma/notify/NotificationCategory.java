package com.gamma.notify;

import java.util.Optional;

/**
 * The catalog of notification categories the preference grid exposes. A category is the unit a user
 * maps to delivery channels (in-app / email), and the {@link Notification#category()} a rule stamps.
 *
 * <ul>
 *   <li>{@link #available} — whether this product version actually emits the category. {@code PIPELINE},
 *       {@code JOB} and {@code OPS} fire today; {@code COLLABORATION} and {@code SECURITY} are shown in
 *       the grid but inert until their (future) trigger modules land.</li>
 *   <li>{@link #critical} — a non-mutable transactional class (e.g. security alerts) that bypasses
 *       opt-out: it is always delivered and its toggles are locked. Latent in the auth-free core (no
 *       security triggers yet), but enforced now so an edition that adds them inherits the guarantee.</li>
 * </ul>
 *
 * @since 4.4.0
 */
public enum NotificationCategory {

    PIPELINE("pipeline", "Pipeline alerts", false, true),
    JOB("job", "Job alerts", false, true),
    OPS("ops", "Operational alerts", false, true),
    COLLABORATION("collaboration", "Collaboration & comments", false, false),
    SECURITY("security", "Security & passwords", true, false);

    private final String id;
    private final String label;
    private final boolean critical;
    private final boolean available;

    NotificationCategory(String id, String label, boolean critical, boolean available) {
        this.id = id;
        this.label = label;
        this.critical = critical;
        this.available = available;
    }

    public String id() { return id; }
    public String label() { return label; }
    /** A non-mutable category: always delivered, toggles locked (bypasses opt-out). */
    public boolean critical() { return critical; }
    /** Whether this version actually emits the category (false ⇒ shown but inert in the grid). */
    public boolean available() { return available; }

    /** The category with this id, if known. */
    public static Optional<NotificationCategory> byId(String id) {
        if (id == null) return Optional.empty();
        for (NotificationCategory c : values()) if (c.id.equals(id)) return Optional.of(c);
        return Optional.empty();
    }
}
