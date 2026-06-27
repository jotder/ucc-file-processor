import { describe, expect, it } from 'vitest';
import { runSql, toAlaSqlDialect } from './sql-run';

describe('toAlaSqlDialect', () => {
    it('rewrites double-quoted identifiers to backticks', () => {
        expect(toAlaSqlDialect('SELECT "a" FROM "t" WHERE "n" >= 3')).toBe('SELECT `a` FROM `t` WHERE `n` >= 3');
    });
    it('leaves single-quoted string literals untouched', () => {
        expect(toAlaSqlDialect(`SELECT * FROM "t" WHERE "type" = 'CA"LL'`)).toBe("SELECT * FROM `t` WHERE `type` = 'CA\"LL'");
    });
});

describe('runSql', () => {
    const rows = [
        { duration_s: 300, type: 'CALL' },
        { duration_s: 120, type: 'SMS' },
        { duration_s: 600, type: 'CALL' },
    ];

    it('runs a generated (double-quoted) query over the rows', async () => {
        const res = await runSql('SELECT *\nFROM "events"\nWHERE "duration_s" >= 300', 'events', rows);
        expect(res.ok).toBe(true);
        expect(res.rows.length).toBe(2);
    });

    it('supports aggregates and functions', async () => {
        const res = await runSql('SELECT type, COUNT(*) AS n FROM "events" GROUP BY type', 'events', rows);
        expect(res.ok).toBe(true);
        expect(res.rows.length).toBe(2);
    });

    it('reports an error for unparseable SQL', async () => {
        const res = await runSql('SELECT FROM WHERE', 'events', rows);
        expect(res.ok).toBe(false);
        expect(res.error).toBeTruthy();
        expect(res.rows).toEqual([]);
    });

    it('rejects empty input', async () => {
        const res = await runSql('   ', 'events', rows);
        expect(res.ok).toBe(false);
    });
});
