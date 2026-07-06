/**
 * R3 of the living-operational-system roadmap (§4): the runtime **`$`-parameter** namespace. A query's
 * text can reference `$`-tokens that are resolved *at run time* from a {@link ParameterContext} (the
 * session/clock/lens today; scheduler / previous-job output / AI decision later — same seam). Pure and
 * framework-free so it unit-tests in vitest and runs identically offline (before `runSql`) or, later, on
 * the backend.
 *
 * Three parameter namespaces coexist and MUST NOT be conflated:
 *  - `$name`      — this: a runtime binding resolved from context (built-ins) or a declared default.
 *  - `:name`      — a rule/data-table template placeholder (`:fieldValue`; see `rule/rule-types.ts`).
 *  - `${ENV:KEY}` — a config-time secret reference (never a runtime value; stays server-side).
 * The token grammar below (`$` + identifier, no `{`) deliberately never matches `${ENV:…}` or `:name`.
 */

/** A user-declared parameter: a name, a value type, and an optional default + display label. */
export interface ParameterDef {
    name: string;
    type: 'date' | 'string' | 'number';
    default?: string;
    label?: string;
}

/** The values available when resolving built-in `$`-tokens. Merged by the caller's priority order. */
export interface ParameterContext {
    /** The clock — resolves `$today` / `$now` / `$day(-N)`. Defaults to `new Date()`. */
    now?: Date;
    /** Resolves `$current_user`. */
    user?: string;
    /** Resolves `$role`. */
    role?: string;
}

/** The built-in token names (no user declaration needed). `$day` takes an integer offset: `$day(-7)`. */
export const BUILTIN_PARAMS = ['today', 'now', 'day', 'current_user', 'role'] as const;

/** `$` + identifier, with an optional integer-offset arg (`$day(-7)`). Never matches `${…}` (no letter
 *  after `$`) nor `:name`, so the other two namespaces are untouched. */
const TOKEN = /\$([A-Za-z_][A-Za-z0-9_]*)(?:\(\s*(-?\d+)\s*\))?/g;

/** The distinct `$`-tokens present in `text`, in first-seen order (drives the editor's parameter chips). */
export function findParameters(text: string): string[] {
    const seen = new Set<string>();
    for (const m of text.matchAll(TOKEN)) seen.add(m[0]);
    return [...seen];
}

/** Format a Date as a `YYYY-MM-DD` UTC calendar date. */
function isoDate(d: Date): string {
    return d.toISOString().slice(0, 10);
}

/** A SQL string literal (single-quoted, embedded quotes doubled). */
function sqlString(v: string): string {
    return `'${v.replace(/'/g, "''")}'`;
}

/**
 * Substitute every `$`-token in `text`: built-ins from `ctx`, then any user-declared `$name` from its
 * `default`. Unknown tokens are left verbatim (so a typo is visible, not silently blanked). Values are
 * emitted as SQL literals — dates/strings quoted, numbers raw — so the result is directly runnable.
 */
export function resolveParameters(text: string, defs: ParameterDef[], ctx: ParameterContext): string {
    const now = ctx.now ?? new Date();
    const byName = new Map(defs.map((d) => [d.name, d]));
    return text.replace(TOKEN, (whole, name: string, arg: string | undefined) => {
        switch (name) {
            case 'today':
                return sqlString(isoDate(now));
            case 'now':
                return sqlString(now.toISOString());
            case 'day': {
                const d = new Date(now);
                d.setUTCDate(d.getUTCDate() + (arg ? parseInt(arg, 10) : 0));
                return sqlString(isoDate(d));
            }
            case 'current_user':
                return ctx.user != null ? sqlString(ctx.user) : whole;
            case 'role':
                return ctx.role != null ? sqlString(ctx.role) : whole;
            default: {
                const def = byName.get(name);
                if (!def || def.default == null) return whole; // undeclared / no default → leave visible
                return def.type === 'number' ? def.default : sqlString(def.default);
            }
        }
    });
}
