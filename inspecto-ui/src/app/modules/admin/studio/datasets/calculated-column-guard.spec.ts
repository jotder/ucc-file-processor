import { describe, expect, it } from 'vitest';
import { checkCalculatedExpr, checkCalculatedName } from './calculated-column-guard';

describe('checkCalculatedExpr', () => {
    it('allows plain arithmetic over column references', () => {
        expect(checkCalculatedExpr('amt * 1.1')).toBeNull();
        expect(checkCalculatedExpr('round(amt * 1.1, 2)')).toBeNull();
    });

    it('allows whitelisted scalar functions and a CASE expression', () => {
        expect(checkCalculatedExpr('upper(msisdn)')).toBeNull();
        expect(checkCalculatedExpr('coalesce(amt, 0)')).toBeNull();
        expect(checkCalculatedExpr("case when amt > 100 then 'big' else 'small' end")).toBeNull();
    });

    it('allows cast to a whitelisted type', () => {
        expect(checkCalculatedExpr('cast(amt AS double)')).toBeNull();
    });

    it('rejects an empty expression', () => {
        expect(checkCalculatedExpr('')).toMatch(/empty/);
        expect(checkCalculatedExpr('   ')).toMatch(/empty/);
    });

    it('rejects a statement keyword even as a bare identifier (scalar-subquery smuggling)', () => {
        expect(checkCalculatedExpr("(select secret from t)")).toMatch(/not allowed/i);
    });

    it('rejects a non-whitelisted function call (file/UDF access)', () => {
        expect(checkCalculatedExpr("read_parquet('x')")).toMatch(/not allowed/i);
        expect(checkCalculatedExpr('some_udf(amt)')).toMatch(/not allowed/i);
    });

    it('rejects a non-whitelisted cast target type', () => {
        expect(checkCalculatedExpr('cast(amt AS blob)')).toMatch(/not allowed/i);
    });

    it('rejects comment sequences even inside otherwise-valid expressions', () => {
        expect(checkCalculatedExpr('amt -- drop everything')).toMatch(/comment/i);
        expect(checkCalculatedExpr('amt /* x */ + 1')).toMatch(/comment/i);
    });

    it('does not flag -- inside a string literal as a comment', () => {
        expect(checkCalculatedExpr("concat(msisdn, '--suffix')")).toBeNull();
    });

    it('rejects an illegal character', () => {
        expect(checkCalculatedExpr('amt; drop table x')).toMatch(/illegal character|not allowed/i);
        expect(checkCalculatedExpr('amt & 1')).toMatch(/illegal character/i);
    });

    it('rejects unbalanced parentheses', () => {
        expect(checkCalculatedExpr('round(amt * 1.1')).toMatch(/unbalanced/i);
        expect(checkCalculatedExpr('amt)')).toMatch(/unbalanced/i);
    });

    it('rejects a dangling AS with no type', () => {
        expect(checkCalculatedExpr('cast(amt AS')).not.toBeNull();
    });

    it('rejects an expression over the length cap', () => {
        expect(checkCalculatedExpr('a'.repeat(501))).toMatch(/exceeds/);
    });

    it('allows a window function with an OVER clause', () => {
        expect(checkCalculatedExpr('sum(amt) OVER (PARTITION BY region ORDER BY day)')).toBeNull();
        expect(checkCalculatedExpr('row_number() OVER (ORDER BY amt DESC)')).toBeNull();
        expect(checkCalculatedExpr('lag(amt) OVER (ORDER BY day) - amt')).toBeNull();
        expect(checkCalculatedExpr('count(*) OVER ()')).toBeNull();
    });

    it('rejects a bare aggregate or a malformed window clause', () => {
        expect(checkCalculatedExpr('sum(amt)')).toMatch(/OVER/i);
        expect(checkCalculatedExpr('avg(amt)')).toMatch(/OVER/i);
        expect(checkCalculatedExpr('row_number()')).toMatch(/OVER/i);
        expect(checkCalculatedExpr('sum(amt) OVER region')).toMatch(/OVER must be followed/i);
        expect(checkCalculatedExpr('sum(amt) OVER (ORDER BY (select 1))')).toMatch(/not allowed/i);
    });
});

describe('checkCalculatedName', () => {
    it('accepts a plain identifier', () => {
        expect(checkCalculatedName('total_with_tax')).toBeNull();
        expect(checkCalculatedName('_private')).toBeNull();
    });

    it('rejects blank, dashed, or dotted names (not a plain identifier)', () => {
        expect(checkCalculatedName('')).toMatch(/required/);
        expect(checkCalculatedName('total-with-tax')).toMatch(/letters, digits, underscore/i);
        expect(checkCalculatedName('total.tax')).toMatch(/letters, digits, underscore/i);
        expect(checkCalculatedName('1total')).toMatch(/letters, digits, underscore/i);
    });
});
