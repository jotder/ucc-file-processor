import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { EventRow } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { EventDetailDialog } from './event-detail.dialog';

const EVENT: EventRow = {
    eventId: 'ev-001',
    ts: 1750000000000,
    timestamp: '2026-06-15 12:26:40',
    level: 'WARN',
    type: 'batch.quarantined',
    source: 'engine',
    pipeline: 'cdr',
    correlationId: 'corr-7',
    message: 'Batch quarantined after 12 rejected rows',
    attributes: { batchId: 'b-42', rejected: '12' },
};

function create() {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [EventDetailDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: EVENT },
            { provide: MatDialogRef, useValue: ref },
        ],
    });
    const fixture = TestBed.createComponent(EventDetailDialog);
    fixture.detectChanges();
    return { fixture, ref };
}

describe('EventDetailDialog', () => {
    it('renders the attributes table and closes with a correlation drill-down', () => {
        const { fixture, ref } = create();
        const el: HTMLElement = fixture.nativeElement;
        const cells = Array.from(el.querySelectorAll('td')).map((td) => td.textContent?.trim());
        expect(cells).toContain('batchId');
        expect(cells).toContain('12');
        fixture.componentInstance.drillCorrelation();
        expect(ref.close).toHaveBeenCalledWith({ correlationId: 'corr-7' });
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
