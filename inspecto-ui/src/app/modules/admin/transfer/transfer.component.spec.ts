import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ComponentsService, ConnectionsService, JobsService, LensService, PipelinesService, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { buildBundle, planImport } from './bundle';
import { TransferComponent } from './transfer.component';

const DATASET_DEF = { type: 'dataset', name: 'cdr_sample', ref: 'dataset/cdr_sample', content: { name: 'cdr_sample' } };
const WIDGET_DEF = { type: 'widget', name: 'cost_by_tariff', ref: 'widget/cost_by_tariff', content: { vizType: 'bar', datasetId: 'cdr_sample', controls: {} } };
const PIPELINE = { name: 'cdr_ingest', active: true, nodes: [{ id: 'c', type: 'collector.file', use: 'connections/cdr_sftp_prod' }], edges: [] };
const CONNECTION = { id: 'cdr_sftp_prod', connector: 'sftp' };
const JOB = { name: 'enrich_roaming', type: 'enrich', cron: null, onPipeline: 'cdr_ingest', enabled: true, catchUp: false, params: {}, lastStatus: 'SUCCESS' };

function create(opts: { canAuthor?: boolean; failCreate?: boolean } = {}) {
    const create = opts.failCreate
        ? vi.fn(() => throwError(() => ({ status: 409, error: { error: 'exists' } })))
        : vi.fn((_type: string, content: Record<string, unknown>) => of({ content }));
    const update = vi.fn(() => of({}));
    const componentLists: Record<string, unknown[]> = { dataset: [DATASET_DEF], widget: [WIDGET_DEF] };
    TestBed.configureTestingModule({
        imports: [TransferComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ComponentsService, useValue: { list: (t: string) => of(componentLists[t] ?? []), create, update } },
            { provide: ConnectionsService, useValue: { list: () => of([CONNECTION]), create: vi.fn(() => of(CONNECTION)), update: vi.fn(() => of(CONNECTION)) } },
            {
                provide: PipelinesService,
                useValue: {
                    authoredList: () => of([{ name: 'cdr_ingest' }]),
                    authoredRaw: () => of(PIPELINE),
                    createAuthored: vi.fn(() => of({})),
                    replaceAuthored: vi.fn(() => of({})),
                },
            },
            {
                provide: JobsService,
                useValue: {
                    list: () => of([{ name: 'enrich_roaming' }]),
                    get: () => of(JOB),
                    create: vi.fn(() => of({})),
                    update: vi.fn(() => of({})),
                },
            },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'staging' } },
            { provide: LensService, useValue: { canAuthorWorkbench: () => opts.canAuthor !== false } },
            { provide: ToastrService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(TransferComponent);
    return { fixture, c: fixture.componentInstance, createSpy: create, updateSpy: update };
}

describe('TransferComponent', () => {
    it('loads every artifact family into export groups (components + connections + pipelines + jobs)', () => {
        const { fixture, c } = create();
        fixture.detectChanges();
        expect(c.groups().map((g) => g.kind)).toEqual(['connection', 'dataset', 'widget', 'authored-pipeline', 'job']);
        // A job's transportable content is the upsert shape — runtime state never travels.
        const job = c.allItems().find((i) => i.kind === 'job')!;
        expect(job.content['onPipeline']).toBe('cdr_ingest');
        expect(job.content['lastStatus']).toBeUndefined();
    });

    it('exports the selection expanded to its dependency closure', async () => {
        const { fixture, c } = create();
        fixture.detectChanges();
        let captured: Blob | null = null;
        URL.createObjectURL = vi.fn((b: Blob) => {
            captured = b;
            return 'blob:x';
        });
        URL.revokeObjectURL = vi.fn();
        const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
        c.toggleItem({ kind: 'widget', id: 'cost_by_tariff', content: WIDGET_DEF.content });
        expect(c.selectedCount()).toBe(1);
        c.exportBundle();
        click.mockRestore();
        const bundle = JSON.parse(await captured!.text());
        expect(bundle.format).toBe('inspecto-metadata-bundle');
        expect(bundle.sourceSpace).toBe('staging');
        // The widget's dataset came along, so the viz renders as-is on the target.
        expect(bundle.items.map((i: { id: string }) => i.id)).toEqual(['cdr_sample', 'cost_by_tariff']);
    });

    it('applies an import: creates new items, overwrites only when chosen, reports per-row results', () => {
        const { fixture, c, createSpy, updateSpy } = create();
        fixture.detectChanges();
        const bundle = buildBundle(
            [
                { kind: 'dataset', id: 'cdr_sample', content: { name: 'cdr_sample' } }, // exists on target
                { kind: 'dataset', id: 'new_ds', content: { name: 'new_ds' } }, // new
            ],
            'staging',
        );
        c.rows.set(planImport(bundle, new Map([['dataset', new Set(['cdr_sample'])]])));
        c.overwriteAllExisting();
        c.apply();
        expect(createSpy).toHaveBeenCalledWith('dataset', { id: 'new_ds', name: 'new_ds' });
        expect(updateSpy).toHaveBeenCalledWith('dataset', 'cdr_sample', { name: 'cdr_sample' });
        expect(c.rows().map((r) => r.result)).toEqual(['overwritten', 'imported']);
        expect(c.applied()).toBe(true);
    });

    it('skip is the default for existing items — nothing is overwritten unless chosen', () => {
        const { fixture, c, updateSpy } = create();
        fixture.detectChanges();
        const bundle = buildBundle([{ kind: 'dataset', id: 'cdr_sample', content: {} }], null);
        c.rows.set(planImport(bundle, new Map([['dataset', new Set(['cdr_sample'])]])));
        c.apply();
        expect(updateSpy).not.toHaveBeenCalled();
        expect(c.actionableCount()).toBe(0);
    });

    it('read-only lens cannot apply', () => {
        const { fixture, c, createSpy } = create({ canAuthor: false });
        fixture.detectChanges();
        c.rows.set(planImport(buildBundle([{ kind: 'dataset', id: 'x', content: {} }], null), new Map()));
        c.apply();
        expect(createSpy).not.toHaveBeenCalled();
    });

    it('a failed write reports on its row without aborting the rest', () => {
        const { fixture, c } = create({ failCreate: true });
        fixture.detectChanges();
        const bundle = buildBundle(
            [
                { kind: 'dataset', id: 'a', content: {} },
                { kind: 'dataset', id: 'b', content: {} },
            ],
            null,
        );
        c.rows.set(planImport(bundle, new Map()));
        c.apply();
        expect(c.rows().every((r) => r.result === 'failed')).toBe(true);
        expect(c.rows().every((r) => !!r.message)).toBe(true);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
