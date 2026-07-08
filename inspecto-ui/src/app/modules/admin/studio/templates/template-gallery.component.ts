import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { ToastrService } from 'ngx-toastr';
import { BiTemplate, BiTemplatesService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { DatasetsService } from '../datasets/datasets.service';
import { ApplyTemplateDialog, ApplyTemplateResult } from './apply-template.dialog';

/**
 * The curated template gallery (BI-8): browse starter widget/dashboard sets and apply one to a Dataset.
 * Apply opens {@link ApplyTemplateDialog} (dataset picker + optional prefix), calls the backend, and on
 * success routes to the created dashboard in the Builder. Offline the list shows but apply answers 501 —
 * surfaced as a clear "applies on the backend" toast, no partial state.
 */
@Component({
    selector: 'app-template-gallery',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoEmptyStateComponent, InspectoSkeletonComponent],
    templateUrl: './template-gallery.component.html',
})
export class TemplateGalleryComponent implements OnInit {
    private api = inject(BiTemplatesService);
    private datasets = inject(DatasetsService);
    private dialog = inject(MatDialog);
    private router = inject(Router);
    private toastr = inject(ToastrService);

    readonly loading = signal(true);
    readonly templates = signal<BiTemplate[]>([]);

    ngOnInit(): void {
        this.api.list().subscribe({
            next: (t) => { this.templates.set(t); this.loading.set(false); },
            error: (e) => {
                this.loading.set(false);
                this.toastr.warning(apiErrorMessage(e, 'Could not load templates — is ControlApi running?'));
            },
        });
    }

    /** A short "2 widgets, 1 dashboard" summary of what a template writes, for the card. */
    componentSummary(t: BiTemplate): string {
        const counts = new Map<string, number>();
        for (const c of t.components) counts.set(c.kind, (counts.get(c.kind) ?? 0) + 1);
        return [...counts.entries()].map(([kind, n]) => `${n} ${kind}${n === 1 ? '' : 's'}`).join(', ');
    }

    apply(template: BiTemplate): void {
        this.datasets.list().subscribe({
            next: (datasets) => {
                if (datasets.length === 0) {
                    this.toastr.info('Create a Dataset first — templates bind their widgets to one.');
                    return;
                }
                this.dialog
                    .open(ApplyTemplateDialog, { data: { template, datasetIds: datasets.map((d) => d.id) }, width: '28rem' })
                    .afterClosed()
                    .subscribe((res: ApplyTemplateResult | undefined) => {
                        if (res) this.runApply(template, res);
                    });
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not load datasets.')),
        });
    }

    private runApply(template: BiTemplate, res: ApplyTemplateResult): void {
        this.api.apply(template.id, res.dataset, res.prefix).subscribe({
            next: (result) => {
                this.toastr.success(`Applied “${template.title}” — ${result.created.length} component(s) created.`);
                const board = result.created.find((c) => c.kind === 'dashboard');
                if (board) this.router.navigate(['/studio/dashboards', board.id]);
            },
            error: (e) => this.toastr.error(apiErrorMessage(e,
                'Could not apply the template. Templates apply on the backend — is ControlApi running?')),
        });
    }
}
