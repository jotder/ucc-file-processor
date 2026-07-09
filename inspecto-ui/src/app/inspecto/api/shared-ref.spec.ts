import { describe, expect, it } from 'vitest';
import { isSharedRef, parseSharedRef } from './shared-ref';

describe('shared-ref', () => {
    it('recognises shared/<owner>/<item> refs', () => {
        expect(isSharedRef('shared/analytics-hub/fx_rates_daily')).toBe(true);
        expect(isSharedRef('cdr_sample')).toBe(false);
        expect(isSharedRef('shared/')).toBe(false);
        expect(isSharedRef('shared/owner')).toBe(false);
        expect(isSharedRef(null)).toBe(false);
        expect(isSharedRef(undefined)).toBe(false);
    });

    it('parses owner + item, tolerating slashes in the item', () => {
        expect(parseSharedRef('shared/analytics-hub/fx_rates_daily')).toEqual({
            owner: 'analytics-hub',
            item: 'fx_rates_daily',
        });
        expect(parseSharedRef('shared/hub/a/b')).toEqual({ owner: 'hub', item: 'a/b' });
        expect(parseSharedRef('local_table')).toBeNull();
    });
});
