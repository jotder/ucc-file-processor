/**
 * Flat-key ↔ nested-map plumbing between `<inspecto-schema-form>` and the Stage-1 pipeline TOON
 * blocks. Flat keys use a `__` path separator (`duplicate__mode` ⇒ `duplicate.mode`) because
 * Angular's `formControlName`/`FormGroup.get` treat a literal `.` as a nested-group path.
 * Framework-free and lossless for keys the form does not know about: {@link nestKeys} output is
 * deep-merged over the existing block by {@link mergeBlock}, so hand-authored keys survive a
 * guided save.
 */

/** The flat-key path separator (a literal `.` would collide with Angular form-path semantics). */
export const KEY_SEP = '__';

/** Keys whose value is a comma-separated string in the form but a string list in the TOON. */
const LIST_KEYS = new Set(['include', 'exclude', 'null_strings']);

const isBlank = (v: unknown): boolean => v === undefined || v === null || v === '';

/** Flatten a nested config block to flat keys (lists join to comma strings) for a form's `initial`. */
export function flattenBlock(block: Record<string, unknown> | undefined, prefix = ''): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(block ?? {})) {
        const key = prefix ? `${prefix}${KEY_SEP}${k}` : k;
        if (Array.isArray(v)) out[key] = v.join(',');
        else if (v !== null && typeof v === 'object') Object.assign(out, flattenBlock(v as Record<string, unknown>, key));
        else out[key] = v;
    }
    return out;
}

/** Nest a form's flat-keyed values into a config block; blanks are pruned, list keys split. */
export function nestKeys(flat: Record<string, unknown>): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const [flatKey, raw] of Object.entries(flat)) {
        if (isBlank(raw)) continue;
        const segs = flatKey.split(KEY_SEP);
        const leaf = segs[segs.length - 1];
        const value = LIST_KEYS.has(leaf)
            ? String(raw)
                  .split(',')
                  .map((s) => s.trim())
                  .filter(Boolean)
            : raw;
        if (Array.isArray(value) && value.length === 0) continue;
        let cur = out;
        for (const seg of segs.slice(0, -1)) {
            cur = (cur[seg] ??= {}) as Record<string, unknown>;
        }
        cur[leaf] = value;
    }
    return out;
}

/**
 * Mark the given roots for deletion when absent from `nested` — so clearing a form field
 * actually removes its key under {@link mergeBlock}'s deep merge, while keys the form never
 * owned (hand-authored TOON) survive untouched. Mutates and returns `nested`.
 */
export function clearMissingRoots(nested: Record<string, unknown>, roots: Iterable<string>): Record<string, unknown> {
    for (const r of roots) {
        if (!(r in nested)) nested[r] = undefined;
    }
    return nested;
}

/**
 * Deep-merge `patch` over `base` (maps only — a patch list/scalar replaces). A patch key with an
 * `undefined` value deletes. Returns a new object; inputs are not mutated.
 */
export function mergeBlock(
    base: Record<string, unknown> | undefined,
    patch: Record<string, unknown>,
): Record<string, unknown> {
    const out: Record<string, unknown> = { ...(base ?? {}) };
    for (const [k, v] of Object.entries(patch)) {
        if (v === undefined) {
            delete out[k];
        } else if (
            v !== null && typeof v === 'object' && !Array.isArray(v) &&
            out[k] !== null && typeof out[k] === 'object' && !Array.isArray(out[k])
        ) {
            out[k] = mergeBlock(out[k] as Record<string, unknown>, v as Record<string, unknown>);
        } else {
            out[k] = v;
        }
    }
    return out;
}
