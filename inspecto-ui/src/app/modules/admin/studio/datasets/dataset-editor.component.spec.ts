import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Dataset } from './dataset-types';
import { DatasetsService } from './datasets.service';
import { DatasetEditorComponent } from './dataset-editor.component';

function create(save = vi.fn((d: Dataset) => of(d))) {
    TestBed.configureTestingModule({
        imports: [DatasetEditorComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: DatasetsService, useValue: { get: () => of(null), save } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    return TestBed.createComponent(DatasetEditorComponent);
}

describe('DatasetEditorComponent', () => {
    it('starts in create mode with inferred columns over the default source', () => {
        const fixture = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.editing()).toBe(false);
        expect(c.isVirtual()).toBe(true);
        expect(c.columns().length).toBeGreaterThan(0);
        // duration_s is numeric & non-id → measure
        expect(c.columns().find((x) => x.name === 'duration_s')?.role).toBe('measure');
    });

    it('switching kind to physical hides the query panel', () => {
        const fixture = create();
        fixture.detectChanges();
        fixture.componentInstance.form.controls.kind.setValue('physical');
        expect(fixture.componentInstance.isVirtual()).toBe(false);
    });

    it('saves a valid dataset and navigates back to the list', () => {
        const save = vi.fn((d: Dataset) => of(d));
        const fixture = create(save);
        fixture.detectChanges();
        const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fixture.componentInstance.form.controls.name.setValue('cdr_view');
        fixture.componentInstance.save();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ id: 'cdr_view', kind: 'virtual', sourceName: 'cdr' }));
        expect(nav).toHaveBeenCalledWith(['/studio/datasets']);
    });

    it('does not save when the name is empty', () => {
        const save = vi.fn((d: Dataset) => of(d));
        const fixture = create(save);
        fixture.detectChanges();
        fixture.componentInstance.save();
        expect(save).not.toHaveBeenCalled();
    });

    // This editor embeds the query panel + an ag-Grid preview, making it the heaviest a11y
    // fixture in the suite. axe finishes in ~1-2s in isolation, but under full-suite multi-worker
    // CPU contention it can occasionally cross vitest's 5s default and time out (never a real
    // violation — those throw immediately). Give it explicit headroom.
    it(
        'renders with no a11y violations',
        async () => {
            const fixture = create();
            fixture.detectChanges();
            await expectNoA11yViolations(fixture.nativeElement);
        },
        15_000,
    );
});
