import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { PipelineDryRunResult, PipelinesService, apiErrorMessage } from 'app/inspecto/api';

/**
 * Dry-run panel for the pipeline editor: runs a bounded JSON sample through the transform→sink
 * subgraph (no write) and renders the per-node/per-sink row counts. Extracted out of
 * {@link PipelineEditorComponent} (BACKLOG §4) — self-contained state (sample text, result, error),
 * the host only owns the open/closed toggle and which pipeline id is selected.
 */
@Component({
    selector: 'app-pipeline-dry-run-panel',
    standalone: true,
    imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
    templateUrl: './pipeline-dry-run-panel.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PipelineDryRunPanelComponent {
    private api = inject(PipelinesService);
    private fb = inject(FormBuilder);

    readonly pipelineId = input<string | null>(null);

    readonly sampleText = this.fb.control('[\n  {}\n]', { nonNullable: true });
    readonly result = signal<PipelineDryRunResult | null>(null);
    readonly error = signal<string | null>(null);

    run(): void {
        const id = this.pipelineId();
        if (!id) return;
        let rows: Record<string, unknown>[];
        try {
            const parsed = JSON.parse(this.sampleText.value);
            rows = Array.isArray(parsed) ? parsed : [parsed];
        } catch {
            this.error.set('Sample must be valid JSON (an array of row objects)');
            this.result.set(null);
            return;
        }
        this.error.set(null);
        this.api.dryRunAuthored(id, rows).subscribe({
            next: (r) => this.result.set(r),
            error: (err) => {
                this.result.set(null);
                this.error.set(apiErrorMessage(err, 'Dry-run failed'));
            },
        });
    }
}
