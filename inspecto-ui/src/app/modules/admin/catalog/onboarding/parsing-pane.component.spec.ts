import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, ParsingPreview } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingParsingPaneComponent } from './parsing-pane.component';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
const PREVIEW: ParsingPreview = { frontend: 'delimited', columns: ['a'], rowCount: 1, rows: [{ a: '1' }], rejectedRows: 0 };
const WRITE_OK = { type: 'pipeline', written: true, path: 'x.toon', name: 'x', bytes: 1, overwritten: false, findings: [] };

function create(config: Record<string, unknown>, api: Partial<ConfigService> = {}) {
    TestBed.configureTestingModule({
        imports: [OnboardingParsingPaneComponent],
        providers: [
            provideNoopAnimations(),
            OnboardingStateService,
            { provide: ConfigService, useValue: { write: vi.fn(() => of(WRITE_OK)), previewParsing: vi.fn(() => of(PREVIEW)), ...api } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const state = TestBed.inject(OnboardingStateService);
    state.config.set(config);
    const fixture = TestBed.createComponent(OnboardingParsingPaneComponent);
    fixture.detectChanges();
    return { fixture, state, api: TestBed.inject(ConfigService) };
}

describe('OnboardingParsingPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('initialises from the existing parsing block and normalises the frontend', () => {
        const { fixture } = create({ name: 'x', parsing: { frontend: 'fixed_width', fixedwidth: { fields: [{ name: 'id', start: 0, length: 3 }] } } });
        const c = fixture.componentInstance;
        expect(c.frontend()).toBe('fixedwidth');
        expect(c.fwFields.length).toBe(1);
    });

    it('shows the plugin banner instead of the editor for plugin-parsed pipelines', () => {
        const { fixture } = create({ name: 'x', processing: { ingester: 'com.example.Ing' } });
        expect(fixture.componentInstance.pluginManaged).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('Plugin ingester');
    });

    it('test parse sends the merged draft + sample and stores the preview on the session', () => {
        const { fixture, state, api } = create({ name: 'x', dirs: { poll: 'in' } });
        state.captureSample('s.csv', 'a|b\n1|2\n');
        fixture.componentInstance.testParse();
        expect(api.previewParsing).toHaveBeenCalledTimes(1);
        const [draft, sample] = (api.previewParsing as ReturnType<typeof vi.fn>).mock.calls[0] as [Record<string, unknown>, string];
        expect((draft['parsing'] as Record<string, unknown>)['frontend']).toBe('delimited');
        expect(draft['dirs']).toEqual({ poll: 'in' });
        expect(sample).toBe('a|b\n1|2\n');
        expect(state.parsePreview()).toEqual(PREVIEW);
    });

    it('a failed test parse surfaces the error on the session thread', () => {
        const { fixture, state } = create(
            { name: 'x' },
            { previewParsing: vi.fn(() => throwError(() => ({ status: 422, error: { message: 'no parse' } }))) },
        );
        state.captureSample('s.csv', 'zzz');
        fixture.componentInstance.testParse();
        expect(state.parsePreview()).toBeNull();
        expect(state.parseError()).toBeTruthy();
    });

    it('switching frontend marks the pane dirty and save clears other frontend blocks', () => {
        const write = vi.fn((_type: string, _config: Record<string, unknown>, _opts?: unknown) => of(WRITE_OK));
        const { fixture, state } = create({ name: 'x', parsing: { frontend: 'json', json: { format: 'newline' } } }, { write });
        const c = fixture.componentInstance;
        c.setFrontend('text_regex');
        fixture.detectChanges(); // propagate [specs] so the schema-form rebuilds for the new frontend
        expect(state.isDirty()).toBe(true);
        const pattern = c.schemaForm?.form.get('text_regex__pattern');
        expect(pattern).toBeTruthy();
        pattern?.setValue('(?P<a>\\d+)');
        c.save();
        const written = write.mock.calls[0][1] as Record<string, unknown>;
        const parsing = written['parsing'] as Record<string, unknown>;
        expect(parsing['frontend']).toBe('text_regex');
        expect(parsing['json']).toBeUndefined();
        expect((parsing['text_regex'] as Record<string, unknown>)['pattern']).toBe('(?P<a>\\d+)');
    });

    it('has no a11y violations', async () => {
        const { fixture } = create({ name: 'x' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
