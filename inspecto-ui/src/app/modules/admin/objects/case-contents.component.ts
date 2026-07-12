import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectGraphNode, ObjectsService, OperationalObject } from 'app/inspecto/api';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { currentOperator, normalizeIncidentStatus } from './mail-model';
import { AddMemberDialog } from './add-member.dialog';
import { SplitCaseDialog } from './split-case.dialog';

/** Live member roll-up the panel's soft close-gate reads: total members and how many are still open. */
export interface MemberRollup {
    total: number;
    open: number;
}

/**
 * **Contents** of a Case (C1, GLOSSARY §9): the member Incidents the case CONTAINS — listed with
 * status badges, add/remove, a roll-up line, and the **Split…** entry point (C2). Membership is the
 * CONTAINS edge set (loaded via the depth-1 correlation graph); mutations go through
 * `POST/DELETE /objects/{id}/links` and `POST /objects/{id}/split`.
 */
@Component({
    selector: 'app-case-contents',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule, StatusBadgeComponent],
    template: `
        <section aria-label="Case contents">
            <div class="mb-2 flex items-center gap-1">
                <h3 class="flex-auto text-sm font-bold uppercase tracking-wider opacity-60">Contents</h3>
                <button
                    mat-icon-button
                    type="button"
                    (click)="addMember()"
                    matTooltip="Add a member incident"
                    aria-label="Add a member incident"
                >
                    <mat-icon class="icon-size-5" svgIcon="heroicons_outline:plus"></mat-icon>
                </button>
                <button
                    mat-stroked-button
                    type="button"
                    [disabled]="!members().length"
                    (click)="split()"
                    matTooltip="Carve members out into a new case"
                >
                    Split…
                </button>
            </div>

            <p class="text-secondary mb-2 text-sm" role="status">
                {{ members().length }} member{{ members().length === 1 ? '' : 's' }}
                @if (openCount() > 0) {
                    · {{ openCount() }} open
                }
            </p>

            @for (n of members(); track n.id) {
                <div class="mb-1 flex items-center gap-2 rounded border px-2 py-1.5" style="border-color: var(--gamma-border)">
                    <inspecto-status-badge [value]="displayStatusOf(n)" />
                    <span class="min-w-0 flex-auto truncate text-sm" [matTooltip]="n.title">{{ n.title }}</span>
                    <span class="text-secondary shrink-0 text-xs">{{ n.id }}</span>
                    <button
                        mat-icon-button
                        type="button"
                        (click)="removeMember(n)"
                        matTooltip="Remove from this case"
                        [attr.aria-label]="'Remove member ' + n.id"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:x-mark"></mat-icon>
                    </button>
                </div>
            } @empty {
                <p class="text-secondary text-sm">No member incidents yet — add the incidents this case groups.</p>
            }
        </section>
    `,
})
export class CaseContentsComponent {
    private api = inject(ObjectsService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);

    readonly object = input.required<OperationalObject>();

    /** The case's membership changed on the server — the shell/panel should reload. */
    readonly changed = output<void>();
    /** Emitted after every (re)load with the roll-up the panel's soft close-gate reads. */
    readonly membersChange = output<MemberRollup>();

    readonly members = signal<ObjectGraphNode[]>([]);
    readonly openCount = computed(
        () => this.members().filter((n) => !['RESOLVED', 'ARCHIVED', 'CLOSED'].includes(this.displayStatusOf(n))).length,
    );

    constructor() {
        effect(() => this.load(this.object().id));
    }

    displayStatusOf(n: ObjectGraphNode): string {
        return n.objectType === 'INCIDENT' ? normalizeIncidentStatus(n.status) : (n.status ?? '').toUpperCase();
    }

    load(caseId: string): void {
        this.api.graph(caseId, 1).subscribe({
            next: (g) => {
                const memberIds = new Set(
                    g.edges
                        .filter((e) => e.from === caseId && e.relationship === 'CONTAINS')
                        .map((e) => e.to),
                );
                this.members.set(g.nodes.filter((n) => memberIds.has(n.id)));
                this.membersChange.emit({ total: memberIds.size, open: this.openCount() });
            },
            error: () => {
                this.members.set([]);
                this.membersChange.emit({ total: 0, open: 0 });
            },
        });
    }

    addMember(): void {
        this.dialog
            .open(AddMemberDialog, {
                width: '480px',
                data: { caseId: this.object().id, exclude: this.members().map((n) => n.id) },
            })
            .afterClosed()
            .subscribe((added?: boolean) => {
                if (added) {
                    this.load(this.object().id);
                    this.changed.emit();
                }
            });
    }

    removeMember(n: ObjectGraphNode): void {
        this.api.unlink(this.object().id, n.id, 'CONTAINS', currentOperator()).subscribe({
            next: () => {
                this.toastr.success(`${n.id} removed from this case`);
                this.load(this.object().id);
                this.changed.emit();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Remove failed')),
        });
    }

    split(): void {
        this.dialog
            .open(SplitCaseDialog, {
                width: '520px',
                data: { caseObj: this.object(), members: this.members() },
            })
            .afterClosed()
            .subscribe((didSplit?: boolean) => {
                if (didSplit) {
                    this.load(this.object().id);
                    this.changed.emit();
                }
            });
    }
}
