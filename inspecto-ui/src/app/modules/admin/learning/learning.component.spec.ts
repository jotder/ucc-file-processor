import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { CaseFeedback, LearningService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { LearningComponent } from './learning.component';

const FB = (id: string, rating: 'HELPFUL' | 'NOT_HELPFUL'): CaseFeedback => ({
    id,
    caseId: 'case-' + id,
    rating,
    note: 'note ' + id,
    submittedBy: 'alice',
    at: '2026-07-21T10:00:00Z',
});

async function create(overrides: Partial<Record<keyof LearningService, unknown>> = {}) {
    const toastr = { info: vi.fn(), error: vi.fn(), warning: vi.fn(), success: vi.fn() };
    const api = {
        feedback: () => of([FB('1', 'HELPFUL'), FB('2', 'HELPFUL'), FB('3', 'NOT_HELPFUL')]),
        rateCase: vi.fn(),
        similarCases: vi.fn(),
        ...overrides,
    } as unknown as LearningService;
    TestBed.configureTestingModule({
        imports: [LearningComponent],
        providers: [
            provideNoopAnimations(),
            { provide: LearningService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(LearningComponent);
    fixture.detectChanges();
    return { fixture, api, toastr };
}

describe('LearningComponent', () => {
    it('loads feedback and computes the helpful-rate KPIs', async () => {
        const { fixture } = await create();
        const c = fixture.componentInstance;
        expect(c.total).toBe(3);
        expect(c.helpful).toBe(2);
        expect(c.notHelpful).toBe(1);
        expect(c.helpfulRate).toBe(67); // 2/3 → 67%
        expect(fixture.nativeElement.textContent).toContain('Learning');
    });

    it('helpful-rate is null (—) with no feedback', async () => {
        const { fixture } = await create({ feedback: () => of([]) });
        expect(fixture.componentInstance.helpfulRate).toBeNull();
        expect(fixture.componentInstance.total).toBe(0);
    });

    it('degrades to an empty state + toast when the load fails', async () => {
        const { fixture, toastr } = await create({ feedback: () => throwError(() => ({ status: 503 })) });
        expect(fixture.componentInstance.feedback).toEqual([]);
        expect(fixture.componentInstance.loading).toBe(false);
        expect(toastr.error).toHaveBeenCalledWith('Failed to load feedback');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
