package com.gamma.catalog;

/**
 * A directed edge between two {@link MetadataNode}s, identified by their stable ids.
 *
 * @param from source node id (see {@link IdScheme})
 * @param to   target node id
 * @param kind the relationship (see {@link EdgeKind})
 */
public record MetadataEdge(String from, String to, EdgeKind kind) {}
