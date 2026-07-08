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
 * @param dataset     for a {@code widget} offer, the id of the Dataset its query binds (its grant travels
 *                    with the widget — §3.5); {@code null} for a Dataset offer
 */
public record Offer(String kind, String item, String owner, String description,
                    Map<String, Object> resultSet, String offeredBy, long offeredAt, String dataset) {

    public Offer {
        resultSet = resultSet == null ? Map.of() : Map.copyOf(resultSet);
    }

    /** A Dataset offer (no bound-dataset link). */
    public Offer(String kind, String item, String owner, String description,
                 Map<String, Object> resultSet, String offeredBy, long offeredAt) {
        this(kind, item, owner, description, resultSet, offeredBy, offeredAt, null);
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
        m.put("dataset", dataset);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Offer fromMap(Map<String, Object> m) {
        Object rs = m.get("resultSet");
        return new Offer(
                Ledger.str(m, "kind"), Ledger.str(m, "item"), Ledger.str(m, "owner"),
                Ledger.str(m, "description"),
                rs instanceof Map ? (Map<String, Object>) rs : Map.of(),
                Ledger.str(m, "offeredBy"), Ledger.asLong(m.get("offeredAt")), Ledger.str(m, "dataset"));
    }
}
