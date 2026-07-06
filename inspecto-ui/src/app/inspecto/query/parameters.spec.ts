import { describe, expect, it } from 'vitest';
import { findParameters, resolveParameters } from './parameters';

/** R3 §4: the runtime `$`-parameter namespace. These pins are the contract for the query run path. */
describe('findParameters', () => {
    it('lists the distinct $tokens present, including args, in first-seen order', () => {
        expect(findParameters("a >= $day(-7) AND b = $region AND c < $day(-7) AND u = $current_user")).toEqual([
            '$day(-7)', '$region', '$current_user',
        ]);
    });

    it('never matches the other two namespaces (:name templates, ${ENV:} secrets)', () => {
        expect(findParameters('WHERE x = :fieldValue AND y = ${ENV:API_KEY}')).toEqual([]);
    });
});

describe('resolveParameters', () => {
    const now = new Date('2026-07-06T13:21:00.000Z');

    it('resolves the clock built-ins as SQL date/timestamp literals', () => {
        expect(resolveParameters('t >= $today', [], { now })).toBe("t >= '2026-07-06'");
        expect(resolveParameters('t >= $day(-7)', [], { now })).toBe("t >= '2026-06-29'");
        expect(resolveParameters('t >= $day(0)', [], { now })).toBe("t >= '2026-07-06'");
        expect(resolveParameters('t >= $now', [], { now })).toBe("t >= '2026-07-06T13:21:00.000Z'");
    });

    it('resolves session built-ins, and leaves them verbatim when the context lacks them', () => {
        expect(resolveParameters('u = $current_user AND r = $role', [], { user: 'ops', role: 'ops' })).toBe(
            "u = 'ops' AND r = 'ops'",
        );
        expect(resolveParameters('u = $current_user', [], {})).toBe('u = $current_user');
    });

    it('resolves user-declared params from their default; numbers raw, strings quoted', () => {
        const defs = [
            { name: 'region', type: 'string' as const, default: 'APAC' },
            { name: 'min_cost', type: 'number' as const, default: '5' },
        ];
        expect(resolveParameters('region = $region AND cost > $min_cost', defs, {})).toBe("region = 'APAC' AND cost > 5");
    });

    it('leaves an undeclared / default-less token visible rather than blanking it', () => {
        expect(resolveParameters('x = $mystery', [], {})).toBe('x = $mystery');
    });

    it('does not touch :name templates or ${ENV:} secrets', () => {
        expect(resolveParameters('a = :fieldValue AND k = ${ENV:API_KEY}', [], { now })).toBe(
            'a = :fieldValue AND k = ${ENV:API_KEY}',
        );
    });

    it('escapes single quotes in resolved string values', () => {
        expect(resolveParameters('u = $current_user', [], { user: "O'Neil" })).toBe("u = 'O''Neil'");
    });
});
