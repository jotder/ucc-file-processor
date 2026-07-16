import { describe, expect, it } from 'vitest';
import { dbColumnType } from './query-columns';

describe('dbColumnType', () => {
    it('maps normalized /db types and raw SQL spellings to the query ColumnType', () => {
        expect(dbColumnType('number')).toBe('number'); // /db/table reports normalized names
        expect(dbColumnType('BIGINT')).toBe('number');
        expect(dbColumnType('DECIMAL(18,3)')).toBe('number');
        expect(dbColumnType('date')).toBe('date');
        expect(dbColumnType('TIMESTAMP WITH TIME ZONE')).toBe('date');
        expect(dbColumnType('boolean')).toBe('boolean');
        expect(dbColumnType('VARCHAR')).toBe('string');
        expect(dbColumnType(undefined)).toBe('string');
    });
});
