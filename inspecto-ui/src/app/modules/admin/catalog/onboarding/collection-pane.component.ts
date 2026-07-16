import { ChangeDetectionStrategy, Component, OnDestroy, ViewChild, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ConnectionTestResult, ConnectionsService, LensService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { connectionOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { ConnectionFormDialog, ConnectionFormResult } from 'app/inspecto/connections/connection-form.dialog';
import { COLLECTOR_ATTRIBUTES } from './collector-attributes';
import { KEY_SEP, clearMissingRoots, flattenBlock, nestKeys } from './onboarding-config-utils';
import { OnboardingStateService } from './onboarding-state.service';

/**
 * Collection stage — authors the Stage-1 `collector:` block (connector, Connection, discovery,
 * file-level duplicate policy). The connection picker is an autocomplete over saved profiles
 * with test + create-in-place (the shared {@link ConnectionFormDialog}); a blank connector
 * config is honest too: no `collector:` block means the local-inbox default.
 */
@Component({
    selector: 'app-onboarding-collection-pane',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
    ],
    template: `
        <div class="flex max-w-3xl flex-col gap-4">
            <p class="text-secondary m-0">
                Where this {{ state.kind() }}'s files come from. Without a connector the pipeline reads
                its local inbox folder — pick a connector and Connection to collect from elsewhere.
            </p>

            <inspecto-schema-form
                #sf
                [specs]="attributes"
                [initial]="initial"
                [optionLoaders]="optionLoaders"
                (submitted)="save()"
            />

            <!-- Connection affordances: test the picked profile, or create one in place. -->
            <div class="flex flex-wrap items-center gap-2">
                <button
                    mat-stroked-button
                    type="button"
                    [disabled]="testing() || !connectionId()"
                    (click)="testConnection()"
                >
                    @if (testing()) {
                        <mat-progress-spinner diameter="16" mode="indeterminate" class="mr-2" />
                    }
                    Test connection
                </button>
                @if (lens.canAuthorWorkbench()) {
                    <button mat-stroked-button type="button" (click)="newConnection()">
                        <mat-icon svgIcon="heroicons_outline:plus" class="icon-size-4" />
                        <span class="ml-1">New connection</span>
                    </button>
                }
            </div>
            @if (testResult(); as t) {
                <inspecto-alert [variant]="t.reachable ? 'success' : 'error'">
                    {{ t.reachable ? 'Reachable' : 'Unreachable' }} — {{ t.detail }}
                    @if (t.latencyMs != null) { ({{ t.latencyMs }} ms) }
                </inspecto-alert>
            }

            <div class="flex items-center gap-3">
                <button
                    mat-flat-button
                    color="primary"
                    [disabled]="saving() || !lens.canAuthorWorkbench()"
                    (click)="save()"
                >
                    Save collection
                </button>
                @if (!lens.canAuthorWorkbench()) {
                    <span class="text-secondary text-sm">Your lens is read-only.</span>
                }
            </div>
        </div>
    `,
})
export class OnboardingCollectionPaneComponent implements OnDestroy {
    protected readonly state = inject(OnboardingStateService);
    protected readonly lens = inject(LensService);
    private connections = inject(ConnectionsService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);

    @ViewChild('sf') schemaForm!: InspectoSchemaFormComponent;

    readonly attributes = COLLECTOR_ATTRIBUTES;
    readonly optionLoaders = { connection: connectionOptionLoader() };
    readonly initial = flattenBlock(
        ((this.state.config() ?? {})['collector'] as Record<string, unknown> | undefined) ?? undefined,
    );

    readonly saving = signal(false);
    readonly testing = signal(false);
    readonly testResult = signal<ConnectionTestResult | null>(null);

    private readonly dirtyCheck = (): boolean => this.schemaForm?.isDirty() ?? false;

    constructor() {
        this.state.registerDirtyCheck(this.dirtyCheck);
    }

    ngOnDestroy(): void {
        this.state.unregisterDirtyCheck(this.dirtyCheck);
    }

    connectionId(): string {
        return String((this.schemaForm?.value() ?? {})['connection'] ?? '').trim();
    }

    testConnection(): void {
        const id = this.connectionId();
        if (!id) return;
        this.testing.set(true);
        this.testResult.set(null);
        this.connections.test(id).subscribe({
            next: (r) => {
                this.testing.set(false);
                this.testResult.set(r);
            },
            error: (e) => {
                this.testing.set(false);
                this.toastr.warning(apiErrorMessage(e, `Could not test "${id}" — is it saved?`));
            },
        });
    }

    async newConnection(): Promise<void> {
        if (!this.lens.canAuthorWorkbench()) return;
        const existingIds = (await firstValueFrom(this.connections.list()).catch(() => [])).map((c) => c.id);
        this.dialog
            .open<ConnectionFormDialog, unknown, ConnectionFormResult>(ConnectionFormDialog, {
                data: { existingIds },
                width: '720px',
                maxWidth: '95vw',
            })
            .afterClosed()
            .subscribe((res) => {
                if (res?.saved) {
                    this.schemaForm.form.get('connection')?.setValue(res.saved.id);
                    this.schemaForm.form.get('connection')?.markAsDirty();
                    this.testResult.set(null);
                }
            });
    }

    save(): void {
        if (!this.lens.canAuthorWorkbench()) return;
        if (!this.schemaForm.validate()) return;
        // Cleared fields delete their key; keys this form never owned survive the deep merge.
        const roots = new Set(this.attributes.map((a) => a.key.split(KEY_SEP)[0]));
        const collector = clearMissingRoots(nestKeys(this.schemaForm.value()), roots);
        this.saving.set(true);
        this.state.saveBlock({ collector }).subscribe({
            next: () => {
                this.saving.set(false);
                this.schemaForm.form.markAsPristine();
                this.toastr.success('Collection saved');
            },
            error: () => this.saving.set(false),
        });
    }
}
