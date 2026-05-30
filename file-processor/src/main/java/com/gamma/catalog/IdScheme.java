package com.gamma.catalog;

import java.util.Optional;

/**
 * Builds and parses the stable node ids used throughout the metadata graph and the catalog API.
 *
 * <p>Every id is {@code <token>:<rest>}, where the token maps 1:1 to a {@link NodeKind} so an id
 * is self-describing and a route can validate a {@code from} parameter without a graph lookup:
 *
 * <ul>
 *   <li>{@code source:<pipeline>}</li>
 *   <li>{@code schema:<pipeline>/<key|table>}</li>
 *   <li>{@code event:<pipeline>/<key|table>}</li>
 *   <li>{@code col:<pipeline>/<key|table>/<column>}</li>
 *   <li>{@code xform:<enrichName>}</li>
 *   <li>{@code ref:<enrichName>/<refName>}</li>
 *   <li>{@code kpi:<name>}</li>
 *   <li>{@code report:<name>}</li>
 * </ul>
 *
 * <p>Pipeline names, enrichment names, and KPI/report names are already lowercase, underscore-safe
 * identifiers (validated by {@code Identifiers}); segment keys and column names are alphanumeric.
 * Ids may therefore contain {@code /} but not whitespace; route handlers URL-decode path params.
 */
public final class IdScheme {

    private IdScheme() {}

    public static String source(String pipeline) {
        return "source:" + pipeline;
    }

    public static String schema(String pipeline, String keyOrTable) {
        return "schema:" + pipeline + "/" + keyOrTable;
    }

    public static String event(String pipeline, String keyOrTable) {
        return "event:" + pipeline + "/" + keyOrTable;
    }

    public static String column(String pipeline, String keyOrTable, String column) {
        return "col:" + pipeline + "/" + keyOrTable + "/" + column;
    }

    public static String xform(String enrichName) {
        return "xform:" + enrichName;
    }

    public static String reference(String enrichName, String refName) {
        return "ref:" + enrichName + "/" + refName;
    }

    public static String kpi(String name) {
        return "kpi:" + name;
    }

    public static String report(String name) {
        return "report:" + name;
    }

    /** The id token for a kind, e.g. {@code EVENT_TABLE} → {@code "event"}. */
    public static String token(NodeKind kind) {
        return switch (kind) {
            case SOURCE -> "source";
            case RAW_SCHEMA -> "schema";
            case COLUMN -> "col";
            case EVENT_TABLE -> "event";
            case TRANSFORMED_TABLE -> "xform";
            case REFERENCE_TABLE -> "ref";
            case KPI -> "kpi";
            case REPORT -> "report";
        };
    }

    /** The kind implied by an id's leading token, if it is recognized. */
    public static Optional<NodeKind> kindOf(String id) {
        if (id == null) {
            return Optional.empty();
        }
        int colon = id.indexOf(':');
        if (colon <= 0) {
            return Optional.empty();
        }
        String tok = id.substring(0, colon);
        for (NodeKind k : NodeKind.values()) {
            if (token(k).equals(tok)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }
}
