import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ReprocessDialog } from './reprocess.dialog';

function create() {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [ReprocessDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { pipeline: 'orders' } },
            { provide: MatDialogRef, useValue: ref },
        ],
    });
    const fixture = TestBed.createComponent(ReprocessDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('ReprocessDialog', () => {
    it('blocks submit on a blank batch id, then closes with the trimmed value', () => {
        const { c, ref } = create();
        c.batchId.setValue('   ');
        c.submit();
        expect(ref.close).not.toHaveBeenCalled();

        c.batchId.setValue('  batch-42 ');
        c.submit();
        expect(ref.close).toHaveBeenCalledWith('batch-42');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
