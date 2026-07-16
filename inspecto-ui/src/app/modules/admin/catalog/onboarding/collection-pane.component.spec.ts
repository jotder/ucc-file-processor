import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, ConnectionsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingCollectionPaneComponent } from './collection-pane.component';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
const WRITE_OK = { type: 'pipeline', written: true, path: 'x.toon', name: 'x', bytes: 1, overwritten: false, findings: [] };

function create(
    config: Record<string, unknown>,
    write = vi.fn((_type: string, _config: Record<string, unknown>, _opts?: unknown) => of(WRITE_OK)),
) {
    TestBed.configureTestingModule({
        imports: [OnboardingCollectionPaneComponent],
        providers: [
            provideNoopAnimations(),
            OnboardingStateService,
            { provide: ConfigService, useValue: { write } },
            { provide: ConnectionsService, useValue: { list: () => of([]), test: vi.fn(() => of({ reachable: true, detail: 'ok' })) } },
            { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const state = TestBed.inject(OnboardingStateService);
    state.config.set(config);
    const fixture = TestBed.createComponent(OnboardingCollectionPaneComponent);
    fixture.detectChanges();
    return { fixture, state, write };
}

describe('OnboardingCollectionPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('initialises the form from the existing collector block', () => {
        const { fixture } = create({ name: 'x', collector: { connector: 'sftp', duplicate: { mode: 'checksum' } } });
        const c = fixture.componentInstance;
        expect(c.initial['connector']).toBe('sftp');
        expect(c.initial['duplicate__mode']).toBe('checksum');
    });

    it('save nests dot keys into the collector block and marks the form pristine', () => {
        const { fixture, state, write } = create({ name: 'x' });
        const c = fixture.componentInstance;
        c.schemaForm.form.get('connector')?.setValue('sftp');
        c.schemaForm.form.get('connection')?.setValue('sftp_prod');
        c.schemaForm.form.get('include')?.setValue('*.csv, *.txt');
        c.save();
        expect(write).toHaveBeenCalledTimes(1);
        const written = write.mock.calls[0][1] as Record<string, unknown>;
        const collector = written['collector'] as Record<string, unknown>;
        expect(collector['connector']).toBe('sftp');
        expect(collector['connection']).toBe('sftp_prod');
        expect(collector['include']).toEqual(['*.csv', '*.txt']);
        expect(state.isDirty()).toBe(false);
    });

    it('registers its dirty check with the session state', () => {
        const { fixture, state } = create({ name: 'x' });
        const c = fixture.componentInstance;
        expect(state.isDirty()).toBe(false);
        c.schemaForm.form.get('connector')?.setValue('sftp');
        c.schemaForm.form.get('connector')?.markAsDirty();
        expect(state.isDirty()).toBe(true);
    });

    it('has no a11y violations', async () => {
        const { fixture } = create({ name: 'x' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
