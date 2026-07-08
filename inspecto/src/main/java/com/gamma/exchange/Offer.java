package com.gamma.exchange;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Dataset or Widget an owner Space has listed as shareable in the {@link Exchange} — the metadata a
 * consumer browses in the catalog <em>before</em> any grant exists. It carries only descriptive
 * metadata (never rows): the item's {@code kind}, its owner, a human description and its
 * {@code resultSet} (columns/types/analytic roles — the Result Set that travels with a Dataset so a
 * consumer never needs the producer's ETL-side Schema). Freshness is added by the snapshot refresh Job
 * (S2); at S1 an offer is metadata-only.
 *
 * @param kind        {@code dataset} or {@code widget}
 * @param item        the owner-side component id being offered
 * @param owner       the owning Space id
 * @param description free-text summary shown in the catalog
 * @param resultSet   descriptive Result Set metadata (may be empty until S2 populates it)
 * @param offeredBy   the actor who listed the offer
 * @param offeredAt   epoch millis the offer was listed/last updated
 */
public record Offer(String kind, String item, String owner, String description,
                    Map<String, Object> resultSet, String offeredBy, long offeredAt) {

    public Offer {
        resultSet = resultSet == null ? Map.of() : Map.copyOf(resultSet);
    }

    /** Stable ledger key for an offer — unique per {@code (owner, kind, item)}. */
    public String key() {
        return owner + "~" + kind + "~" + item;
    }

    /** TOON/JSON-ready map (stable key order). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("item", item);
        m.put("owner", owner);
        m.put("description", description == null ? "" : description);
        m.put("resultSet", resultSet);
        m.put("offeredBy", offeredBy == null ? "" : offeredBy);
        m.put("offeredAt", offeredAt);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Offer fromMap(Map<String, Object> m) {
        Object rs = m.get("resultSet");
        return new Offer(
                Ledger.str(m, "kind"), Ledger.str(m, "item"), Ledger.str(m, "owner"),
                Ledger.str(m, "description"),
                rs instanceof Map ? (Map<String, Object>) rs : Map.of(),
                Ledger.str(m, "offeredBy"), Ledger.asLong(m.get("offeredAt")));
    }
}
