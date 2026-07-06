/**
 * PKCE (RFC 7636) helpers for the Standard-edition OIDC Authorization-Code flow (W6d). Pure Web-Crypto
 * functions — no Angular, no state — so they unit-test with no backend. The SPA is a public client
 * (no secret): the code verifier proves the token exchange came from the same client that started the
 * redirect. Kept tiny and dependency-free (no `angular-oauth2-oidc`) — the backend BFF does the actual
 * token exchange (POST /auth/exchange), so the browser never handles a client secret or refresh token.
 */

/** A cryptographically-random code verifier: 32 bytes → 43-char base64url (RFC 7636 §4.1 range). */
export function randomVerifier(): string {
    const bytes = new Uint8Array(32);
    globalThis.crypto.getRandomValues(bytes);
    return base64Url(bytes.buffer);
}

/** An opaque random value for the OAuth `state` parameter (CSRF defense on the redirect round-trip). */
export function randomState(): string {
    const bytes = new Uint8Array(16);
    globalThis.crypto.getRandomValues(bytes);
    return base64Url(bytes.buffer);
}

/** The S256 code challenge for a verifier: base64url(SHA-256(verifier)) (RFC 7636 §4.2). */
export async function challengeFromVerifier(verifier: string): Promise<string> {
    const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
    return base64Url(digest);
}

/** base64url (no padding) of raw bytes — the encoding PKCE + JOSE use. */
function base64Url(buffer: ArrayBuffer): string {
    let binary = '';
    const bytes = new Uint8Array(buffer);
    for (const b of bytes) binary += String.fromCharCode(b);
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
