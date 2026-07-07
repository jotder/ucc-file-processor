import { GammaNavigationItem } from '@gamma/components/navigation';

/**
 * Flatten the navigation tree to the leaf items (those with a `link`) that match `query`, for the
 * sidebar's client-side menu search. A leaf matches when its own title — or any ancestor group's
 * title — contains the query (case-insensitive), so typing a group name ("Platform") surfaces all of
 * its pages while typing a page name ("Pipelines") surfaces just that page. Each result carries a
 * `subtitle` breadcrumb of its ancestor groups for context. An empty query returns `[]` — callers
 * render the full (unflattened) tree in that case.
 */
export function flattenNavForSearch(
    items: readonly GammaNavigationItem[],
    query: string,
): GammaNavigationItem[] {
    const q = query.trim().toLowerCase();
    if (!q) {
        return [];
    }

    const results: GammaNavigationItem[] = [];

    const walk = (
        nodes: readonly GammaNavigationItem[],
        ancestorMatch: boolean,
        trail: readonly string[],
    ): void => {
        for (const node of nodes) {
            const titleMatch = (node.title ?? '').toLowerCase().includes(q);
            const matched = ancestorMatch || titleMatch;

            if (node.children?.length) {
                walk(node.children, matched, node.title ? [...trail, node.title] : trail);
            } else if (node.link && matched) {
                results.push({
                    id: node.id,
                    title: node.title,
                    type: 'basic',
                    icon: node.icon,
                    link: node.link,
                    subtitle: trail.length ? trail.join(' › ') : undefined,
                });
            }
        }
    };

    walk(items, false, []);
    return results;
}
