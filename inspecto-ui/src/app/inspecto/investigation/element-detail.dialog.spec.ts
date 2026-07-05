import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ElementDetailData, ElementDetailDialog } from './element-detail.dialog';

const DATA: ElementDetailData = {
    title: 'CELL-101',
    subtitle: 'entity',
    rows: [{ label: 'Label', value: 'CELL-101' }, { label: 'Degree', value: '3' }],
    branch: 'collapse',
};

function create(data = DATA) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [ElementDetailDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
        ],
    });
    const fixture = TestBed.createComponent(ElementDetailDialog);
    fixture.detectChanges();
    return { fixture, ref };
}

describe('ElementDetailDialog', () => {
    it('renders the detail rows and closes with the chosen branch / focus action', () => {
        const { fixture, ref } = create();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).toContain('CELL-101');
        Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Collapse branch'))?.click();
        expect(ref.close).toHaveBeenCalledWith('collapse');
        Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Focus'))?.click();
        expect(ref.close).toHaveBeenCalledWith('focus');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
