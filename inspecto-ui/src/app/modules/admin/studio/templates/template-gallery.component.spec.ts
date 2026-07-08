import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { ToastrService } from 'ngx-toastr';
import { BiTemplate, BiTemplatesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DatasetsService } from '../datasets/datasets.service';
import { TemplateGalleryComponent } from './template-gallery.component';

const TEMPLATES: BiTemplate[] = [
    {
        id: 'kpi-overview', title: 'KPI overview', description: 'A starter board.', params: ['dataset', 'prefix?'],
        components: [
            { kind: 'widget', id: 'kpi_total' }, { kind: 'widget', id: 'sum_by_dim' },
            { kind: 'dashboard', id: 'kpi_board' },
        ],
    },
];

describe('TemplateGalleryComponent', () => {
    function make(over: {
        list?: BiTemplatesService['list'];
        apply?: BiTemplatesService['apply'];
        datasets?: DatasetsService['list'];
        dialogResult?: unknown;
    }) {
        const toastr = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
        const dialog = { open: vi.fn(() => ({ afterClosed: () => of(over.dialogResult) })) };
        const router = { navigate: vi.fn() };
        TestBed.configureTestingModule({
            imports: [TemplateGalleryComponent],
            providers: [
                provideRouter([]),
                provideNoopAnimations(),
                { provide: BiTemplatesService, useValue: { list: over.list ?? (() => of(TEMPLATES)), apply: over.apply ?? (() => of({ template: 't', dataset: 'd', created: [] })) } },
                { provide: DatasetsService, useValue: { list: over.datasets ?? (() => of([{ id: 'sales_ds' }])) } },
                { provide: MatDialog, useValue: dialog },
                { provide: ToastrService, useValue: toastr },
            ],
        });
        // Router is provided via provideRouter; override navigate spy through the component after create.
        const fixture = TestBed.createComponent(TemplateGalleryComponent);
        fixture.detectChanges();
        return { fixture, toastr, dialog, router };
    }

    it('lists templates as cards with a component summary (and passes axe)', async () => {
        const { fixture } = make({});
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelectorAll('h1')).toHaveLength(1);
        expect(el.querySelectorAll('section')).toHaveLength(1);
        expect(el.textContent).toContain('KPI overview');
        expect(el.textContent).toContain('2 widgets, 1 dashboard');
        await expectNoA11yViolations(el);
    });

    it('shows the empty state when the gallery is empty', async () => {
        const { fixture } = make({ list: (() => of([])) as BiTemplatesService['list'] });
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('No templates');
    });

    it('opens the apply dialog and calls apply with the chosen dataset + prefix', () => {
        const applySpy = vi.fn(() => of({ template: 'kpi-overview', dataset: 'sales_ds', created: [{ kind: 'dashboard', id: 'q3_kpi_board' }] }));
        const { fixture, toastr, dialog } = make({
            apply: applySpy as unknown as BiTemplatesService['apply'],
            dialogResult: { dataset: 'sales_ds', prefix: 'q3' },
        });
        fixture.componentInstance.apply(TEMPLATES[0]);
        expect(dialog.open).toHaveBeenCalled();
        expect(applySpy).toHaveBeenCalledWith('kpi-overview', 'sales_ds', 'q3');
        expect(toastr.success).toHaveBeenCalled();
    });

    it('nudges to create a dataset first when none exist', () => {
        const { fixture, toastr, dialog } = make({ datasets: (() => of([])) as DatasetsService['list'] });
        fixture.componentInstance.apply(TEMPLATES[0]);
        expect(toastr.info).toHaveBeenCalled();
        expect(dialog.open).not.toHaveBeenCalled();
    });

    it('surfaces a load failure as a warning toast, not a crash', () => {
        const { toastr } = make({ list: (() => throwError(() => new Error('down'))) as BiTemplatesService['list'] });
        expect(toastr.warning).toHaveBeenCalled();
    });
});
