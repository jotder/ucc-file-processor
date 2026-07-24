/**
 * Client-side mirror of the backend `com.gamma.query.ExpressionGuard` (DAT-5,
 * `docs/superpower/calculated-columns-design.md`) — the same three rules (closed token alphabet,
 * keyword deny-set, function-call whitelist), kept in lockstep by hand since there is no shared
 * runtime between Java and TS. **Not authoritative**: this exists purely so an author gets instant
 * inline feedback instead of a save-then-query round trip; the server re-validates at query time
 * (`DatasetRelation.withCalculated`) and is the only enforcement that matters for safety.
 */

const MAX_LENGTH = 500;

/** Mirrors `ExpressionGuard.TOKEN` — sticky so each match must start exactly at `lastIndex`. */
const TOKEN = /\s+|[A-Za-z_][A-Za-z0-9_]*|[0-9]+(?:\.[0-9]+)?|'(?:[^']|'')*'|\|\||!=|<>|>=|<=|[+\-*/%=<>(),]/y;
const IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** Statement/structure keywords that must never appear, even as bare identifiers. */
const DENIED = new Set([
    'select', 'from', 'where', 'group', 'having', 'union', 'join', 'with', 'values',
    'insert', 'update', 'delete', 'drop', 'create', 'alter', 'attach', 'copy',
    'pragma', 'install', 'load', 'call', 'set', 'table', 'exec', 'execute',
]);

/** Flow keywords of a CASE expression + predicate glue — allowed as bare words (never called). */
const FLOW_KEYWORDS = new Set([
    'case', 'when', 'then', 'else', 'end', 'and', 'or', 'not', 'is', 'null',
    'in', 'like', 'between', 'as',
]);

/** The scalar functions a calculated column may call. */
const FUNCTIONS = new Set([
    'abs', 'round', 'floor', 'ceil', 'coalesce', 'nullif', 'greatest', 'least',
    'upper', 'lower', 'trim', 'ltrim', 'rtrim', 'length', 'substr', 'substring',
    'concat', 'replace', 'cast', 'try_cast',
]);

/** Functions callable ONLY as a window call — must be immediately followed by `OVER (…)`. The windowed
 *  aggregates are deliberately absent from FUNCTIONS, so used bare they stay rejected. */
const WINDOW_FUNCTIONS = new Set([
    'sum', 'avg', 'count', 'min', 'max',
    'row_number', 'rank', 'dense_rank', 'percent_rank', 'cume_dist',
    'ntile', 'lag', 'lead', 'first_value', 'last_value', 'nth_value',
]);

/** Bare words allowed only inside an `OVER (…)` clause (never call targets). */
const WINDOW_KEYWORDS = new Set(['partition', 'by', 'order', 'asc', 'desc', 'nulls', 'first', 'last']);

/** The type names a `cast(x AS type)` may target. */
const TYPES = new Set([
    'integer', 'int', 'bigint', 'smallint', 'double', 'float', 'real', 'decimal',
    'varchar', 'text', 'boolean', 'date', 'timestamp',
]);

/** Validate a calculated column's expression fragment; returns an error message, or `null` if clean. */
export function checkCalculatedExpr(expr: string): string | null {
    if (!expr || !expr.trim()) return 'Expression is empty.';
    const e = expr.trim();
    if (e.length > MAX_LENGTH) return `Expression exceeds ${MAX_LENGTH} characters.`;

    const noStrings = e.replace(/'(?:[^']|'')*'/g, "''");
    if (noStrings.includes('--') || noStrings.includes('/*') || noStrings.includes('*/'))
        return 'Comment sequences (--, /*, */) are not allowed.';

    let pos = 0;
    let parens = 0;
    let prevWord: string | null = null;
    let afterAs = false;
    let expectOver = false;      // a window call just closed — the next token MUST be 'over'
    let expectOverParen = false; // 'over' just seen — the next token MUST be '('
    let windowOpenDepth = -1;    // parens depth inside a window fn's arg list (to detect its close)
    let windowSpecDepth = -1;    // parens depth inside the OVER (…) clause (window keywords legal here)
    while (pos < e.length) {
        TOKEN.lastIndex = pos;
        const m = TOKEN.exec(e);
        if (!m || m.index !== pos) return `Illegal character at: '${e.slice(pos, pos + 12)}'`;
        const tok = m[0];
        pos += tok.length;
        if (!tok.trim()) continue;

        // A window call must be followed by OVER (…) — gated before anything else so a bare
        // aggregate/window call (no OVER) can never slip through as a normal expression.
        if (expectOver) {
            if (!(IDENT.test(tok) && tok.toLowerCase() === 'over'))
                return 'A window function must be followed by an OVER (…) clause.';
            expectOver = false;
            expectOverParen = true;
            prevWord = null;
            continue;
        }
        if (expectOverParen) {
            if (tok !== '(') return "OVER must be followed by '('.";
            expectOverParen = false;
            parens++;
            windowSpecDepth = parens;
            prevWord = null;
            continue;
        }

        if (tok === '(') {
            const windowCall = prevWord !== null && WINDOW_FUNCTIONS.has(prevWord);
            if (prevWord !== null && !FUNCTIONS.has(prevWord) && !windowCall)
                return `Function '${prevWord}' is not allowed (allowed: ${[...FUNCTIONS].sort().join(', ')}).`;
            parens++;
            if (windowCall) windowOpenDepth = parens;
            prevWord = null;
            continue;
        }
        if (tok === ')') {
            const closingWindowCall = windowOpenDepth !== -1 && parens === windowOpenDepth;
            const closingWindowSpec = windowSpecDepth !== -1 && parens === windowSpecDepth;
            parens--;
            if (parens < 0) return "Unbalanced ')' in expression.";
            prevWord = null;
            if (closingWindowCall) { expectOver = true; windowOpenDepth = -1; }
            if (closingWindowSpec) windowSpecDepth = -1;
            continue;
        }

        if (IDENT.test(tok)) {
            const w = tok.toLowerCase();
            if (DENIED.has(w)) return `'${tok}' is not allowed in a calculated column.`;
            if (afterAs) {
                if (!TYPES.has(w))
                    return `Cast target type '${tok}' is not allowed (allowed: ${[...TYPES].sort().join(', ')}).`;
                afterAs = false;
                prevWord = null;
                continue;
            }
            if (w === 'as') {
                afterAs = true;
                prevWord = null;
                continue;
            }
            // window keywords are only meaningful inside the OVER (…) clause; elsewhere they lex as
            // ordinary column refs (a DuckDB bind error at worst — never a structural break).
            if (windowSpecDepth !== -1 && parens >= windowSpecDepth && WINDOW_KEYWORDS.has(w)) {
                prevWord = null;
                continue;
            }
            // a flow keyword is never a call target; anything else may be a column ref OR a function
            // name — resolved when the next token is '('
            prevWord = FLOW_KEYWORDS.has(w) ? null : w;
            continue;
        }
        afterAs = false;
        prevWord = null; // literals/operators break any identifier-then-paren pairing
    }
    if (parens !== 0) return "Unbalanced '(' in expression.";
    if (afterAs) return 'Dangling AS in expression.';
    if (expectOver) return 'A window function must be followed by an OVER (…) clause.';
    if (expectOverParen) return "OVER must be followed by '('.";
    return null;
}

/** SAFE_IDENT check for a calculated column's name (mirrors `DatasetRelation.SAFE_IDENT`). */
export function checkCalculatedName(name: string): string | null {
    const n = (name ?? '').trim();
    if (!n) return 'Name is required.';
    if (!IDENT.test(n)) return 'Letters, digits, underscore only; must start with a letter or underscore.';
    return null;
}
