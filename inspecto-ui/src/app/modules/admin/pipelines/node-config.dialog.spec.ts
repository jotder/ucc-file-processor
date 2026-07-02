import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { AuthoredNode, ComponentDef, ComponentsService, PipelinesService } from 'app/inspecto/api';
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
            { provide: PipelinesService, useValue: { testNode: () => of({}) } },
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

    it('uses the free-form config editor for a type with no schema (parser.dsv)', () => {
        const c = create().componentInstance;
        expect(c.specs()).toEqual([]);
        expect(c.freeFormOpen()).toBe(true); // free-form is the primary surface
    });

    it('renders the schema-form for a known type and splits config into schema + free-form', () => {
        const fixture = create({
            node: { id: 'w', type: 'sink.file', config: { format: 'CSV', partition_by: 'day', custom_flag: 'x' } },
            typeLabel: 'sink.file', categoryLabel: 'Writer', bindKind: null,
        });
        const c = fixture.componentInstance;
        expect(c.specs().map((s) => s.key)).toContain('format');
        // schema-known keys seed the schema-form; the unknown key falls to the free-form editor
        expect(c.schemaInitial).toMatchObject({ format: 'CSV', partition_by: 'day' });
        expect(c.schemaInitial['custom_flag']).toBeUndefined();
        expect(c.configRows.length).toBe(1);
        expect(c.freeFormOpen()).toBe(true); // an extra key is present ⇒ shown
    });

    it('merges schema-form values with free-form rows on save', () => {
        let closed: { node: AuthoredNode } | undefined;
        const fixture = create({
            node: { id: 'w', type: 'sink.file', config: { format: 'CSV', extra: '1' } },
            typeLabel: 'sink.file', categoryLabel: 'Writer', bindKind: null,
        });
        const c = fixture.componentInstance;
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: (r) => (closed = r) };
        fixture.detectChanges(); // instantiate the schema-form ViewChild
        c.save();
        expect(closed?.node.config).toMatchObject({ format: 'CSV', extra: '1' });
    });

    it('has no a11y violations (free-form type)', async () => {
        await expectNoA11yViolations(create().nativeElement);
    });

    it('has no a11y violations (schema-backed type)', async () => {
        const fixture = create({
            node: { id: 'w', type: 'sink.file' }, typeLabel: 'sink.file', categoryLabel: 'Writer', bindKind: null,
        });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
