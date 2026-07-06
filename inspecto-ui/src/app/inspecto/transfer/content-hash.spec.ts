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
