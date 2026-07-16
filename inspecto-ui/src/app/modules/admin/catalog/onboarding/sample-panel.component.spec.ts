import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingSamplePanelComponent } from './sample-panel.component';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };

function create() {
    TestBed.configureTestingModule({
        imports: [OnboardingSamplePanelComponent],
        providers: [
            provideNoopAnimations(),
            OnboardingStateService,
            { provide: ConfigService, useValue: { read: () => of({ config: {} }) } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const state = TestBed.inject(OnboardingStateService);
    const fixture = TestBed.createComponent(OnboardingSamplePanelComponent);
    fixture.detectChanges();
    return { fixture, state };
}

describe('OnboardingSamplePanelComponent', () => {
    it('captures pasted text as the session sample and resets the parse thread', () => {
        const { fixture, state } = create();
        state.parsePreview.set({ frontend: 'delimited', columns: [], rowCount: 0, rows: [], rejectedRows: 0 });
        const c = fixture.componentInstance;
        c.pasting.set(true);
        c.pasteText = 'a,b\n1,2';
        c.usePasted();
        expect(state.sample()).toEqual({ name: 'pasted sample', text: 'a,b\n1,2' });
        expect(state.parsePreview()).toBeNull();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('2 lines');
        expect(fixture.nativeElement.textContent).toContain('Not tested yet');
    });

    it('clear removes the sample and the downstream results', () => {
        const { fixture, state } = create();
        state.captureSample('s.csv', 'x\n');
        fixture.detectChanges();
        fixture.componentInstance.clear();
        expect(state.sample()).toBeNull();
    });

    it('has no a11y violations (empty and captured states)', async () => {
        const { fixture, state } = create();
        await expectNoA11yViolations(fixture.nativeElement);
        state.captureSample('s.csv', 'a,b\n1,2\n');
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
