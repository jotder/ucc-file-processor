import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, InboxStatus, RunsService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingPublishPaneComponent } from './publish-pane.component';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
const WRITE_OK = { type: 'pipeline', written: true, path: 'x.toon', name: 'x', bytes: 1, overwritten: false, findings: [] };
const PENDING: InboxStatus = { pipeline: 'x', inbox: 'spaces/demo/data/inbox/orders_feed', pending: 2, running: false };

const READY_CONFIG = {
    name: 'orders_feed',
    active: false,
    collector: { connector: 'local' },
    parsing: { frontend: 'delimited' },
    processing: { schema_file: 'x_schema.toon' },
};

function create(config: Record<string, unknown>, opts: { api?: Partial<ConfigService>; confirm?: boolean; runsApi?: Partial<RunsService> } = {}) {
    const write = vi.fn((_type: string, _config: Record<string, unknown>, _opts?: unknown) => of(WRITE_OK));
    const confirmFn = vi.fn(() => Promise.resolve(opts.confirm ?? true));
    TestBed.configureTestingModule({
        imports: [OnboardingPublishPaneComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            OnboardingStateService,
            { provide: ConfigService, useValue: { write, ...opts.api } },
            { provide: RunsService, useValue: { pending: vi.fn(() => of(PENDING)), ...opts.runsApi } },
            { provide: InspectoConfirmService, useValue: { confirm: confirmFn, confirmDestructive: vi.fn(() => Promise.resolve(true)) } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const state = TestBed.inject(OnboardingStateService);
    state.config.set(config);
    const fixture = TestBed.createComponent(OnboardingPublishPaneComponent);
    fixture.detectChanges();
    return { fixture, state, write, confirmFn };
}

describe('OnboardingPublishPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('save writes the output block with the schema-form defaults', () => {
        const { fixture, write } = create({ name: 'x' });
        fixture.componentInstance.save();
        const [type, config] = write.mock.calls[0] as [string, Record<string, unknown>];
        expect(type).toBe('pipeline');
        expect((config['output'] as Record<string, unknown>)['format']).toBe('PARQUET');
    });

    it('names the other incomplete required stages when far from ready', () => {
        const { fixture } = create({ name: 'x' });
        expect(fixture.componentInstance.blockedOn()).toEqual(['Collection', 'Parsing', 'Schema & Mapping']);
        expect(fixture.nativeElement.textContent).toContain('Collection, Parsing, Schema & Mapping');
    });

    it('asks to save output (not the other stages) when only the output block is missing', () => {
        const { fixture, state } = create(READY_CONFIG);
        expect(fixture.componentInstance.blockedOn()).toEqual([]);
        expect(state.lifecycle()).toBe('Draft');
        expect(fixture.nativeElement.textContent).toContain('Save the output settings above');
    });

    it('activate confirms, flips active, and refreshes the activity glance', async () => {
        const { fixture, state, write, confirmFn } = create({ ...READY_CONFIG, output: { format: 'PARQUET' } });
        expect(state.lifecycle()).toBe('Ready');
        await fixture.componentInstance.activate();
        expect(confirmFn).toHaveBeenCalled();
        const [, config] = write.mock.calls[0] as [string, Record<string, unknown>];
        expect(config['active']).toBe(true);
    });

    it('declining the confirm leaves the draft inactive', async () => {
        const { fixture, write } = create({ ...READY_CONFIG, output: { format: 'PARQUET' } }, { confirm: false });
        await fixture.componentInstance.activate();
        expect(write).not.toHaveBeenCalled();
    });

    it('shows the inbox activity glance once live', () => {
        const { fixture } = create({ ...READY_CONFIG, active: true, output: { format: 'PARQUET' } });
        expect(fixture.componentInstance.activity()).toEqual(PENDING);
        expect(fixture.nativeElement.textContent).toContain('pending in spaces/demo');
    });

    it('has no a11y violations', async () => {
        const { fixture } = create({ name: 'x' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
