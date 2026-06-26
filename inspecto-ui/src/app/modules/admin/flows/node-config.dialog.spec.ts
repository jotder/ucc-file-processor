import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { ComponentDef, ComponentsService, FlowsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { NodeConfigData, NodeConfigDialog } from './node-config.dialog';

const GRAMMARS: ComponentDef[] = [
    { type: 'grammar', name: 'cdr_csv', ref: 'grammar/cdr_csv', content: { delimiter: ',' } },
    { type: 'grammar', name: 'pipe_delimited', ref: 'grammar/pipe_delimited', content: { delimiter: '|' } },
];

function create(data: Partial<NodeConfigData> = {}) {
    TestBed.configureTestingModule({
        imports: [NodeConfigDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: () => {} } },
            { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
            {
                provide: MAT_DIALOG_DATA,
                useValue: {
                    node: { id: 'parse', type: 'parser.dsv' },
                    typeLabel: 'parser.dsv',
                    categoryLabel: 'Parser',
                    bindKind: 'grammar',
                    ...data,
                },
            },
            { provide: FlowsService, useValue: { testNode: () => of({}) } },
            { provide: ComponentsService, useValue: { list: () => of(GRAMMARS) } },
        ],
    });
    const fixture = TestBed.createComponent(NodeConfigDialog);
    fixture.detectChanges();
    return fixture;
}

describe('NodeConfigDialog', () => {
    it('loads existing components of the bound kind for the picker', () => {
        const c = create().componentInstance;
        expect(c.componentOptions().map((o) => o.name)).toEqual(['cdr_csv', 'pipe_delimited']);
        expect(c.bindLabel).toBe('Grammar');
    });

    it('binds and reads back the chosen component as a <kind>/<id> ref', () => {
        const c = create().componentInstance;
        c.selectComponent('cdr_csv');
        expect(c.form.value.use).toBe('grammar/cdr_csv');
        expect(c.selectedComponentId()).toBe('cdr_csv');
        c.selectComponent(null);
        expect(c.form.value.use).toBe('');
        expect(c.selectedComponentId()).toBeNull();
    });

    it('falls back to free-text use when no kind is bound', () => {
        const c = create({ bindKind: null }).componentInstance;
        expect(c.componentOptions()).toEqual([]);
    });

    it('has no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
