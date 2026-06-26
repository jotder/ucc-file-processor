import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { ToastrService } from 'ngx-toastr';
import { FlowNodeType, FlowsService, IconMap, IconMapService, apiErrorMessage } from 'app/inspecto/api';
import { GLYPH_LIBRARY, ICON_COLOR_SWATCHES, iconDataUri } from 'app/modules/admin/catalog/catalog-graph';
import { NodeTypeGroup, categoryLabel, groupByCategory, resolveNodeIcon } from 'app/modules/admin/flows/flow-graph';

/**
 * Processor-icon settings — map each processor **type** (and per-**sub-type**) to a glyph + colour. A
 * sub-type row overrides its category default; an unset ("inherit") row uses the category rule, then the
 * built-in per-kind glyph. Persists the whole map via {@link IconMapService} (mock-backed). The pipeline
 * graphs read this map to render their nodes (see flow-graph `resolveNodeIcon`).
 */
@Component({
    selector: 'app-icon-settings',
    standalone: true,
    imports: [NgTemplateOutlet, MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule],
    templateUrl: './icon-settings.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IconSettingsComponent implements OnInit {
    private api = inject(FlowsService);
    private iconMapApi = inject(IconMapService);
    private toast = inject(ToastrService);

    readonly glyphNames = Object.keys(GLYPH_LIBRARY);
    readonly swatches = ICON_COLOR_SWATCHES;
    readonly categoryLabel = categoryLabel;

    readonly types = signal<FlowNodeType[]>([]);
    readonly draft = signal<IconMap>({});
    readonly loading = signal(true);
    readonly saving = signal(false);

    readonly groups = computed<NodeTypeGroup[]>(() => groupByCategory(this.types()));

    ngOnInit(): void {
        this.api.nodeTypes().subscribe({
            next: (ts) => this.types.set(ts),
            error: () => this.types.set([]),
        });
        this.reset();
    }

    /** (Re)load the saved map from the server, discarding unsaved edits. */
    reset(): void {
        this.loading.set(true);
        this.iconMapApi.get().subscribe({
            next: (m) => {
                this.draft.set({ ...m });
                this.loading.set(false);
            },
            error: () => {
                this.draft.set({});
                this.loading.set(false);
            },
        });
    }

    save(): void {
        this.saving.set(true);
        this.iconMapApi.save(this.draft()).subscribe({
            next: (m) => {
                this.draft.set({ ...m });
                this.saving.set(false);
                this.toast.success('Processor icons saved');
            },
            error: (e) => {
                this.saving.set(false);
                this.toast.error(apiErrorMessage(e, 'Could not save icons'));
            },
        });
    }

    // ── per-row state (key = category for the default row, or the type string for a sub-type row) ──

    hasRule(key: string): boolean {
        return !!this.draft()[key];
    }

    selectedGlyph(key: string): string {
        return this.draft()[key]?.glyph ?? '';
    }

    selectedColor(key: string): string | null {
        return this.draft()[key]?.color ?? null;
    }

    /** The icon that will actually render for this row (own rule → category rule → built-in). */
    previewSrc(type: string | undefined, category: string): string {
        return resolveNodeIcon(type, category, this.draft()).iconSrc;
    }

    /** The effective colour, used to tint the glyph-option previews so they read in context. */
    effColor(type: string | undefined, category: string): string {
        return resolveNodeIcon(type, category, this.draft()).color;
    }

    glyphOptionSrc(glyph: string, color: string): string {
        return iconDataUri(GLYPH_LIBRARY[glyph], color);
    }

    /** Set (or clear, when glyph is '') a row's icon; a new rule seeds its colour from the inherited value. */
    setGlyph(key: string, type: string | undefined, category: string, glyph: string): void {
        const next = { ...this.draft() };
        if (!glyph) {
            delete next[key];
        } else {
            const color = next[key]?.color ?? resolveNodeIcon(type, category, this.draft()).color;
            next[key] = { glyph, color };
        }
        this.draft.set(next);
    }

    /** Recolour a row (only meaningful once it has its own glyph rule). */
    setColor(key: string, color: string): void {
        const cur = this.draft()[key];
        if (!cur) return;
        this.draft.set({ ...this.draft(), [key]: { ...cur, color } });
    }
}
