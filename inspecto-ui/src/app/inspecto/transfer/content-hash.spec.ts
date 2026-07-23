import { describe, expect, it } from 'vitest';
import { canonicalJson, hashContent, sha256Hex } from './content-hash';

describe('sha256Hex', () => {
    it('matches the RFC known-answer vectors', () => {
        expect(sha256Hex('')).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
        expect(sha256Hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
        // crosses the 55/56-byte padding boundary (a second block)
        expect(sha256Hex('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq'))
            .toBe('248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1');
    });

    it('handles multi-byte UTF-8', () => {
        expect(sha256Hex('café €')).toHaveLength(64);
        expect(sha256Hex('a')).not.toBe(sha256Hex('b'));
    });
});

describe('canonicalJson', () => {
    it('is independent of object key order but preserves array order', () => {
        expect(canonicalJson({ b: 1, a: 2 })).toBe(canonicalJson({ a: 2, b: 1 }));
        expect(canonicalJson({ x: { d: 1, c: 2 } })).toBe('{"x":{"c":2,"d":1}}');
        expect(canonicalJson([3, 1, 2])).toBe('[3,1,2]'); // order significant (tiles/nodes)
    });
});

describe('hashContent', () => {
    it('is stable under key reordering and changes when a value changes', () => {
        expect(hashContent({ name: 'x', priority: 10 })).toBe(hashContent({ priority: 10, name: 'x' }));
        expect(hashContent({ name: 'x', priority: 10 })).not.toBe(hashContent({ name: 'x', priority: 11 }));
    });
});

/**
 * UI↔backend ContentHash conformance. Every pinned hex here is asserted verbatim by the backend's
 * ContentHashTest.java for the same logical value, so the two implementations are locked together: if
 * either side's canonical JSON or digest drifts, its own test breaks. The bundle-v2 provenance
 * `contentHash` (UI) and the ETag/optimistic-lock hash (backend) must agree for the JSON value types
 * config content actually uses.
 */
describe('content-hash — UI↔backend conformance vectors', () => {
    it('object key sorting (parity: ContentHashTest.matchesUiHashForObjectWithSortedKeys)', () => {
        expect(hashContent({ b: 1, a: 'x' })).toBe('cdab067e9f3beb32d1252cfd63e492592fecbf591b0d08cadb24bb17f3864246');
    });

    it('array order + booleans (parity: ContentHashTest.preservesArrayOrderAndBooleans)', () => {
        expect(hashContent({ n: true, list: [3, 1, 2] })).toBe('052056fc446d4e0ac3678ab97574cf3cbfbaba11e668015db4c3f848b0ec2a0b');
    });

    describe('floating-point', () => {
        // Non-integer doubles round-trip to the same shortest decimal on both sides (JS number
        // formatting and JDK 19+ Double.toString both emit the shortest round-tripping form), so these
        // match ContentHashTest.matchesUiHashForNonIntegerFloats verbatim.
        it('agrees with the backend on realistic geo coordinates', () => {
            expect(canonicalJson({ lon: 88.89, lat: 23.04 })).toBe('{"lat":23.04,"lon":88.89}');
            expect(hashContent({ lon: 88.89, lat: 23.04 })).toBe('54317338419e694fa0b603d0eb3bf179f0762f60c80f3d748d796e446b38ae93');
        });

        it('agrees on a bare double and a mix of fractional values', () => {
            expect(hashContent(3.14)).toBe('2efff1261c25d94dd6698ea1047f5c0a7107ca98b0a6c2427ee6614143500215');
            expect(hashContent({ a: 0.1, b: 0.5, c: -2.5 })).toBe('0fb5c6cba126819f44b399eba37313188f6eb5bcb8831c2ec7f919e8b5b2f2ed');
        });

        it('KNOWN DIVERGENCE: an integer-valued double serializes without a decimal here (1.0 → "1")', () => {
            // JS has no int/double distinction — JSON.stringify(1.0) === "1". Jackson keeps a Double as
            // "1.0", so the backend hashes the SAME source value differently (pinned as the java_1.0
            // vector in ContentHashTest.pinsIntegerValuedDoubleDivergence). Config content the two sides
            // both hash should therefore avoid integer-valued floats — see the parity note in
            // ContentHash.java. This vector keeps that boundary explicit rather than silent.
            expect(canonicalJson(1.0)).toBe('1');
            expect(hashContent(1.0)).toBe('6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b');
            expect(hashContent(1.0)).not.toBe('d0ff5974b6aa52cf562bea5921840c032a860a91a3512f7fe8f768f6bbe005f6'); // the backend's "1.0"
        });
    });
});
