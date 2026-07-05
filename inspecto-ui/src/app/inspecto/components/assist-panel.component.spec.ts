import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { AssistResult, AssistService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AssistPanelComponent } from './assist-panel.component';

const RESULT: AssistResult = {
    intent: 'explain-entity',
    status: 'OK',
    answer: 'This pipeline ingests CDR files hourly.',
    citations: [],
    links: [],
    confidence: 0.9,
    validated: true,
    data: { sql: 'SELECT 1', nextRuns: ['2026-07-05T10:00'] },
};

function create() {
    const api = { run: vi.fn(() => of(RESULT)) };
    TestBed.configureTestingModule({
        imports: [AssistPanelComponent],
        providers: [
            provideNoopAnimations(),
            { provide: AssistService, useValue: api },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined, warning: () => undefined } },
        ],
    });
    const fixture = TestBed.createComponent(AssistPanelComponent);
    fixture.componentRef.setInput('intent', 'explain-entity');
    return { fixture, api };
}

describe('AssistPanelComponent', () => {
    it('runs the intent and renders the answer + SQL section', () => {
        const { fixture, api } = create();
        fixture.componentInstance.userText = 'explain this pipeline';
        fixture.detectChanges();
        fixture.componentInstance.run();
        fixture.detectChanges();
        expect(api.run).toHaveBeenCalledWith('explain-entity', expect.objectContaining({ userText: 'explain this pipeline' }));
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('This pipeline ingests CDR files hourly.');
        expect(text).toContain('SELECT 1');
    });

    it('has no a11y violations with a rendered result', async () => {
        const { fixture } = create();
        fixture.componentInstance.userText = 'explain';
        fixture.detectChanges();
        fixture.componentInstance.run();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
