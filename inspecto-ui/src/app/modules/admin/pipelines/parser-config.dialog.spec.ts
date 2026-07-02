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
        const { c, fixture } = create();
        expect(c.parserType()).toBe('dsv');
        expect(c.isHierarchical()).toBe(false);
        expect(c.schemaFormSpecs().some((s) => s.key === 'column_delimiter')).toBe(true);
        fixture.detectChanges();
        expect(c.schemaForm.form.get('sample_rows')!.value).toBe(100); // advanced tier default still applied
    });

    it('rebuilds the sheet and flags hierarchical output on type change', () => {
        const { c, fixture } = create();
        c.onTypeChange('json');
        fixture.detectChanges();
        expect(c.parserType()).toBe('json');
        expect(c.isHierarchical()).toBe(true);
        expect(c.schemaFormSpecs().some((s) => s.key === 'root_path')).toBe(true);
        expect(c.schemaFormSpecs().some((s) => s.key === 'column_delimiter')).toBe(false);
    });

    it('loads a bound grammar (type + props) and skips the two-step save', () => {
        const { c, fixture } = create({
            data: { node: { id: 'parse', type: 'parser.dsv', use: 'grammar/my_json' } },
            grammars: [{ type: 'grammar', name: 'my_json', ref: 'grammar/my_json', content: { parser_type: 'json', root_path: '$.items' } }],
        });
        fixture.detectChanges();
        expect(c.parserType()).toBe('json');
        expect(c.schemaForm.form.get('root_path')!.value).toBe('$.items');
        expect(c.boundGrammarId()).toBe('my_json');
    });

    it('two-step create: config advances to a pre-filled name, then persists on the second save()', () => {
        const { c, close, components } = create();
        c.save(); // config valid ⇒ advance to the save step with a suggested name
        expect(c.step()).toBe('save');
        expect(c.saveForm.controls.name.value).toBe('dsv_grammar');
        expect(components.create).not.toHaveBeenCalled();

        c.saveForm.controls.name.setValue('cdr_csv');
        c.save();
        expect(components.create).toHaveBeenCalledWith('grammar', expect.objectContaining({ id: 'cdr_csv', parser_type: 'dsv' }));
        expect(close).toHaveBeenCalledWith({ node: expect.objectContaining({ use: 'grammar/cdr_csv' }) });
    });

    it('blocks the save step on a duplicate name', () => {
        const { c, components } = create({ grammars: [{ type: 'grammar', name: 'cdr_csv', ref: 'grammar/cdr_csv', content: { parser_type: 'dsv' } }] });
        c.save();
        c.saveForm.controls.name.setValue('cdr_csv');
        c.save();
        expect(c.saveForm.controls.name.hasError('duplicate')).toBe(true);
        expect(components.create).not.toHaveBeenCalled();
    });

    it('updates instead of creating when an existing grammar is chosen from the dropdown', () => {
        const { c, components } = create({ grammars: [{ type: 'grammar', name: 'cdr_csv', ref: 'grammar/cdr_csv', content: { parser_type: 'dsv' } }] });
        c.onGrammarChange('cdr_csv');
        c.save(); // bound ⇒ saves straight through, no two-step
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

    it('loads the ASN.1 module library on switching to asn1 and requires the schema module to save', () => {
        const { c, fixture, components } = create();
        c.onTypeChange('asn1');
        fixture.detectChanges();
        expect(components.asn1Modules).toHaveBeenCalled();
        expect(c.asn1Modules().map((mod) => mod.name)).toEqual(['cdr_3gpp_ts32297', 'map_rel99']);
        expect(c.moduleProp()?.key).toBe('schema_spec');

        c.save(); // encoding_rules has a default, but schema_spec is required and blank ⇒ blocked
        expect(c.step()).toBe('config');
        expect(c.moduleForm.get('schema_spec')?.touched).toBe(true);
    });

    it('binds + downloads a chosen ASN.1 module for the viewer', () => {
        const { c, fixture, components } = create();
        c.onTypeChange('asn1');
        fixture.detectChanges();
        c.moduleForm.get('schema_spec')?.setValue('map_rel99');
        c.onModuleSelect('map_rel99');
        expect(components.asn1Module).toHaveBeenCalledWith('map_rel99');
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

    it('backToConfig() returns from the save step without losing the typed name', () => {
        const { c } = create();
        c.save();
        c.saveForm.controls.name.setValue('cdr_csv');
        c.backToConfig();
        expect(c.step()).toBe('config');
        expect(c.saveForm.controls.name.value).toBe('cdr_csv');
    });

    it('has no a11y violations on the config step or the save step', async () => {
        const { fixture, c } = create();
        await expectNoA11yViolations(fixture.nativeElement);
        c.save();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
