import { describe, expect, it } from 'vitest';
import { challengeFromVerifier, randomState, randomVerifier } from './pkce';

describe('pkce (W6d OIDC)', () => {
    it('randomVerifier is base64url in the RFC 7636 length range and unique', () => {
        const a = randomVerifier();
        const b = randomVerifier();
        expect(a).toMatch(/^[A-Za-z0-9_-]+$/); // base64url, no padding
        expect(a.length).toBeGreaterThanOrEqual(43);
        expect(a.length).toBeLessThanOrEqual(128);
        expect(a).not.toEqual(b); // random
    });

    it('challengeFromVerifier matches the RFC 7636 S256 reference vector', async () => {
        // The canonical example from RFC 7636 Appendix B.
        const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
        const challenge = await challengeFromVerifier(verifier);
        expect(challenge).toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
    });

    it('challenge is deterministic for a given verifier', async () => {
        const v = randomVerifier();
        expect(await challengeFromVerifier(v)).toBe(await challengeFromVerifier(v));
    });

    it('randomState is base64url and unique', () => {
        expect(randomState()).toMatch(/^[A-Za-z0-9_-]+$/);
        expect(randomState()).not.toEqual(randomState());
    });
});
