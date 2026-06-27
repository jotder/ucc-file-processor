import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { ComponentDef, ComponentsService, ParserPreview } from 'app/inspecto/api';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ParserConfigData, ParserConfigDialog } from './parser-config.dialog';

const TABLE_PREVIEW: ParserPreview = {
    kind: 'table', columns: ['id', 'msisdn'], rows: [{ id: '1', msisdn: 'x' }], rowCount: 1, rejectedRows: 0,
};

function saved(name: string): ComponentDef {
    return { type: 'grammar', name, ref: `grammar/${name}`, content: {} };
}

function create(opts: { data?: Partial<ParserConfigData>; grammars?: ComponentDef[] } = {}) {
    const close = vi.fn();
    const components = {
        list: () => of(opts.grammars ?? []),
        create: vi.fn((_t: string, c: Record<string, unknown>) => of(saved(String(c['id'])))),
        update: vi.fn((_t: string, id: string) => of(saved(id))),
        previewParse: vi.fn(() => of(TABLE_PREVIEW)),
        asn1Modules: vi.fn(() => of([{ name: 'cdr_3gpp_ts32297' }, { name: 'map_rel99' }])),
        asn1Module: vi.fn((name: string) => of({ name, text: `-- source of ${name}` })),
        uploadAsn1Module: vi.fn((name: string) => of({ name })),
    };
    TestBed.configureTestingModule({
        imports: [ParserConfigDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close, addPanelClass: vi.fn(), removePanelClass: vi.fn() } },
            {
                provide: MAT_DIALOG_DATA,
                useValue: {
                    node: { id: 'parse', type: 'parser.dsv' },
                    typeLabel: 'parser.dsv',
                    categoryLabel: 'Parser',
                    ...opts.data,
                },
            },
            { provide: ComponentsService, useValue: components },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            { provide: ToastrService, useValue: { success: () => {}, error: () => {} } },
        ],
    });
    const fixture = TestBed.createComponent(ParserConfigDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, close, components };
}

describe('ParserConfigDialog', () => {
    it('builds the DSV property sheet by default', () => {
        const { c } = create();
        expect(c.parserType()).toBe('dsv');
        expect(c.isHierarchical()).toBe(false);
        expect(c.propsForm().get('column_delimiter')).toBeTruthy();
        expect(c.propsForm().get('sample_rows')!.value).toBe(100);
    });

    it('rebuilds the sheet and flags hierarchical output on type change', () => {
        const { c } = create();
        c.onTypeChange('json');
        expect(c.parserType()).toBe('json');
        expect(c.isHierarchical()).toBe(true);
        expect(c.propsForm().get('root_path')).toBeTruthy();
        expect(c.propsForm().get('column_delimiter')).toBeNull();
    });

    it('loads a bound grammar (type + props) and locks the id', () => {
        const { c } = create({
            data: { node: { id: 'parse', type: 'parser.dsv', use: 'grammar/my_json' } },
            grammars: [{ type: 'grammar', name: 'my_json', ref: 'grammar/my_json', content: { parser_type: 'json', root_path: '$.items' } }],
        });
        expect(c.parserType()).toBe('json');
        expect(c.propsForm().get('root_path')!.value).toBe('$.items');
        expect(c.form.get('name')!.disabled).toBe(true);
        expect(c.form.get('name')!.value).toBe('my_json');
    });

    it('saves a new grammar and binds the node via use', () => {
        const { c, close, components } = create();
        c.form.get('name')!.setValue('cdr_csv');
        c.save();
        expect(components.create).toHaveBeenCalledWith('grammar', expect.objectContaining({ id: 'cdr_csv', parser_type: 'dsv' }));
        expect(close).toHaveBeenCalledWith({ node: expect.objectContaining({ use: 'grammar/cdr_csv' }) });
    });

    it('updates instead of creating when the id already exists', () => {
        const { c, components } = create({ grammars: [{ type: 'grammar', name: 'cdr_csv', ref: 'grammar/cdr_csv', content: { parser_type: 'dsv' } }] });
        c.onGrammarChange('cdr_csv');
        c.save();
        expect(components.update).toHaveBeenCalledWith('grammar', 'cdr_csv', expect.objectContaining({ parser_type: 'dsv' }));
        expect(components.create).not.toHaveBeenCalled();
    });

    it('previews a flat parse and feeds the rows to the query panel', () => {
        const { c, components } = create();
        c.test();
        expect(components.previewParse).toHaveBeenCalled();
        expect(c.preview()?.kind).toBe('table');
        expect(c.gridRows().length).toBe(1);
        expect(c.parsedSource().rows.length).toBe(1);
        expect(c.parsedSource().name).toBe('parsed');
    });

    it('loads the ASN.1 module library on switching to asn1', () => {
        const { c, components } = create();
        c.onTypeChange('asn1');
        expect(components.asn1Modules).toHaveBeenCalled();
        expect(c.asn1Modules().map((mod) => mod.name)).toEqual(['cdr_3gpp_ts32297', 'map_rel99']);
        expect(c.propsForm().get('schema_spec')).toBeTruthy();
    });

    it('binds + downloads a chosen ASN.1 module for the viewer', () => {
        const { c, components } = create();
        c.onTypeChange('asn1');
        c.onModuleSelect('map_rel99');
        expect(components.asn1Module).toHaveBeenCalledWith('map_rel99');
        expect(c.propsForm().get('schema_spec')!.value).toBe('map_rel99');
        expect(c.moduleText()).toBe('-- source of map_rel99');
    });

    it('maximizes a pane and restores the split layout', () => {
        const { c } = create();
        expect(c.maximized()).toBeNull();
        expect(c.paneVisible('props')).toBe(true);
        c.toggleMaximize('output');
        expect(c.maximized()).toBe('output');
        expect(c.bigOutput()).toBe(true);
        expect(c.paneVisible('props')).toBe(false);
        expect(c.paneVisible('output')).toBe(true);
        c.toggleMaximize('output');
        expect(c.maximized()).toBeNull();
    });

    it('toggles full screen via a dialog panel class', () => {
        const { c, fixture } = create();
        const ref = TestBed.inject(MatDialogRef);
        c.toggleFullscreen();
        expect(c.fullscreen()).toBe(true);
        expect(ref.addPanelClass).toHaveBeenCalledWith('dialog-fullscreen');
        c.toggleFullscreen();
        expect(c.fullscreen()).toBe(false);
        expect(ref.removePanelClass).toHaveBeenCalledWith('dialog-fullscreen');
        fixture.detectChanges();
    });

    it('does not save without a valid id', () => {
        const { c, close, components } = create();
        c.save();
        expect(components.create).not.toHaveBeenCalled();
        expect(close).not.toHaveBeenCalled();
    });

    it('has no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
