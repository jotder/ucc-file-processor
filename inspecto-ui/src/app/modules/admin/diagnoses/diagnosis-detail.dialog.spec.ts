import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { AssistService, Diagnosis } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DiagnosisDetailDialog } from './diagnosis-detail.dialog';

const DIAGNOSIS: Diagnosis = {
    batchId: 'b-42',
    pipeline: 'cdr',
    severity: 'CRITICAL',
    rootCause: 'Header row missing in 3 of 5 files',
    suggestedAlertRuleToon: 'alert:\n  when: header.missing',
    heuristicOnly: false,
    epochMillis: 1750000000000,
    citations: [{ source: 'runbook', ref: 'ingest#headers' }],
};

function create() {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [DiagnosisDetailDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: DIAGNOSIS },
            { provide: MatDialogRef, useValue: ref },
            { provide: AssistService, useValue: { run: vi.fn(() => of({})) } },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
        ],
    });
    const fixture = TestBed.createComponent(DiagnosisDetailDialog);
    fixture.detectChanges();
    return { fixture, ref };
}

describe('DiagnosisDetailDialog', () => {
    it('renders the root cause, citations and suggested alert rule', () => {
        const { fixture } = create();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Header row missing in 3 of 5 files');
        expect(text).toContain('runbook: ingest#headers');
        expect(text).toContain('Suggested alert rule (.toon)');
        expect(fixture.componentInstance.alertText).toContain('pipeline cdr, batch b-42');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
