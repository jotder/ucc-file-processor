import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';

import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { TreeTableComponent } from './tree-table.component';
import { allParentIds, flattenTree, seedExpanded, TreeNode, varianceCell } from './tree-types';

const FOREST: TreeNode[] = [
    {
        id: 'north',
        label: 'Region North',
        values: { e1: 100, e2: 120, delta: 20 },
        children: [
            { id: 'north/a', label: 'Product A', values: { e1: 40, e2: 45, delta: 5 } },
            { id: 'north/b', label: 'Product B', values: { e1: 60, e2: 75, delta: 15 } },
        ],
    },
    { id: 'south', label: 'Region South', values: { e1: 200, e2: 190, delta: -10 }, children: [] },
];

describe('tree-types', () => {
    it('flattenTree emits only the roots when nothing is expanded', () => {
        const rows = flattenTree(FOREST, new Set());
        expect(rows.map((r) => r.__id)).toEqual(['north', 'south']);
        expect(rows[0].__hasChildren).toBe(true);
        expect(rows[0].__expanded).toBe(false);
        expect(rows[1].__hasChildren).toBe(false); // empty children array ⇒ leaf
    });

    it('flattenTree reveals children of expanded nodes with the right depth + spread values', () => {
        const rows = flattenTree(FOREST, new Set(['north']));
        expect(rows.map((r) => r.__id)).toEqual(['north', 'north/a', 'north/b', 'south']);
        expect(rows[1].__depth).toBe(1);
        expect(rows[1]['e1']).toBe(40);
        expect(rows[1]['delta']).toBe(5);
    });

    it('seedExpanded expands to the requested depth; allParentIds expands every parent', () => {
        expect([...seedExpanded(FOREST, 1)]).toEqual(['north']); // depth 0 parents only
        expect([...seedExpanded(FOREST, 0)]).toEqual([]); // nothing
        expect([...allParentIds(FOREST)]).toEqual(['north']); // 'south' has no children
    });

    it('varianceCell renders signed value + direction glyph, blanks empties', () => {
        const r = varianceCell();
        expect(r({ value: 20 } as never)).toContain('▲');
        expect(r({ value: 20 } as never)).toContain('+20');
        expect(r({ value: -10 } as never)).toContain('▼');
        expect(r({ value: null } as never)).toBe('');
    });
});

describe('TreeTableComponent', () => {
    async function create(groupDefaultExpanded = 1) {
        TestBed.configureTestingModule({
            imports: [TreeTableComponent],
            providers: [
                provideNoopAnimations(),
                { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            ],
        });
        await TestBed.compileComponents();
        const f = TestBed.createComponent(TreeTableComponent);
        f.componentRef.setInput('nodes', FOREST);
        f.componentRef.setInput('groupDefaultExpanded', groupDefaultExpanded);
        f.componentRef.setInput('columns', [{ field: 'e1' }, { field: 'e2' }, { field: 'delta' }]);
        f.detectChanges();
        return f;
    }

    it('flattens with the top level expanded by default and toggles a node', async () => {
        const c = (await create(1)).componentInstance;
        expect(c.flatRows().map((r) => r.__id)).toEqual(['north', 'north/a', 'north/b', 'south']);
        c.toggle('north'); // collapse
        expect(c.flatRows().map((r) => r.__id)).toEqual(['north', 'south']);
    });

    it('synthesizes a tree column ahead of the value columns', async () => {
        const c = (await create(0)).componentInstance;
        const ids = c.gridColumns().map((col) => col.colId ?? col.field);
        expect(ids[0]).toBe('__tree');
        expect(ids).toContain('e1');
        expect(ids).toContain('delta');
    });

    it('has no a11y violations', async () => {
        const f = await create(1);
        await expectNoA11yViolations(f.nativeElement);
    });
});
