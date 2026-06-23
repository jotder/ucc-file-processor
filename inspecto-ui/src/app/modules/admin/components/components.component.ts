import { Component, OnInit, ViewEncapsulation, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { forkJoin } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ComponentDef, ComponentsService, ComponentType, COMPONENT_TYPES } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { ComponentFormDialog, ComponentFormResult } from './component-form.dialog';

/**
 * Component registry editor (T19) — create / edit / delete the reusable grammar / schema / transform / sink
 * components that flows compose via `use:`, and dry-run each over a sample (T18) from its edit dialog. Writes
 * are write-root gated; listing works regardless. The flow-topology editor is a separate (later) feature.
 */
@Component({
    selector: 'app-components',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule, MatTooltipModule, InspectoEmptyStateComponent, InspectoAlertComponent],
    templateUrl: './components.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class ComponentsComponent implements OnInit {
    private api = inject(ComponentsService);
    private toastr = inject(ToastrService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);

    readonly types = COMPONENT_TYPES;
    byType: Record<string, ComponentDef[]> = {};
    loading = false;
    /** Flipped true once a mutate (create/update/delete) returns 503 — hides the mutate actions. */
    writesDisabled = false;

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading = true;
        forkJoin(Object.fromEntries(this.types.map((t) => [t, this.api.list(t)]))).subscribe({
            next: (res) => {
                this.byType = res as Record<string, ComponentDef[]>;
                this.loading = false;
            },
            error: () => {
                this.byType = {};
                this.loading = false;
                this.toastr.warning('Could not load components — is ControlApi running?');
            },
        });
    }

    countFor(type: ComponentType): number {
        return this.byType[type]?.length ?? 0;
    }

    summary(def: ComponentDef): string {
        const c = def.content ?? {};
        switch (def.type) {
            case 'grammar': return `delimiter ${disp(c['delimiter'], ',')}${c['has_header'] ? ', header' : ''}`;
            case 'schema': return `${fieldCount(c)} field(s)`;
            case 'transform': return String(c['type'] ?? 'transform');
            case 'sink': return `${disp(c['type'], 'sink')} → ${disp(c['store'], '(no store)')}`;
            default: return '';
        }
    }

    create(type: ComponentType): void {
        this.openForm(type);
    }

    edit(type: ComponentType, def: ComponentDef): void {
        this.openForm(type, def);
    }

    private openForm(kind: ComponentType, def?: ComponentDef): void {
        this.dialog
            .open(ComponentFormDialog, { data: { kind, def }, width: '760px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r?: ComponentFormResult) => {
                if (r?.writesDisabled) this.writesDisabled = true;
                if (r?.saved) this.load();
            });
    }

    async remove(type: ComponentType, def: ComponentDef): Promise<void> {
        if (
            !(await this.confirm.confirmDestructive(
                `Delete ${type} "${def.name}"? This removes its component definition.`,
                { title: `Delete ${type}` },
            ))
        )
            return;
        this.api.remove(type, def.name).subscribe({
            next: () => {
                this.toastr.success(`${type} "${def.name}" deleted`);
                this.load();
            },
            error: (e) => {
                if (e?.status === 503) this.writesDisabled = true;
                const msg =
                    e?.status === 503 ? 'Writes are disabled (no write root configured).'
                    : e?.status === 409 ? `"${def.name}" is referenced by a flow and can't be deleted.`
                    : apiErrorMessage(e, `Could not delete "${def.name}".`);
                this.toastr.error(msg);
            },
        });
    }
}

function disp(v: unknown, dflt: string): string {
    return v == null || v === '' ? dflt : String(v);
}
function fieldCount(c: Record<string, unknown>): number {
    const raw = c['raw'] as { fields?: unknown } | undefined;
    const src = (raw?.fields ?? c['fields'] ?? c['columns']) as unknown;
    return Array.isArray(src) ? src.length : 0;
}
