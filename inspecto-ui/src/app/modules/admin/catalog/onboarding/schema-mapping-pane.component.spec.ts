import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, ParsingPreview, SchemaPreview, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingSchemaMappingPaneComponent } from './schema-mapping-pane.component';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
const CONVENTION_PATH = 'spaces/demo/config/orders_feed_schema.toon';
const WRITE_OK = (type: string) => ({ type, written: true, path: 'x.toon', name: 'x', bytes: 1, overwritten: false, findings: [] });
const SCHEMA_PREVIEW: SchemaPreview = { columns: ['ORDER_ID'], okCount: 2, rejectedCount: 0, rejectedRows: [] };

function delimitedPreview(): ParsingPreview {
    return {
        frontend: 'delimited',
        columns: ['ORDER_ID', 'QUANTITY'],
        rowCount: 2,
        rows: [{ ORDER_ID: '1001', QUANTITY: '3' }, { ORDER_ID: '1002', QUANTITY: '5' }],
        rejectedRows: 0,
    };
}

function create(config: Record<string, unknown>, api: Partial<ConfigService> = {}, parsePreview: ParsingPreview | null = delimitedPreview()) {
    TestBed.configureTestingModule({
        imports: [OnboardingSchemaMappingPaneComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            OnboardingStateService,
            {
                provide: ConfigService,
                useValue: {
                    write: vi.fn((type: string) => of(WRITE_OK(type))),
                    read: vi.fn(() => throwError(() => ({ status: 404 }))),
                    previewSchema: vi.fn(() => of(SCHEMA_PREVIEW)),
                    ...api,
                },
            },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'demo' } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const state = TestBed.inject(OnboardingStateService);
    state.config.set(config);
    if (parsePreview) state.parsePreview.set(parsePreview);
    const fixture = TestBed.createComponent(OnboardingSchemaMappingPaneComponent);
    fixture.detectChanges();
    return { fixture, state, api: TestBed.inject(ConfigService) };
}

describe('OnboardingSchemaMappingPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('derives fields from the parsed sample using the index selector for delimited', () => {
        const { fixture } = create({ name: 'orders_feed' });
        const c = fixture.componentInstance;
        expect(c.fieldRows.length).toBe(2);
        expect(c.fieldRows.at(0).get('name')?.value).toBe('ORDER_ID');
        expect(c.fieldRows.at(0).get('selector')?.value).toBe('0');
        expect(c.fieldRows.at(1).get('selector')?.value).toBe('1');
    });

    it('derives the verbatim key as the selector for a json/text_regex sample', () => {
        const preview: ParsingPreview = { frontend: 'json', columns: ['orderId'], rowCount: 1, rows: [{ orderId: '1' }], rejectedRows: 0 };
        const { fixture } = create({ name: 'orders_feed' }, {}, preview);
        const c = fixture.componentInstance;
        expect(c.fieldRows.at(0).get('selector')?.value).toBe('orderId');
        expect(c.fieldRows.at(0).get('name')?.value).toBe('ORDERID'); // sanitized identifier, not the raw key
    });

    it('shows a foreign-managed banner instead of the editor for a schema_file outside its convention', () => {
        const { fixture } = create({ name: 'orders_feed', processing: { schema_file: 'spaces/demo/config/hand_authored.toon' } });
        expect(fixture.componentInstance.foreignManaged()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('Schema managed elsewhere');
    });

    it('resumes by reading back a previously saved schema, pristine', () => {
        const read = vi.fn(() => of({
            type: 'schema', name: 'orders_feed_schema', path: 'orders_feed_schema.toon',
            config: { partitionKey: 'ORDER_DATE', raw: { name: 'orders_feed_schema', format: 'CSV', fields: [{ name: 'ORDER_ID', selector: '0', type: 'VARCHAR' }] } },
        }));
        const { fixture } = create({ name: 'orders_feed', processing: { schema_file: CONVENTION_PATH } }, { read });
        const c = fixture.componentInstance;
        expect(c.fieldRows.length).toBe(1);
        expect(c.fieldRows.at(0).get('name')?.value).toBe('ORDER_ID');
        expect(c.partitionKeyControl.value).toBe('ORDER_DATE');
        expect(c.fieldsForm.dirty).toBe(false);
    });

    it('validate types casts only the included rows against the parsed rows', () => {
        const previewSchema = vi.fn((_config: Record<string, unknown>, _rows: Record<string, unknown>[]) => of(SCHEMA_PREVIEW));
        const { fixture, state } = create({ name: 'orders_feed' }, { previewSchema });
        const c = fixture.componentInstance;
        c.fieldRows.at(1).get('include')?.setValue(false); // drop QUANTITY
        c.testTypes();
        const [content, rows] = previewSchema.mock.calls[0] as [{ raw: { fields: { name: string }[] } }, unknown[]];
        expect(content.raw.fields.map((f) => f.name)).toEqual(['ORDER_ID']);
        expect(rows).toEqual(delimitedPreview().rows);
        expect(state.schemaPreview()).toEqual(SCHEMA_PREVIEW);
    });

    it('save writes the schema config then links it into the pipeline draft', () => {
        const write = vi.fn((type: string, _config: Record<string, unknown>, _opts?: unknown) => of(WRITE_OK(type)));
        const { fixture } = create({ name: 'orders_feed', dirs: { poll: 'in' } }, { write });
        fixture.componentInstance.save();
        expect(write).toHaveBeenCalledTimes(2);
        const [schemaType, schemaDraft] = write.mock.calls[0] as [string, Record<string, unknown>];
        expect(schemaType).toBe('schema');
        expect((schemaDraft['raw'] as Record<string, unknown>)['name']).toBe('orders_feed_schema');
        expect((schemaDraft['mapping'] as Record<string, unknown>)['rules']).toHaveLength(2);
        const [pipelineType, pipelineDraft] = write.mock.calls[1] as [string, Record<string, unknown>];
        expect(pipelineType).toBe('pipeline');
        expect((pipelineDraft['processing'] as Record<string, unknown>)['schema_file']).toBe(CONVENTION_PATH);
    });

    it('blocks save on a duplicate field name', () => {
        const write = vi.fn((type: string) => of(WRITE_OK(type)));
        const { fixture } = create({ name: 'orders_feed' }, { write });
        const c = fixture.componentInstance;
        c.fieldRows.at(1).get('name')?.setValue('ORDER_ID');
        c.save();
        expect(write).not.toHaveBeenCalled();
        expect(TOASTR.warning).toHaveBeenCalled();
    });

    it('shows the honest full-replace load-policy note when serving the Reference keys stage', () => {
        const { fixture, state } = create({ name: 'region_dim', produces: 'reference' });
        expect(state.kind()).toBe('reference');
        expect(fixture.nativeElement.textContent).toContain('Load policy: full replace');
    });

    it('has no a11y violations', async () => {
        const { fixture } = create({ name: 'orders_feed' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
