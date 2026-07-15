import { Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectsService, OperationalObject, Tag } from 'app/inspecto/api';
import { objectTags } from './mail-model';

/** What the shell applies after the dialog closes: tags to add to / remove from every target. */
export interface TagChange {
    add: string[];
    remove: string[];
}

type TagState = 'all' | 'some' | 'none';

/**
 * Manual tagging (Gmail label-menu semantics) — tri-state checkboxes over the tag registry:
 * checked = every selected object has the tag, indeterminate = some do. Only rows the user
 * touches are applied (checked → add to all, unchecked → remove from all). New tags can be
 * created inline; closes with a {@link TagChange} or null.
 */
@Component({
    selector: 'app-tag-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTooltipModule,
    ],
    template: `
        <h2 mat-dialog-title>Tag {{ data.targets.length }} item{{ data.targets.length === 1 ? '' : 's' }}</h2>
        <mat-dialog-content class="flex flex-col gap-1 pt-2">
            @for (t of tags(); track t.name) {
                <mat-checkbox
                    class="block"
                    [checked]="stateOf(t.name) === 'all'"
                    [indeterminate]="stateOf(t.name) === 'some'"
                    (change)="toggle(t.name, $event.checked)"
                >
                    {{ t.name }}
                </mat-checkbox>
            } @empty {
                <p class="text-secondary text-sm">No tags yet — create the first one below.</p>
            }
            <div class="mt-2 flex items-center gap-2">
                <mat-form-field class="flex-auto" subscriptSizing="dynamic">
                    <mat-label>New tag</mat-label>
                    <input
                        matInput
                        [formControl]="newTag"
                        (keyup.enter)="createTag()"
                        placeholder="e.g. billing"
                        aria-label="New tag name"
                        cdkFocusInitial
                    />
                </mat-form-field>
                <button
                    mat-icon-button
                    type="button"
                    [disabled]="!newTag.value?.trim() || creating()"
                    (click)="createTag()"
                    matTooltip="Create tag"
                    aria-label="Create tag"
                >
                    <mat-icon class="icon-size-5" svgIcon="heroicons_outline:plus"></mat-icon>
                </button>
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="null">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="!dirty()" (click)="apply()">Apply</button>
        </mat-dialog-actions>
    `,
})
export class TagDialog {
    private api = inject(ObjectsService);
    private ref = inject(MatDialogRef<TagDialog>);
    private toastr = inject(ToastrService);
    readonly data = inject<{ targets: OperationalObject[]; registry: Tag[] }>(MAT_DIALOG_DATA);

    readonly tags = signal<Tag[]>([...this.data.registry]);
    readonly creating = signal(false);
    readonly newTag = new FormControl('');

    /** Initial tri-state per tag, from the selection; `touched` records the user's decisions. */
    private readonly initial = new Map<string, TagState>();
    private readonly touched = new Map<string, boolean>();
    readonly dirty = signal(false);

    constructor() {
        const names = new Set([
            ...this.data.registry.map((t) => t.name),
            ...this.data.targets.flatMap(objectTags),
        ]);
        // Tags in use but missing from the registry still show up (they exist on the data).
        this.tags.set([...names].sort().map((name) => this.data.registry.find((t) => t.name === name) ?? { name, createdAt: 0 }));
        for (const name of names) {
            const on = this.data.targets.filter((o) => objectTags(o).includes(name)).length;
            this.initial.set(name, on === 0 ? 'none' : on === this.data.targets.length ? 'all' : 'some');
        }
    }

    stateOf(name: string): TagState {
        const t = this.touched.get(name);
        if (t !== undefined) return t ? 'all' : 'none';
        return this.initial.get(name) ?? 'none';
    }

    toggle(name: string, checked: boolean): void {
        this.touched.set(name, checked);
        this.dirty.set(true);
    }

    createTag(): void {
        const name = (this.newTag.value ?? '').trim();
        if (!name || this.creating()) return;
        this.creating.set(true);
        this.api.createTag(name).subscribe({
            next: (t) => {
                this.creating.set(false);
                this.newTag.setValue('');
                this.tags.update((all) => [...all.filter((x) => x.name !== t.name), t].sort((a, b) => a.name.localeCompare(b.name)));
                this.initial.set(t.name, 'none');
                this.toggle(t.name, true); // creating a tag here means "apply it"
            },
            error: (e) => {
                this.creating.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not create tag'));
            },
        });
    }

    apply(): void {
        const change: TagChange = { add: [], remove: [] };
        for (const [name, checked] of this.touched) {
            if (checked && this.initial.get(name) !== 'all') change.add.push(name);
            if (!checked && this.initial.get(name) !== 'none') change.remove.push(name);
        }
        this.ref.close(change);
    }
}
