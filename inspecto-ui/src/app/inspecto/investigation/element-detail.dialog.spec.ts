import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ElementDetailData, ElementDetailDialog } from './element-detail.dialog';
import { PivotService } from './pivot.service';

const DATA: ElementDetailData = {
    title: 'CELL-101',
    subtitle: 'entity',
    rows: [{ label: 'Label', value: 'CELL-101' }, { label: 'Degree', value: '3' }],
    branch: 'collapse',
};

function create(data = DATA) {
    const ref = { close: vi.fn() };
    const pivotService = { pivotTo: vi.fn() };
    TestBed.configureTestingModule({
        imports: [ElementDetailDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
            { provide: PivotService, useValue: pivotService },
        ],
    });
    const fixture = TestBed.createComponent(ElementDetailDialog);
    fixture.detectChanges();
    return { fixture, ref, pivotService };
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

    it('offers no "Open" action when objectRef is absent', () => {
        const { fixture } = create();
        const el = fixture.nativeElement as HTMLElement;
        expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Open'))).toBe(false);
    });

    it('offers "Open case" when objectRef is set, and closes with that choice', () => {
        const { fixture, ref } = create({ ...DATA, objectRef: { id: 'case-1', type: 'CASE' } });
        const el = fixture.nativeElement as HTMLElement;
        const btn = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Open case'));
        expect(btn).toBeTruthy();
        btn?.click();
        expect(ref.close).toHaveBeenCalledWith('open-record');
    });

    it('offers no pivot action when pivotViews is absent, even with an objectRef', () => {
        const { fixture } = create({ ...DATA, objectRef: { id: 'case-1', type: 'CASE' } });
        const el = fixture.nativeElement as HTMLElement;
        expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('View'))).toBe(false);
    });

    it('offers "View on map" when pivotViews includes map, and hands off to PivotService then closes', () => {
        const { fixture, ref, pivotService } = create({
            ...DATA,
            objectRef: { id: 'case-1', type: 'CASE' },
            pivotViews: ['map'],
        });
        const el = fixture.nativeElement as HTMLElement;
        const btn = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('View on map'));
        expect(btn).toBeTruthy();
        btn?.click();
        expect(pivotService.pivotTo).toHaveBeenCalledWith('map', { id: 'case-1', type: 'CASE' });
        expect(ref.close).toHaveBeenCalledWith(undefined);
    });
});
