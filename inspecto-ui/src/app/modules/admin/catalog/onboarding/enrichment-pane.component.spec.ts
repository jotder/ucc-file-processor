import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { CatalogService, ConfigService, MetadataNode, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingEnrichmentPaneComponent } from './enrichment-pane.component';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
const WRITE_OK = (type: string, name: string) => ({
    // Mirrors the real fileBase convention: suffix once, never double.
    type, written: true, path: name.endsWith('_enrich') ? `${name}.toon` : `${name}_enrich.toon`,
    name, bytes: 1, overwritten: false, findings: [],
});
const REGISTER_OK = { registered: true, name: 'orders_feed_enrich', path: 'orders_feed_enrich.toon', findings: [] };

const PRODUCED_REF: MetadataNode = {
    id: 'ref:region_dim', kind: 'REFERENCE_DATASET', label: 'REGION_DIM',
    attrs: { pipeline: 'region_dim', active: true },
} as MetadataNode;
const PATH_REF: MetadataNode = {
    id: 'ref:daily/zones', kind: 'REFERENCE_DATASET', label: 'zones',
    attrs: { path: 'data/zones.csv', format: 'CSV' },
} as MetadataNode;

function create(
    config: Record<string, unknown>,
    opts: { api?: Partial<ConfigService>; refs?: MetadataNode[]; enrichment?: Record<string, unknown> | null } = {},
) {
    TestBed.configureTestingModule({
        imports: [OnboardingEnrichmentPaneComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            OnboardingStateService,
            {
                provide: ConfigService,
                useValue: {
                    write: vi.fn((type: string, cfg: Record<string, unknown>, _opts?: unknown) =>
                        of(WRITE_OK(type, String(cfg['name'] ?? 'x')))),
                    read: vi.fn(() => throwError(() => ({ status: 404 }))),
                    registerEnrichment: vi.fn((_path: string) => of(REGISTER_OK)),
                    ...opts.api,
                },
            },
            {
                provide: CatalogService,
                useValue: { references: vi.fn(() => of(opts.refs ?? [PRODUCED_REF, PATH_REF])) },
            },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'demo' } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const state = TestBed.inject(OnboardingStateService);
    state.name.set(String(config['name'] ?? ''));
    state.config.set(config);
    if (opts.enrichment !== undefined) state.enrichmentConfig.set(opts.enrichment);
    const fixture = TestBed.createComponent(OnboardingEnrichmentPaneComponent);
    fixture.detectChanges();
    return { fixture, state, api: TestBed.inject(ConfigService) };
}

const PIPELINE = { name: 'orders_feed', dirs: { poll: 'in', database: 'spaces/demo/data/orders_feed/database' } };

describe('OnboardingEnrichmentPaneComponent', () => {
    beforeEach(() => {
        localStorage.removeItem('inspecto.currentLens');
        TOASTR.success.mockClear();
        TOASTR.warning.mockClear();
        TOASTR.error.mockClear();
    });

    it('opens as an opt-in empty state (the stage is optional) and starts on demand', () => {
        const { fixture } = create(PIPELINE);
        const c = fixture.componentInstance;
        expect(c.started()).toBe(false);
        expect(fixture.nativeElement.textContent).toContain('Optional');
        c.start();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('Transform');
    });

    it('offers only pipeline-produced references, excluding this pipeline itself', () => {
        const self: MetadataNode = {
            id: 'ref:orders_feed', kind: 'REFERENCE_DATASET', label: 'self',
            attrs: { pipeline: 'orders_feed' },
        } as MetadataNode;
        const { fixture } = create(PIPELINE, { refs: [PRODUCED_REF, PATH_REF, self] });
        expect(fixture.componentInstance.referenceOptions()).toEqual([
            { id: 'region_dim', label: 'REGION_DIM' },
        ]);
    });

    it('hydrates from an existing companion config, pristine', () => {
        const { fixture } = create(PIPELINE, {
            enrichment: {
                name: 'orders_feed_enrich',
                references: { region_dim: { ref: 'region_dim' }, zones: { path: 'data/zones.csv', format: 'CSV' } },
                transform: 'SELECT * FROM input i LEFT JOIN region_dim r ON i.REGION = r.region',
            },
        });
        const c = fixture.componentInstance;
        expect(c.started()).toBe(true);
        expect(c.referenceRows.length).toBe(2);
        expect(c.referenceRows.at(0).get('mode')?.value).toBe('ref');
        expect(c.referenceRows.at(1).get('mode')?.value).toBe('path');
        expect(c.sql()).toContain('LEFT JOIN region_dim');
        expect(c.referencesForm.dirty).toBe(false);
    });

    it('hydrates when the companion read lands after the pane mounted', () => {
        const { fixture, state } = create(PIPELINE);
        expect(fixture.componentInstance.started()).toBe(false);
        state.enrichmentConfig.set({ name: 'orders_feed_enrich', transform: 'SELECT 1 FROM input' });
        fixture.detectChanges();
        expect(fixture.componentInstance.started()).toBe(true);
        expect(fixture.componentInstance.sql()).toBe('SELECT 1 FROM input');
    });

    it('save writes the companion with derived wiring, then hot-registers it', () => {
        const write = vi.fn((type: string, cfg: Record<string, unknown>, _opts?: unknown) =>
            of(WRITE_OK(type, String(cfg['name'] ?? 'x'))));
        const registerEnrichment = vi.fn((_path: string) => of(REGISTER_OK));
        const { fixture, state } = create(PIPELINE, { api: { write, registerEnrichment } });
        const c = fixture.componentInstance;
        c.start();
        c.addReference();
        c.referenceRows.at(0).get('name')?.setValue('region_dim');
        c.referenceRows.at(0).get('ref')?.setValue('region_dim');
        c.onSqlChange('SELECT i.*, r.zone FROM input i LEFT JOIN region_dim r ON i.REGION = r.region');
        c.save();

        const [type, draft] = write.mock.calls[0] as [string, Record<string, unknown>];
        expect(type).toBe('enrichment');
        expect(draft['name']).toBe('orders_feed_enrich');
        expect(draft['references']).toEqual({ region_dim: { ref: 'region_dim' } });
        expect((draft['triggers'] as Record<string, unknown>)['on_pipeline']).toBe('orders_feed');
        expect((draft['input'] as Record<string, unknown>)['database'])
            .toBe('spaces/demo/data/orders_feed/database');
        expect((draft['output'] as Record<string, unknown>)['database'])
            .toBe('spaces/demo/data/enriched/orders_feed_enrich');
        expect(registerEnrichment).toHaveBeenCalledWith('orders_feed_enrich.toon');
        expect(state.enrichmentConfig()).toEqual(draft);
        expect(TOASTR.success).toHaveBeenCalled();
    });

    it('keeps the save but warns when registration fails', () => {
        const registerEnrichment = vi.fn((_path: string) => throwError(() => ({ status: 503 })));
        const { fixture, state } = create(PIPELINE, { api: { registerEnrichment } });
        const c = fixture.componentInstance;
        c.start();
        c.onSqlChange('SELECT * FROM input');
        c.save();
        expect(state.enrichmentConfig()).not.toBeNull();
        expect(TOASTR.warning).toHaveBeenCalled();
    });

    it('blocks save on a duplicate reference alias', () => {
        const write = vi.fn((type: string, cfg: Record<string, unknown>, _opts?: unknown) =>
            of(WRITE_OK(type, String(cfg['name'] ?? 'x'))));
        const { fixture } = create(PIPELINE, { api: { write } });
        const c = fixture.componentInstance;
        c.start();
        c.addReference();
        c.addReference();
        c.referenceRows.at(0).get('name')?.setValue('dupe');
        c.referenceRows.at(0).get('ref')?.setValue('a');
        c.referenceRows.at(1).get('name')?.setValue('dupe');
        c.referenceRows.at(1).get('ref')?.setValue('b');
        c.save();
        expect(write).not.toHaveBeenCalled();
        expect(TOASTR.warning).toHaveBeenCalled();
    });

    it('has no a11y violations in both the opt-in and form states', async () => {
        const { fixture } = create(PIPELINE);
        await expectNoA11yViolations(fixture.nativeElement);
        fixture.componentInstance.start();
        fixture.componentInstance.addReference();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
