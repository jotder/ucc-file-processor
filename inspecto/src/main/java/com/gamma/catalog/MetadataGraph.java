package com.gamma.catalog;

import com.gamma.api.PublicApi;

import java.util.List;

/**
 * A set of {@link MetadataNode}s and the {@link MetadataEdge}s between them — the wire form
 * returned by the catalog graph API. May be the whole catalog or a traversed subgraph.
 *
 * @param nodes the nodes (order is not significant)
 * @param edges the edges among {@link #nodes}
 */
@PublicApi(since = "4.0.0")
public record MetadataGraph(List<MetadataNode> nodes, List<MetadataEdge> edges) {

    public static final MetadataGraph EMPTY = new MetadataGraph(List.of(), List.of());

    public MetadataGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
