import { describe, expect, it } from 'vitest';
import { menuTreeToNav } from './menu-nav';
import { MenuNode } from './menu-types';

describe('menuTreeToNav', () => {
    it('maps groups to collapsable, leaves to basic /w/ links, and namespaces ids', () => {
        const nodes: MenuNode[] = [
            {
                id: 'rev',
                title: 'Revenue',
                icon: 'heroicons_outline:banknotes',
                children: [
                    {
                        id: 'top',
                        title: 'TopX',
                        children: [
                            { id: 'usage', title: 'Top usages', binding: { kind: 'dashboard', componentId: 'd1' } },
                        ],
                    },
                ],
            },
        ];
        const nav = menuTreeToNav(nodes);
        expect(nav[0]).toMatchObject({
            id: 'menu-rev',
            title: 'Revenue',
            type: 'collapsable',
            icon: 'heroicons_outline:banknotes',
        });
        const top = nav[0].children![0];
        expect(top).toMatchObject({ id: 'menu-top', type: 'collapsable' });
        const leaf = top.children![0];
        expect(leaf).toMatchObject({ id: 'menu-usage', title: 'Top usages', type: 'basic', link: '/w/usage' });
        expect(leaf.children).toBeUndefined();
    });

    it('renders an empty group as a collapsable with no children', () => {
        const nav = menuTreeToNav([{ id: 'fms', title: 'FMS' }]);
        expect(nav[0]).toMatchObject({ id: 'menu-fms', type: 'collapsable' });
        expect(nav[0].children).toEqual([]);
    });
});
