import { describe, expect, it } from 'vitest';
import { toCsv } from './csv';

describe('toCsv', () => {
    it('writes header + rows', () => {
        expect(toCsv([{ a: 1, b: 'x' }], ['a', 'b'])).toBe('a,b\n1,x');
    });

    it('quotes cells with commas, quotes and newlines (RFC-4180)', () => {
        expect(toCsv([{ a: 'x,y', b: 'he said "hi"', c: 'l1\nl2' }], ['a', 'b', 'c'])).toBe(
            'a,b,c\n"x,y","he said ""hi""","l1\nl2"',
        );
    });

    it('renders null/undefined cells as blank', () => {
        expect(toCsv([{ a: null, b: undefined }], ['a', 'b'])).toBe('a,b\n,');
    });

    it('emits the header alone when there are no rows', () => {
        expect(toCsv([], ['a', 'b'])).toBe('a,b');
    });
});
