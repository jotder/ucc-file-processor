import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { OnboardingStateService } from './onboarding-state.service';

/** Session-held sample cap — a preview thread, not a data upload. */
const MAX_SAMPLE_BYTES = 256 * 1024;
const RAW_PREVIEW_LINES = 8;

/**
 * The sample-as-thread panel (design §4.3): ONE captured sample follows the builder through the
 * stages — raw here, parsed once the Parsing stage tests it, cast/mapped in later phases. The
 * sample is session-held and re-capturable (upload or paste); it never becomes part of the
 * config. Capture is allowed in every lens — it changes nothing on the server.
 */
@Component({
    selector: 'app-onboarding-sample-panel',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [FormsModule, MatButtonModule, MatIconModule, MatTooltipModule],
    template: `
        <div class="flex h-full flex-col gap-3 overflow-y-auto p-4">
            <h2 class="m-0 text-sm font-semibold uppercase tracking-wider text-secondary">Sample</h2>

            @if (state.sample(); as s) {
                <div class="flex items-center gap-2 text-sm">
                    <mat-icon svgIcon="heroicons_outline:document-text" class="icon-size-4"></mat-icon>
                    <span class="min-w-0 truncate font-medium" [matTooltip]="s.name">{{ s.name }}</span>
                    <span class="text-secondary whitespace-nowrap">{{ lineCount() }} lines</span>
                </div>
                <pre
                    class="bg-default m-0 max-h-48 overflow-auto rounded p-2 text-xs leading-relaxed"
                    aria-label="Raw sample preview"
                >{{ rawPreview() }}</pre>

                <div class="text-sm">
                    <div class="font-semibold">After parsing</div>
                    @if (state.parseError()) {
                        <div class="text-secondary">Does not parse — see the Parsing stage.</div>
                    } @else if (state.parsePreview(); as p) {
                        <div class="text-secondary">
                            {{ p.columns.length }} columns · {{ p.rowCount }} rows
                            @if (p.rejectedRows > 0) { · {{ p.rejectedRows }} rejected }
                        </div>
                    } @else {
                        <div class="text-secondary">Not tested yet — run Test parse in the Parsing stage.</div>
                    }
                </div>

                @if (state.parsePreview(); as p) {
                    <div class="text-sm">
                        <div class="font-semibold">After schema</div>
                        @if (state.schemaError()) {
                            <div class="text-secondary">Does not cast — see the Schema &amp; Mapping stage.</div>
                        } @else if (state.schemaPreview(); as sp) {
                            <div class="text-secondary">
                                {{ sp.okCount }} ok
                                @if (sp.rejectedCount > 0) { · {{ sp.rejectedCount }} rejected }
                            </div>
                        } @else {
                            <div class="text-secondary">Not tested yet — run Validate types in the Schema &amp; Mapping stage.</div>
                        }
                    </div>
                }

                <div class="flex flex-wrap gap-2">
                    <button mat-stroked-button type="button" (click)="fileInput.click()">Replace</button>
                    <button mat-stroked-button type="button" (click)="clear()">Clear</button>
                </div>
            } @else {
                <p class="text-secondary m-0 text-sm">
                    Capture one representative sample — it follows you through the stages, so every
                    test shows <em>your</em> data.
                </p>
                <div class="flex flex-wrap gap-2">
                    <button mat-flat-button color="primary" type="button" (click)="fileInput.click()">
                        <mat-icon svgIcon="heroicons_outline:arrow-up-tray" class="icon-size-4"></mat-icon>
                        <span class="ml-1">Choose file</span>
                    </button>
                    <button mat-stroked-button type="button" (click)="pasting.set(!pasting())">Paste text</button>
                </div>
            }

            @if (pasting()) {
                <textarea
                    class="bg-default min-h-32 w-full rounded border p-2 font-mono text-xs"
                    [(ngModel)]="pasteText"
                    placeholder="Paste a few representative lines…"
                    aria-label="Paste sample text"
                ></textarea>
                <button
                    mat-flat-button
                    color="primary"
                    type="button"
                    class="self-start"
                    [disabled]="!pasteText.trim()"
                    (click)="usePasted()"
                >
                    Use pasted sample
                </button>
            }

            <input #fileInput type="file" class="hidden" (change)="onFile($event)" aria-hidden="true" tabindex="-1" />
        </div>
    `,
})
export class OnboardingSamplePanelComponent {
    protected readonly state = inject(OnboardingStateService);
    private toastr = inject(ToastrService);

    readonly pasting = signal(false);
    pasteText = '';

    readonly lineCount = computed(() => this.state.sample()?.text.split('\n').filter((l) => l.length).length ?? 0);
    readonly rawPreview = computed(() => {
        const text = this.state.sample()?.text ?? '';
        const lines = text.split('\n');
        const head = lines.slice(0, RAW_PREVIEW_LINES).join('\n');
        return lines.length > RAW_PREVIEW_LINES ? `${head}\n…` : head;
    });

    onFile(event: Event): void {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';
        if (!file) return;
        const truncated = file.size > MAX_SAMPLE_BYTES;
        file.slice(0, MAX_SAMPLE_BYTES)
            .text()
            .then((text) => {
                this.state.captureSample(file.name, text);
                this.pasting.set(false);
                if (truncated) this.toastr.info(`Sample truncated to the first ${MAX_SAMPLE_BYTES / 1024} KB.`);
            })
            .catch(() => this.toastr.error('Could not read the file as text.'));
    }

    usePasted(): void {
        const text = this.pasteText.slice(0, MAX_SAMPLE_BYTES);
        if (!text.trim()) return;
        this.state.captureSample('pasted sample', text);
        this.pasting.set(false);
        this.pasteText = '';
    }

    clear(): void {
        this.state.clearSample();
    }
}
