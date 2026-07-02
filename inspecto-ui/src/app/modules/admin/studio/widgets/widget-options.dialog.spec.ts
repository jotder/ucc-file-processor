import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { WidgetOptions } from './widget-types';
import { WidgetOptionsData, WidgetOptionsDialog } from './widget-options.dialog';

function create(data: WidgetOptionsData = {}) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [WidgetOptionsDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
        ],
    });
    const fixture = TestBed.createComponent(WidgetOptionsDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('WidgetOptionsDialog', () => {
    it('re-nests the flat form values into axis/legend on save', () => {
        const data: WidgetOptions = {
            title: 'My chart',
            legend: { show: false, position: 'bottom' },
            sort: 'desc',
            limit: 5,
            stacked: true,
            axis: { xTitle: 'X' },
        };
        const { c, ref } = create(data);
        c.save();
        expect(ref.close).toHaveBeenCalledTimes(1);
        const saved = ref.close.mock.calls[0][0] as WidgetOptions;
        expect(saved.title).toBe('My chart');
        expect(saved.legend).toEqual({ show: false, position: 'bottom' });
        expect(saved.axis).toEqual({ xTitle: 'X', yTitle: undefined });
        expect(saved.sort).toBe('desc');
        expect(saved.limit).toBe(5);
        expect(saved.stacked).toBe(true);
    });

    it('applies sane defaults when opened with empty options', () => {
        const { c, ref } = create({});
        c.save();
        const saved = ref.close.mock.calls[0][0] as WidgetOptions;
        expect(saved.title).toBeUndefined();
        expect(saved.legend).toEqual({ show: true, position: 'top' });
        expect(saved.axis).toBeUndefined();
        expect(saved.sort).toBeUndefined();
        expect(saved.stacked).toBe(false);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create({});
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
