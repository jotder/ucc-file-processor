package com.gamma.catalog;

import java.util.Map;

/**
 * A typed node in the metadata graph.
 *
 * <p>Structural fields that vary by {@link NodeKind} (e.g. {@code type}, {@code unit},
 * {@code classification}, {@code grain}, {@code stage}, {@code outputGlob}, {@code producedBy})
 * live in {@link #attrs} so the record stays stable as kinds evolve. {@link #overlay} is
 * {@code null} until {@link MetadataGraphService} hydrates it lazily — the structural graph is
 * assembled and cached without ever touching the audit stores.
 *
 * @param id          stable, URL-safe id (see {@link IdScheme})
 * @param kind        node kind
 * @param label       human-readable label
 * @param description description + provenance (never {@code null}; {@link Description#EMPTY} if none)
 * @param attrs       kind-specific structural attributes (never {@code null})
 * @param overlay     lazily-hydrated operational state, or {@code null} if not yet fetched
 */
public record MetadataNode(String id, NodeKind kind, String label,
                           Description description, Map<String, Object> attrs,
                           OperationalOverlay overlay) {

    public MetadataNode {
        description = description == null ? Description.EMPTY : description;
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
    }

    /** A structural node with no overlay yet. */
    public MetadataNode(String id, NodeKind kind, String label,
                        Description description, Map<String, Object> attrs) {
        this(id, kind, label, description, attrs, null);
    }

    /** A copy of this node with its operational overlay attached. */
    public MetadataNode withOverlay(OperationalOverlay o) {
        return new MetadataNode(id, kind, label, description, attrs, o);
    }

    /** A copy of this node with the given description (used when a provider fills an empty one). */
    public MetadataNode withDescription(Description d) {
        return new MetadataNode(id, kind, label, d, attrs, overlay);
    }
}
