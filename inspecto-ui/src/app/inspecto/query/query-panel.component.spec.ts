import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { QueryPanelComponent } from './query-panel.component';
import { ConditionGroup, QuerySource } from './query-types';

const SOURCE: QuerySource = {
    name: 'cdr',
    rows: [
        { id: 1, cell: 'A', dur: 30 },
        { id: 2, cell: 'B', dur: 120 },
        { id: 3, cell: 'A', dur: 90 },
    ],
};

function create() {
    TestBed.configureTestingModule({
        imports: [QueryPanelComponent],
        providers: [provideNoopAnimations(), { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } }],
    });
    const f = TestBed.createComponent(QueryPanelComponent);
    f.componentInstance.source = SOURCE;
    f.detectChanges();
    return f;
}

const cellEqA: ConditionGroup = {
    kind: 'group',
    op: 'AND',
    items: [{ kind: 'condition', field: 'cell', operator: '=', value: 'A' }],
};

describe('QueryPanelComponent', () => {
    it('infers columns + previews all rows initially', () => {
        const c = create().componentInstance;
        expect(c.allColumnNames()).toEqual(['id', 'cell', 'dur']);
        expect(c.previewRows().length).toBe(3);
        expect(c.sql()).toContain('SELECT *');
    });

    it('filters the preview live as the builder changes', () => {
        const c = create().componentInstance;
        c.where.set(cellEqA);
        c.onWhereChanged();
        expect(c.previewRows().length).toBe(2);
        expect(c.sql()).toContain("\"cell\" = 'A'");
    });

    it('compares numerically over an inferred (column-less) source', () => {
        // SOURCE has no `columns` → types are inferred; a numeric >= must not compare as strings
        const c = create().componentInstance;
        c.where.set({ kind: 'group', op: 'AND', items: [{ kind: 'condition', field: 'dur', operator: '>=', value: '90' }] });
        c.onWhereChanged();
        expect(c.previewRows().length).toBe(2); // dur 120 and 90 (NOT a lexical '120' < '90')
        expect(c.sql()).toContain('"dur" >= 90'); // unquoted ⇒ typed as number
    });

    it('narrows the projected columns', () => {
        const c = create().componentInstance;
        c.setProjection(['id', 'cell']);
        expect(c.gridColumns().map((x) => x.field)).toEqual(['id', 'cell']);
        expect(c.sql()).toContain('SELECT "id", "cell"');
    });

    it('Edit SQL enters one-way override; Revert restores the builder', () => {
        const c = create().componentInstance;
        c.where.set(cellEqA);
        c.onWhereChanged();
        c.editSql();
        expect(c.overrideActive()).toBe(true);
        c.onOverrideInput('SELECT substring(cell, 1, 1) FROM cdr');
        expect(c.sql()).toContain('substring');
        expect(c.previewRows().length).toBe(3); // custom SQL ⇒ sample passthrough
        c.revertToBuilder();
        expect(c.overrideActive()).toBe(false);
        expect(c.previewRows().length).toBe(2); // builder filter restored
    });

    it('has no a11y violations', async () => {
        const f = create();
        await expectNoA11yViolations(f.nativeElement);
    });
});
