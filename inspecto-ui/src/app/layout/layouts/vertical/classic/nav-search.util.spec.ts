import { GammaNavigationItem } from '@gamma/components/navigation';
import { describe, expect, it } from 'vitest';
import { flattenNavForSearch } from './nav-search.util';

const NAV: GammaNavigationItem[] = [
    { id: 'divider', type: 'divider' },
    {
        id: 'operations-group',
        title: 'Operations',
        type: 'collapsable',
        children: [
            { id: 'events', title: 'Events', type: 'basic', link: '/events' },
            { id: 'audit', title: 'Audit log', type: 'basic', link: '/audit' },
        ],
    },
    {
        id: 'platform-group',
        title: 'Platform',
        type: 'collapsable',
        children: [
            {
                id: 'workbench-group',
                title: 'Workbench',
                type: 'collapsable',
                children: [{ id: 'pipelines', title: 'Pipelines', type: 'basic', link: '/pipelines' }],
            },
        ],
    },
    { id: 'settings', title: 'Settings', type: 'basic', link: '/settings' },
];

describe('flattenNavForSearch', () => {
    it('returns [] for an empty / whitespace query', () => {
        expect(flattenNavForSearch(NAV, '')).toEqual([]);
        expect(flattenNavForSearch(NAV, '   ')).toEqual([]);
    });

    it('matches a leaf by its own title (case-insensitive)', () => {
        const res = flattenNavForSearch(NAV, 'pipe');
        expect(res.map((r) => r.link)).toEqual(['/pipelines']);
        expect(res[0].type).toBe('basic');
    });

    it('matches a top-level basic item', () => {
        expect(flattenNavForSearch(NAV, 'settings').map((r) => r.link)).toEqual(['/settings']);
    });

    it('includes every descendant leaf when an ancestor group title matches', () => {
        expect(flattenNavForSearch(NAV, 'operations').map((r) => r.link)).toEqual(['/events', '/audit']);
    });

    it('carries the ancestor breadcrumb as the subtitle', () => {
        const res = flattenNavForSearch(NAV, 'pipelines');
        expect(res[0].subtitle).toBe('Platform › Workbench');
    });

    it('excludes dividers, group headers, and non-matches', () => {
        const res = flattenNavForSearch(NAV, 'audit');
        expect(res).toHaveLength(1);
        expect(res.every((r) => !!r.link)).toBe(true);
    });
});
