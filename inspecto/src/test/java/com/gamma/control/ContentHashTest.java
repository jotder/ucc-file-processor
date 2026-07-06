package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity + determinism guard for {@link ContentHash} (W3). The pinned hex vectors are SHA-256 of the
 * canonical JSON the UI's {@code transfer/content-hash.ts} would produce for the same value — so the
 * backend ETag/optimistic-lock hash and the UI's bundle-v2 {@code contentHash} agree for the JSON
 * types config content actually uses (string / integer / boolean / array / nested object).
 */
class ContentHashTest {

    @Test
    void matchesUiHashForObjectWithSortedKeys() {
        // canonicalJson({b:1,a:"x"}) == '{"a":"x","b":1}'  → SHA-256 hex:
        assertEquals("cdab067e9f3beb32d1252cfd63e492592fecbf591b0d08cadb24bb17f3864246",
                ContentHash.of(Map.of("b", 1, "a", "x")));
    }

    @Test
    void preservesArrayOrderAndBooleans() {
        // canonicalJson({list:[3,1,2],n:true}) == '{"list":[3,1,2],"n":true}'  → SHA-256 hex:
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("n", true);
        content.put("list", List.of(3, 1, 2));   // array order is significant — preserved, not sorted
        assertEquals("052056fc446d4e0ac3678ab97574cf3cbfbaba11e668015db4c3f848b0ec2a0b",
                ContentHash.of(content));
    }

    @Test
    void keyOrderDoesNotAffectHash() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", "x");
        ab.put("b", 1);
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", 1);
        ba.put("a", "x");
        assertEquals(ContentHash.of(ab), ContentHash.of(ba),
                "canonical JSON sorts keys, so insertion order is irrelevant");
    }
}
