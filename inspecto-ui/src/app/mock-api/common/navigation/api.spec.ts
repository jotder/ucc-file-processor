import { GammaNavigationItem } from '@gamma/components/navigation';
import { MENU_STORAGE_KEY, MenuTree } from 'app/inspecto/menu';
import { NavigationMockApi } from './api';

/**
 * The sidebar's @for tracks nav items by `item.id`; duplicate ids trip Angular's NG0955 and render a
 * group twice. These lock the mock's guarantee that every returned variant has unique top-level ids.
 */
describe('NavigationMockApi', () => {
    let replyCb: (args: unknown) => [number, Record<string, GammaNavigationItem[]>];
    const mockService = {
        onGet: () => ({ reply: (cb: typeof replyCb) => (replyCb = cb) }),
    };

    const seedTree = (nodes: MenuTree['nodes']): void => {
        localStorage.setItem('inspecto.currentSpace', 'default');
        localStorage.setItem(
            MENU_STORAGE_KEY,
            JSON.stringify({ default: { space: 'default', version: 1, nodes } }),
        );
    };

    const fetchDefault = (): GammaNavigationItem[] => {
        new NavigationMockApi(mockService as never);
        return replyCb({})[1].default;
    };

    const ids = (items: GammaNavigationItem[]): string[] => items.map((i) => i.id);

    beforeEach(() => localStorage.clear());

    it('returns unique top-level ids for the default variant (no menu tree)', () => {
        const topIds = ids(fetchDefault());
        expect(new Set(topIds).size).toBe(topIds.length);
    });

    it('dedupes when a corrupt Menu tree yields two nodes with the same id', () => {
        // Two top-level nodes sharing an id both map to `menu-<id>` — a duplicate top-level key.
        seedTree([
            { id: 'dup', title: 'One', children: [] },
            { id: 'dup', title: 'Two', children: [] },
        ]);
        const nav = fetchDefault();
        const topIds = ids(nav);
        expect(new Set(topIds).size).toBe(topIds.length);
        expect(topIds.filter((id) => id === 'menu-dup')).toEqual(['menu-dup']);
    });
});
