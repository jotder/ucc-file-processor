package com.gamma.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lenient JSON serialisation for a {@code String → String} attribute map — the shared idiom the
 * operational stores (objects, notes) and the event store use to persist an {@code attributes}
 * column. Both directions are <b>total</b>: a serialisation failure falls back to an empty JSON
 * object and a parse failure (or null/blank input) to an empty map, so a malformed value never
 * breaks a read or a write. Insertion order is preserved on parse ({@link LinkedHashMap}).
 */
public final class JsonAttributes {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> ATTRS = new TypeReference<>() {};
    private static final TypeReference<LinkedHashMap<String, Object>> PAYLOAD = new TypeReference<>() {};

    private JsonAttributes() {}

    /** Serialise an attribute map to a JSON object string; a {@code null} map or any failure → {@code "{}"}. */
    public static String toJson(Map<String, String> attrs) {
        try {
            return JSON.writeValueAsString(attrs == null ? Map.of() : attrs);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Parse a JSON object string to an attribute map; {@code null}/blank input or any failure → empty map. */
    public static Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, ATTRS);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Serialise a structured payload map (native {@code Map<String,Object>}, arbitrary nesting) to JSON. */
    public static String toPayloadJson(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Parse a JSON object string to a structured payload map; {@code null}/blank input or any failure → empty map. */
    public static Map<String, Object> fromPayloadJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, PAYLOAD);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
