import { describe, expect, it } from 'vitest';
import { favoritesNavGroup, MENU_FAVORITES_NAV_ID, menuTreeToNav } from './menu-nav';
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

describe('favoritesNavGroup', () => {
    const tree: MenuNode[] = [
        {
            id: 'rev',
            title: 'Revenue',
            children: [
                { id: 'd1', title: 'Dash one', binding: { kind: 'dashboard', componentId: 'c1' } },
                { id: 'd2', title: 'Dash two', binding: { kind: 'widget', componentId: 'c2' } },
            ],
        },
        { id: 'grp', title: 'A group' },
    ];

    it('resolves favorite leaf ids to basic /w/ links under a Favorites group, distinct fav- ids, in favorite order', () => {
        const group = favoritesNavGroup(tree, ['d2', 'd1']);
        expect(group).toMatchObject({ id: MENU_FAVORITES_NAV_ID, title: 'Favorites', type: 'collapsable' });
        expect(group!.children!.map((c) => c.id)).toEqual(['fav-d2', 'fav-d1']);
        expect(group!.children![0]).toMatchObject({ title: 'Dash two', type: 'basic', link: '/w/d2' });
    });

    it('drops ids that no longer resolve to a leaf (deleted item, or a group)', () => {
        const group = favoritesNavGroup(tree, ['d1', 'gone', 'grp']);
        expect(group!.children!.map((c) => c.id)).toEqual(['fav-d1']);
    });

    it('returns null when nothing resolves', () => {
        expect(favoritesNavGroup(tree, [])).toBeNull();
        expect(favoritesNavGroup(tree, ['grp', 'nope'])).toBeNull();
    });
});
