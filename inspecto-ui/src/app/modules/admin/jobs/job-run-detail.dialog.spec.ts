import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { JobRunRow } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { JobRunDetailDialog } from './job-run-detail.dialog';

const RUN: JobRunRow = {
    runId: 'r-100',
    job: 'rollup',
    type: 'ENRICH',
    trigger: 'schedule',
    startTime: '2026-06-17 10:00:00',
    endTime: '2026-06-17 10:00:01',
    status: 'SUCCESS',
    durationMs: 1500,
    message: 'ok',
};

function create() {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [JobRunDetailDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: RUN },
            { provide: MatDialogRef, useValue: ref },
        ],
    });
    const fixture = TestBed.createComponent(JobRunDetailDialog);
    fixture.detectChanges();
    return { fixture, ref };
}

describe('JobRunDetailDialog', () => {
    it('renders the run fields with a formatted duration', () => {
        const { fixture } = create();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('rollup');
        expect(text).toContain('r-100');
        expect(text).toContain('1.5s');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
