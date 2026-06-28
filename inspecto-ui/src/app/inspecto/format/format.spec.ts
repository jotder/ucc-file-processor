import { describe, expect, it } from 'vitest';
import { fmtBytes, fmtDateTime, fmtInt, fmtPercent } from './format';
import { FmtPercentPipe } from './pipes';

describe('format', () => {
    it('fmtInt rounds and adds thousands separators', () => {
        expect(fmtInt(1234.6)).toBe((1235).toLocaleString());
        expect(fmtInt(0)).toBe('0');
    });

    it('fmtBytes scales B → KB/MB', () => {
        expect(fmtBytes(512)).toBe('512 B');
        expect(fmtBytes(1024)).toBe('1.0 KB');
        expect(fmtBytes(1536)).toBe('1.5 KB');
        expect(fmtBytes(1048576)).toBe('1.0 MB');
    });

    it('fmtPercent renders a ratio as a 1-decimal percentage', () => {
        expect(fmtPercent(0.0123)).toBe('1.2%');
        expect(fmtPercent(0)).toBe('0.0%');
    });

    it('fmtDateTime handles empty / invalid / epoch-falsy', () => {
        expect(fmtDateTime(null)).toBe('');
        expect(fmtDateTime('')).toBe('');
        expect(fmtDateTime('not-a-date')).toBe('not-a-date');
        expect(fmtDateTime(0)).toBe(''); // 0 is falsy → '' (preserves the original grid guard)
    });
});

describe('FmtPercentPipe', () => {
    const pipe = new FmtPercentPipe();

    it('formats a ratio and guards null/undefined', () => {
        expect(pipe.transform(0.0123)).toBe('1.2%');
        expect(pipe.transform(null)).toBe('—');
        expect(pipe.transform(undefined)).toBe('—');
    });
});
