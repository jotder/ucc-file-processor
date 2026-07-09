/**
 * Cross-space shared references (Exchange, §3). A consumer binds another space's Dataset/Widget by a
 * physicalRef of the form `shared/<owner>/<item>` — the one string that spans spaces. These helpers let
 * pickers and hosts recognise such refs and show a scope badge, without each feature re-parsing the shape.
 */

const SHARED_PREFIX = 'shared/';

/** True when a physicalRef points at another space's shared item (`shared/<owner>/<item>`). */
export function isSharedRef(ref: string | null | undefined): boolean {
    return typeof ref === 'string' && ref.startsWith(SHARED_PREFIX) && ref.split('/').length >= 3;
}

/** Parse a `shared/<owner>/<item>` ref into its parts, or null when it isn't a shared ref. */
export function parseSharedRef(ref: string | null | undefined): { owner: string; item: string } | null {
    if (!isSharedRef(ref)) return null;
    const [, owner, ...rest] = (ref as string).split('/');
    const item = rest.join('/');
    return owner && item ? { owner, item } : null;
}
