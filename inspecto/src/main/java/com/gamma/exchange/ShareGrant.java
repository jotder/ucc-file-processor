package com.gamma.exchange;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A grant of read-only use of one owner Space's Dataset/Widget to one consumer Space — the record that
 * mediates all cross-Space sharing. Nothing is discoverable or usable across Spaces without an active
 * grant (fail-closed). One grant exists per {@code (kind, item, owner, consumer)} quad, keyed by
 * {@link #idFor}.
 *
 * <p>Lifecycle ({@link #status}): {@code requested → active | denied}; an active grant can later be
 * {@code revoked} or {@code expired}. Every transition is audited and signalled at the HTTP edge.
 *
 * @param mode      {@code snapshot} (default) or {@code live} — how data is delivered (S2/S3)
 * @param pin       optional version pin ({@code "v12"}); {@code null} = track current
 * @param expiresAt optional epoch-millis expiry; {@code null} = no expiry
 */
public record ShareGrant(String id, String kind, String item, String owner, String consumer,
                         String mode, String status,
                         String requestedBy, long requestedAt, String purpose,
                         String approvedBy, long approvedAt,
                         String pin, Long expiresAt) {

    // Status values.
    public static final String REQUESTED = "requested";
    public static final String ACTIVE    = "active";
    public static final String DENIED    = "denied";
    public static final String REVOKED   = "revoked";
    public static final String EXPIRED   = "expired";

    // Delivery modes.
    public static final String SNAPSHOT = "snapshot";
    public static final String LIVE     = "live";

    /**
     * Deterministic grant id for a {@code (kind, item, owner, consumer)} quad. Uses {@code ~} as the
     * separator — outside the charset of every part (SpaceId {@code [a-z0-9-]}, component id
     * {@code [A-Za-z0-9._-]}, {@code kind} a fixed word) so the id is unambiguous and URL-path-safe.
     */
    public static String idFor(String kind, String item, String owner, String consumer) {
        return consumer + "~" + owner + "~" + kind + "~" + item;
    }

    /** Map/JSON-ready view (stable key order); {@code null} optionals are preserved as JSON null. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("item", item);
        m.put("owner", owner);
        m.put("consumer", consumer);
        m.put("mode", mode);
        m.put("status", status);
        m.put("requestedBy", requestedBy == null ? "" : requestedBy);
        m.put("requestedAt", requestedAt);
        m.put("purpose", purpose == null ? "" : purpose);
        m.put("approvedBy", approvedBy);
        m.put("approvedAt", approvedAt);
        m.put("pin", pin);
        m.put("expiresAt", expiresAt);
        return m;
    }

    static ShareGrant fromMap(Map<String, Object> m) {
        Object exp = m.get("expiresAt");
        return new ShareGrant(
                Ledger.str(m, "id"), Ledger.str(m, "kind"), Ledger.str(m, "item"),
                Ledger.str(m, "owner"), Ledger.str(m, "consumer"),
                Ledger.str(m, "mode"), Ledger.str(m, "status"),
                Ledger.str(m, "requestedBy"), Ledger.asLong(m.get("requestedAt")), Ledger.str(m, "purpose"),
                Ledger.str(m, "approvedBy"), Ledger.asLong(m.get("approvedAt")),
                Ledger.str(m, "pin"), exp == null ? null : Ledger.asLong(exp));
    }
}
