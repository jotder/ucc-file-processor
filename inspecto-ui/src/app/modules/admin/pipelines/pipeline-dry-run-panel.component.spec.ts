import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { PipelineDryRunPanelComponent } from './pipeline-dry-run-panel.component';
import { PipelineDryRunResult, PipelinesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';

describe('PipelineDryRunPanelComponent', () => {
    let api: { dryRunAuthored: ReturnType<typeof vi.fn> };

    beforeEach(() => {
        api = {
            dryRunAuthored: vi.fn().mockReturnValue(of({ seedNode: 'src', nodes: [], sinks: [] } as PipelineDryRunResult)),
        };
        TestBed.configureTestingModule({
            imports: [PipelineDryRunPanelComponent],
            providers: [provideNoopAnimations(), { provide: PipelinesService, useValue: api }],
        });
    });

    it('parses the sample and maps the result', () => {
        const fixture = TestBed.createComponent(PipelineDryRunPanelComponent);
        fixture.componentRef.setInput('pipelineId', 'demo');
        const cmp = fixture.componentInstance;
        cmp.sampleText.setValue('[{"amt":"150"}]');
        cmp.run();
        expect(api.dryRunAuthored).toHaveBeenCalledWith('demo', [{ amt: '150' }]);
        expect(cmp.result()?.seedNode).toBe('src');
    });

    it('rejects invalid JSON without calling the API', () => {
        const fixture = TestBed.createComponent(PipelineDryRunPanelComponent);
        fixture.componentRef.setInput('pipelineId', 'demo');
        const cmp = fixture.componentInstance;
        cmp.sampleText.setValue('not json');
        cmp.run();
        expect(api.dryRunAuthored).not.toHaveBeenCalled();
        expect(cmp.error()).toBeTruthy();
    });

    it('is a11y-clean with no pipeline selected', async () => {
        const fixture = TestBed.createComponent(PipelineDryRunPanelComponent);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
