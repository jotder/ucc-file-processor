import { GammaNavigationItem } from '@gamma/components/navigation';
import { describe, expect, it } from 'vitest';

import { AccessGrant } from '../api/access.service';
import {
    ACCESS_ACTION_NODES,
    deriveAccessCatalog,
    deriveDefaultAccessCatalog,
    filterNavByAccess,
    indexCatalog,
    resolveGrant,
} from './access-catalog';

const NAV: GammaNavigationItem[] = [
    { id: 'custom-menus-divider', type: 'divider' },
    {
        id: 'workbench-group', title: 'Workbench', type: 'collapsable', icon: 'heroicons_outline:wrench',
        children: [
            { id: 'pipelines', title: 'Pipelines', type: 'basic', link: '/pipelines' },
            { id: 'runs', title: 'Runs', type: 'basic', link: '/runs' },
        ],
    },
    { id: 'settings', title: 'Settings', type: 'basic', link: '/settings' },
];

describe('access-catalog derivation', () => {
    it('maps nav into menu/pane nodes, skips dividers, grafts the action nodes', () => {
        const nodes = deriveAccessCatalog(NAV);
        expect(nodes.map((n) => n.id)).toEqual(['workbench-group', 'settings']);
        const workbench = nodes[0];
        expect(workbench.kind).toBe('menu');
        expect(workbench.icon).toBe('heroicons_outline:wrench');
        // children = panes + the grafted workbench.author action
        expect(workbench.children!.map((c) => c.id)).toEqual(['pipelines', 'runs', 'workbench.author']);
        expect(workbench.children![0].kind).toBe('pane');
        expect(workbench.children![0].link).toBe('/pipelines');
        // runs carries its operate action; settings (a pane) carries access.configure
        const runs = workbench.children![1];
        expect(runs.children!.map((c) => c.id)).toEqual(['runs.operate']);
        expect(runs.children![0].capability).toBe('canOperateRuns');
        expect(nodes[1].children!.map((c) => c.id)).toEqual(['access.configure']);
    });

    it('the default catalog covers every declared action node', () => {
        const idx = indexCatalog(deriveDefaultAccessCatalog());
        for (const actions of Object.values(ACCESS_ACTION_NODES)) {
            for (const a of actions) expect(idx.byId.has(a.id), a.id).toBe(true);
        }
    });
});

describe('resolveGrant', () => {
    const idx = indexCatalog(deriveAccessCatalog(NAV));

    it('defaults to allow with no explicit ancestor', () => {
        expect(resolveGrant('pipelines', {}, idx))
            .toEqual({ effective: 'allow', explicit: null, sourceId: null, sourceLabel: null });
    });

    it('an explicit grant on the node wins and names itself as the source', () => {
        const g: Record<string, AccessGrant> = { pipelines: 'deny' };
        const s = resolveGrant('pipelines', g, idx);
        expect(s.effective).toBe('deny');
        expect(s.explicit).toBe('deny');
        expect(s.sourceId).toBe('pipelines');
    });

    it('inherits from the nearest explicit ancestor', () => {
        const g: Record<string, AccessGrant> = { 'workbench-group': 'deny' };
        const s = resolveGrant('runs.operate', g, idx);
        expect(s.effective).toBe('deny');
        expect(s.explicit).toBeNull();
        expect(s.sourceLabel).toBe('Workbench');
        // a closer explicit allow overrides the group deny
        const s2 = resolveGrant('runs.operate', { 'workbench-group': 'deny', runs: 'allow' }, idx);
        expect(s2.effective).toBe('allow');
        expect(s2.sourceId).toBe('runs');
    });
});

describe('filterNavByAccess', () => {
    const idx = indexCatalog(deriveAccessCatalog(NAV));

    it('is the identity with no grants (empty profile = today\'s sidebar)', () => {
        expect(filterNavByAccess(NAV, {}, idx)).toBe(NAV);
    });

    it('drops denied panes and whole denied groups; unknown ids (dividers, custom menus) stay', () => {
        const denyPane = filterNavByAccess(NAV, { pipelines: 'deny' }, idx);
        expect(denyPane.map((i) => i.id)).toEqual(['custom-menus-divider', 'workbench-group', 'settings']);
        expect(denyPane[1].children!.map((c) => c.id)).toEqual(['runs']);

        const denyGroup = filterNavByAccess(NAV, { 'workbench-group': 'deny' }, idx);
        expect(denyGroup.map((i) => i.id)).toEqual(['custom-menus-divider', 'settings']);
    });
});
