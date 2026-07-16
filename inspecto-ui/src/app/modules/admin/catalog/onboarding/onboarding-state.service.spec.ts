import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService } from 'app/inspecto/api';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };

function create(configApi: Partial<ConfigService> = {}) {
    TestBed.configureTestingModule({
        providers: [
            OnboardingStateService,
            { provide: ConfigService, useValue: { read: () => of({ config: {} }), write: () => of({ findings: [] }), ...configApi } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    return TestBed.inject(OnboardingStateService);
}

describe('OnboardingStateService', () => {
    it('derives kind, stages and lifecycle from the config', () => {
        const s = create();
        s.config.set({ name: 'r', produces: 'reference', active: false });
        expect(s.kind()).toBe('reference');
        expect(s.stages().map((x) => x.id)).toEqual(['collection', 'parsing', 'keys', 'publish']);
        expect(s.lifecycle()).toBe('Draft');
        s.config.set({ name: 's', active: true });
        expect(s.kind()).toBe('stream');
        expect(s.lifecycle()).toBe('Live');
    });

    it('computes stage readiness from the config blocks, never stored state', () => {
        const s = create();
        s.config.set({
            name: 'x',
            collector: { connector: 'local' },
            parsing: { frontend: 'delimited' },
            processing: { schema_file: 's.toon' },
            output: { format: 'CSV' },
        });
        const status = s.stageStatus();
        expect(status.collection).toBe('configured');
        expect(status.parsing).toBe('configured');
        expect(status.schema).toBe('configured');
        expect(status.publish).toBe('configured');
        expect(s.lifecycle()).toBe('Ready');
        // A successful parse test upgrades parsing to validated (session-scoped).
        s.parsePreview.set({ frontend: 'delimited', columns: [], rowCount: 1, rows: [], rejectedRows: 0 });
        expect(s.stageStatus().parsing).toBe('validated');
    });

    it('lands a resumed session on the first incomplete stage in data-path order', () => {
        const s = create();
        s.config.set({ name: 'x', collector: { connector: 'local' } });
        expect(s.firstOpenStage()).toBe('parsing');
        s.config.set({ name: 'x' });
        expect(s.firstOpenStage()).toBe('collection');
        // Implemented stages done → the next step is the (placeholder) schema stage, honestly.
        s.config.set({ name: 'x', collector: { connector: 'local' }, parsing: { frontend: 'delimited' } });
        expect(s.firstOpenStage()).toBe('schema');
    });

    it('saveBlock deep-merges the patch and writes with overwrite', () => {
        const write = vi.fn(() => of({ findings: [] }));
        const s = create({ write } as unknown as Partial<ConfigService>);
        s.config.set({ name: 'x', dirs: { poll: 'in' }, collector: { fetch: { mode: 'seq' } } });
        s.saveBlock({ collector: { connector: 'sftp' } }).subscribe();
        expect(write).toHaveBeenCalledWith(
            'pipeline',
            { name: 'x', dirs: { poll: 'in' }, collector: { fetch: { mode: 'seq' }, connector: 'sftp' } },
            { overwrite: true },
        );
        expect((s.config() as Record<string, unknown>)['collector']).toEqual({ fetch: { mode: 'seq' }, connector: 'sftp' });
    });

    it('marks the draft missing on a 404 read', () => {
        const s = create({ read: () => throwError(() => ({ status: 404 })) } as unknown as Partial<ConfigService>);
        s.load('ghost');
        expect(s.missing()).toBe(true);
        expect(s.loading()).toBe(false);
    });

    it('the Reference keys stage mirrors the schema readiness and is required for Ready', () => {
        const s = create();
        s.config.set({
            name: 'region_dim', produces: 'reference',
            collector: { connector: 'local' }, parsing: { frontend: 'delimited' }, output: { format: 'CSV' },
        });
        expect(s.stageStatus().keys).toBe('empty');
        expect(s.lifecycle()).toBe('Draft'); // no schema yet — a schema-less pipeline cannot arm
        s.config.set({ ...(s.config() as Record<string, unknown>), processing: { schema_file: 's.toon' } });
        expect(s.stageStatus().keys).toBe('configured');
        expect(s.lifecycle()).toBe('Ready');
    });

    it('the enrichment stage reads the companion config and loads it for streams', () => {
        const read = vi.fn((type: string) => type === 'enrichment'
            ? of({ config: { name: 'x_enrich', transform: 'SELECT 1' } })
            : of({ config: { name: 'x' } }));
        const s = create({ read } as unknown as Partial<ConfigService>);
        expect(s.stageStatus().enrichment).toBe('empty');
        s.load('x');
        expect(read).toHaveBeenCalledWith('enrichment', 'x_enrich');
        expect(s.enrichmentConfig()).toEqual({ name: 'x_enrich', transform: 'SELECT 1' });
        expect(s.stageStatus().enrichment).toBe('configured');
        // Optional: a missing companion never blocks Ready (covered by lifecycle's optional filter).
        expect(s.stages().find((x) => x.id === 'enrichment')?.optional).toBe(true);
    });

    it('exposes the engine-normalized pipeline id for on_pipeline wiring', () => {
        const s = create();
        s.config.set({ name: 'Orders Feed' });
        expect(s.normalizedName()).toBe('orders_feed');
    });
});
