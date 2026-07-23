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
    void matchesUiHashForNonIntegerFloats() {
        // Non-integer doubles round-trip to the same shortest decimal on both sides (JDK 19+
        // Double.toString and JS number formatting both emit the shortest round-tripping form), so
        // these hashes match content-hash.spec.ts's floating-point parity vectors verbatim.
        // canonicalJson({lat:23.04,lon:88.89}) == '{"lat":23.04,"lon":88.89}'
        assertEquals("54317338419e694fa0b603d0eb3bf179f0762f60c80f3d748d796e446b38ae93",
                ContentHash.of(Map.of("lon", 88.89, "lat", 23.04)));
        // canonicalJson(3.14) == '3.14'
        assertEquals("2efff1261c25d94dd6698ea1047f5c0a7107ca98b0a6c2427ee6614143500215",
                ContentHash.of(3.14));
        // canonicalJson({a:0.1,b:0.5,c:-2.5}) == '{"a":0.1,"b":0.5,"c":-2.5}'
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("c", -2.5);
        mixed.put("a", 0.1);
        mixed.put("b", 0.5);
        assertEquals("0fb5c6cba126819f44b399eba37313188f6eb5bcb8831c2ec7f919e8b5b2f2ed",
                ContentHash.of(mixed));
    }

    @Test
    void pinsIntegerValuedDoubleDivergence() {
        // The one place the two implementations DON'T agree: Jackson keeps a Double 1.0 as "1.0",
        // while JS has no int/double distinction so the UI emits "1" (see content-hash.spec.ts's
        // KNOWN DIVERGENCE vector). Pinned here so the boundary is explicit, not silent — config
        // content the two sides both hash should avoid integer-valued floats (see ContentHash javadoc).
        assertEquals("d0ff5974b6aa52cf562bea5921840c032a860a91a3512f7fe8f768f6bbe005f6",
                ContentHash.of(1.0), "Jackson serialises Double 1.0 as \"1.0\"");
        assertNotEquals("6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b",
                ContentHash.of(1.0), "the UI's hash of the same 1.0 (emitted as \"1\") differs");
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
