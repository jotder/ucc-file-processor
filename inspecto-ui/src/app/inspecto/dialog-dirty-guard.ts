import { MatDialogRef } from '@angular/material/dialog';
import { InspectoConfirmService } from './confirm.service';

/**
 * Protect entered form data from a silent discard (design `docs/superpower/ui-design-review.md` R2):
 * Esc / backdrop-click close immediately while the form is pristine, but once `isDirty()` reports
 * true they ask first. Call once from the dialog component's constructor:
 *
 * ```ts
 * readonly requestClose = guardDirtyClose(this.ref, () => this.schemaForm?.isDirty() ?? false, this.confirm);
 * ```
 *
 * Returns the guarded close request — bind the dialog's Cancel button to it (instead of
 * `mat-dialog-close`, which would skip the guard). The subscriptions complete with the dialog
 * (MatDialogRef finalizes its event streams on close), so no teardown is needed.
 */
export function guardDirtyClose(
    ref: MatDialogRef<unknown, unknown>,
    isDirty: () => boolean,
    confirm: InspectoConfirmService,
): () => Promise<void> {
    ref.disableClose = true;
    const tryClose = async (): Promise<void> => {
        if (
            !isDirty() ||
            (await confirm.confirmDestructive('Your unsaved changes will be lost.', {
                title: 'Discard changes?',
                confirmText: 'Discard',
                cancelText: 'Keep editing',
            }))
        ) {
            ref.close();
        }
    };
    // Optional-chained so minimal test doubles (`{ close }`) keep working; a real MatDialogRef
    // always exposes both streams.
    ref.keydownEvents?.().subscribe((e) => {
        if (e.key === 'Escape') void tryClose();
    });
    ref.backdropClick?.().subscribe(() => void tryClose());
    return tryClose;
}
