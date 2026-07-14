import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ICellRendererAngularComp } from 'ag-grid-angular';
import { ICellRendererParams } from 'ag-grid-community';
import { AccessGrant } from 'app/inspecto/api';
import { FlatTreeRow } from 'app/inspecto/tree-table/tree-types';

/** What one matrix cell shows for (node × lens) — computed by the host from the working grants. */
export interface AccessCellState {
    effective: AccessGrant;
    /** null = inheriting (no explicit grant on this node). */
    explicit: AccessGrant | null;
    /** Label of the ancestor an inherited value comes from (null = the allow default). */
    sourceLabel: string | null;
    editable: boolean;
}

type GrantCellParams = ICellRendererParams<FlatTreeRow> & {
    subject?: string;
    subjectLabel?: string;
    state?: (nodeId: string, subject: string) => AccessCellState;
    cycle?: (nodeId: string, subject: string) => void;
};

/**
 * One Access-matrix cell: a button cycling **Inherit → Hidden → Shown → Inherit** for (node × lens).
 * Explicit grants render solid; an inherited value renders faded with its origin in the tooltip, so
 * any row reads at a glance — what happens, and why — without understanding inheritance first
 * (design `lens-access-config-design.md` §6). Angular component renderer, mirroring
 * {@code TreeGroupCell} (the only interactive-cell pattern on Community ag-Grid).
 */
@Component({
    selector: 'inspecto-access-grant-cell',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <button
            mat-button
            type="button"
            class="!h-7 !min-w-0 !px-2"
            [disabled]="!state.editable"
            (click)="onCycle($event)"
            [matTooltip]="tooltip"
            [attr.aria-label]="aria">
            <span class="flex items-center gap-1.5" [class.opacity-40]="!state.explicit">
                <mat-icon
                    class="icon-size-4"
                    [svgIcon]="shown ? 'heroicons_outline:eye' : 'heroicons_outline:eye-slash'"></mat-icon>
                <span class="text-sm font-normal">{{ shown ? 'Shown' : 'Hidden' }}</span>
            </span>
        </button>
    `,
})
export class AccessGrantCell implements ICellRendererAngularComp {
    state: AccessCellState = { effective: 'allow', explicit: null, sourceLabel: null, editable: false };
    tooltip = '';
    aria = '';
    private nodeId = '';
    private subject = '';
    private stateFn?: GrantCellParams['state'];
    private cycleFn?: GrantCellParams['cycle'];
    private label = '';
    private subjectLabel = '';

    get shown(): boolean {
        return this.state.effective === 'allow';
    }

    agInit(params: GrantCellParams): void {
        this.nodeId = params.data?.__id ?? '';
        this.label = params.data?.__label ?? this.nodeId;
        this.subject = params.subject ?? '';
        this.subjectLabel = params.subjectLabel ?? this.subject;
        this.stateFn = params.state;
        this.cycleFn = params.cycle;
        this.read();
    }

    /** Recreate rather than in-place refresh (matches TreeGroupCell) — the host re-flattens on edits. */
    refresh(): boolean {
        return false;
    }

    onCycle(e: Event): void {
        e.stopPropagation();
        if (!this.state.editable) return;
        this.cycleFn?.(this.nodeId, this.subject);
        this.read();   // repaint self immediately; dependents repaint via the host's node rebuild
    }

    private read(): void {
        if (this.stateFn) this.state = this.stateFn(this.nodeId, this.subject);
        const word = this.shown ? 'Shown' : 'Hidden';
        const origin = this.state.explicit
            ? 'set here'
            : this.state.sourceLabel
              ? `inherited from ${this.state.sourceLabel}`
              : 'default';
        this.tooltip = `${word} (${origin})${this.state.editable ? ' — click to change' : ''}`;
        this.aria = `${this.label} for ${this.subjectLabel}: ${word}, ${origin}.`
            + (this.state.editable ? ' Activate to change.' : '');
    }
}
