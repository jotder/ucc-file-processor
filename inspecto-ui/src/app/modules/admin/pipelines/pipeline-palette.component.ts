import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { categoryColor, categoryLabel, NodeTypeGroup, paletteHeroIcon } from './pipeline-graph';

/**
 * The processor palette — a floating, self-toggling panel over the pipeline canvas (Wave-1 decomposition
 * of `PipelineEditorComponent`, see `docs/superpower/reviews/pipeline-editor.md`). Presentational: it owns
 * only its own open/closed state; the host supplies the node-type catalog and reacts to `pick` (click-to-add
 * at canvas centre). Drag-to-position needs no output — it writes the type directly onto the native
 * `dataTransfer` (`text/flow-node-type`), which `PipelineEditorGraphComponent`'s drop handler reads.
 */
@Component({
    selector: 'app-pipeline-palette',
    standalone: true,
    imports: [MatIconModule, MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <!-- colour-legend trigger — top-right of canvas; opens the floating palette -->
        <button
            type="button"
            class="absolute right-2 top-2 z-10 flex items-center gap-1 rounded border px-2 py-1 text-xs"
            style="background: var(--gamma-bg-card); border-color: var(--gamma-border)"
            (click)="open.set(!open())"
            [matTooltip]="open() ? 'Hide palette' : 'Show processor palette'"
            [attr.aria-label]="open() ? 'Hide palette' : 'Show processor palette'"
            [attr.aria-expanded]="open()"
        >
            @for (g of groups; track g.category) {
                <span class="h-2.5 w-2.5 rounded-full" [style.background]="categoryColor(g.category)"></span>
            }
        </button>

        @if (open()) {
            <!-- pointer-events-none on the wrapper so drags/drops pass through to the canvas below;
                 only the interactive content div restores pointer events -->
            <div class="pointer-events-none absolute right-2 top-10 z-20 max-h-80 w-56">
                <div
                    class="pointer-events-auto overflow-y-auto rounded border shadow-lg"
                    style="background: var(--gamma-bg-card); border-color: var(--gamma-border)"
                >
                    <div class="p-2">
                        @for (group of groups; track group.category) {
                            <div class="mb-0.5 mt-2 flex items-center gap-1 text-xs font-semibold uppercase opacity-60">
                                <span class="h-2 w-2 rounded-full" [style.background]="categoryColor(group.category)"></span>
                                {{ categoryLabel(group.category) }}
                            </div>
                            @for (t of group.types; track t.type) {
                                <button
                                    type="button"
                                    class="flex w-full cursor-grab items-center gap-1.5 rounded px-2 py-1 text-xs hover:bg-black/5 dark:hover:bg-white/10"
                                    draggable="true"
                                    [matTooltip]="t.description"
                                    [attr.aria-label]="'Add ' + t.label"
                                    (click)="pick.emit(t.type)"
                                    (dragstart)="$event.dataTransfer?.setData('text/flow-node-type', t.type)"
                                >
                                    <mat-icon class="icon-size-4 shrink-0" [svgIcon]="paletteHeroIcon(group.category)"></mat-icon>
                                    {{ t.label }}
                                </button>
                            }
                        }
                    </div>
                    <div class="border-t px-3 py-1.5 text-xs opacity-50" style="border-color: var(--gamma-border)">
                        click to add at centre · drag to position
                    </div>
                </div>
            </div>
        }
    `,
})
export class PipelinePaletteComponent {
    @Input({ required: true }) groups: NodeTypeGroup[] = [];
    /** A palette entry was clicked — add it at the canvas centre (the no-mouse path). */
    @Output() pick = new EventEmitter<string>();

    readonly open = signal(false);
    readonly categoryColor = categoryColor;
    readonly categoryLabel = categoryLabel;
    readonly paletteHeroIcon = paletteHeroIcon;
}
