import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { GammaConfigService } from '@gamma/services/config';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { AssistResult, AssistService } from 'app/inspecto/api';
import { DecisionRulesService } from 'app/inspecto/api/decision-rules.service';
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

function create(result: AssistResult = RESULT) {
    const api = { run: vi.fn(() => of(result)) };
    TestBed.configureTestingModule({
        imports: [AssistPanelComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            provideHttpClient(),
            provideHttpClientTesting(),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            { provide: AssistService, useValue: api },
            { provide: DecisionRulesService, useValue: {} },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined, warning: () => undefined } },
        ],
    });
    const fixture = TestBed.createComponent(AssistPanelComponent);
    fixture.componentRef.setInput('intent', 'explain-entity');
    return { fixture, api };
}

/** Run the panel's intent and flush the (synchronous `of()`) result into the DOM. */
function run(fixture: ReturnType<typeof TestBed.createComponent<AssistPanelComponent>>, userText = 'explain'): HTMLElement {
    fixture.componentInstance.userText = userText;
    fixture.detectChanges();
    fixture.componentInstance.run();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
}

describe('AssistPanelComponent', () => {
    it('runs the intent and renders the answer + SQL section', () => {
        const { fixture, api } = create();
        const el = run(fixture, 'explain this pipeline');
        expect(api.run).toHaveBeenCalledWith('explain-entity', expect.objectContaining({ userText: 'explain this pipeline' }));
        const text = el.textContent ?? '';
        expect(text).toContain('This pipeline ingests CDR files hourly.');
        expect(text).toContain('SELECT 1');
        // Nothing artifact-shaped in the data bag — the generic host doesn't mount.
        expect(el.querySelector('inspecto-a2ui-render')).toBeNull();
    });

    it('renders humanReadable + narrative through the generic A2UI host (S7)', () => {
        const { fixture } = create({ ...RESULT, data: { humanReadable: 'Every hour on the hour.', narrative: 'A longer story.' } });
        const el = run(fixture);
        const host = el.querySelector('inspecto-a2ui-render');
        expect(host).not.toBeNull();
        expect(host!.textContent).toContain('Every hour on the hour.');
        expect(host!.textContent).toContain('A longer story.');
    });

    it('renders sampleRows + findings as data-table artifacts through the host', () => {
        const { fixture } = create({
            ...RESULT,
            data: {
                sampleRows: [{ msisdn: '123', dur: 42 }],
                findings: [{ severity: 'error', fieldPath: 'a.b', message: 'bad' }],
            },
        });
        const el = run(fixture);
        const host = el.querySelector('inspecto-a2ui-render');
        expect(host).not.toBeNull();
        expect(host!.textContent).toContain('Sample rows (1)');
        expect(host!.textContent).toContain('Findings');
        expect(host!.querySelectorAll('inspecto-data-table')).toHaveLength(2);
    });

    it('uses a server-shaped result.artifact verbatim instead of composing one', () => {
        const { fixture } = create({
            ...RESULT,
            data: { humanReadable: 'ignored — the served artifact wins' },
            artifact: { kind: 'text', config: { text: 'Served artifact body.' } },
        });
        const el = run(fixture);
        const host = el.querySelector('inspecto-a2ui-render');
        expect(host!.textContent).toContain('Served artifact body.');
        expect(host!.textContent).not.toContain('ignored — the served artifact wins');
    });

    it('has no a11y violations with a rendered result', async () => {
        const { fixture } = create({ ...RESULT, data: { ...RESULT.data, humanReadable: 'Prose.', findings: [{ severity: 'warn', fieldPath: 'x', message: 'm' }] } });
        run(fixture);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
