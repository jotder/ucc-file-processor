import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';

/** One documented keyboard shortcut. */
interface Shortcut {
    keys: string[];
    description: string;
}

const SHORTCUTS: Shortcut[] = [
    { keys: ['Ctrl', 'K'], description: 'Open the command palette (jump to a page or run an action)' },
    { keys: ['?'], description: 'Show this keyboard-shortcuts help' },
    { keys: ['Esc'], description: 'Close the palette, a dialog, or this help' },
    { keys: ['↑', '↓'], description: 'Move between palette results' },
    { keys: ['Enter'], description: 'Open the selected result, or submit the focused form' },
    { keys: ['/'], description: "Search the page's table (opens and focuses its quick filter)" },
    { keys: ['J', 'K'], description: 'Move down / up a list that supports keyboard nav (e.g. incidents)' },
    { keys: ['Enter'], description: 'Open the focused list row' },
    { keys: ['X'], description: 'Select / deselect the focused list row' },
];

/**
 * Keyboard-shortcuts cheat sheet (`docs/superpower/ui-design-review.md` R3). Opened by `?` from the
 * shell; a plain reference dialog, no state. `Ctrl` renders as `Cmd` is left to the OS — the label is
 * kept generic so it reads correctly on both.
 */
@Component({
    selector: 'inspecto-shortcuts-help-dialog',
    standalone: true,
    imports: [MatButtonModule, MatDialogModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Keyboard shortcuts</h2>
        <mat-dialog-content>
            <dl class="m-0 flex flex-col gap-3">
                @for (s of shortcuts; track s.description) {
                    <div class="flex items-center justify-between gap-6">
                        <dd class="text-secondary m-0 text-sm">{{ s.description }}</dd>
                        <dt class="flex shrink-0 items-center gap-1">
                            @for (k of s.keys; track k) {
                                <kbd
                                    class="rounded border px-2 py-0.5 font-mono text-xs"
                                    style="border-color: var(--gamma-border); background: var(--gamma-bg-default)"
                                    >{{ k }}</kbd
                                >
                            }
                        </dt>
                    </div>
                }
            </dl>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-flat-button color="primary" mat-dialog-close type="button">Got it</button>
        </mat-dialog-actions>
    `,
})
export class ShortcutsHelpDialog {
    readonly shortcuts = SHORTCUTS;
}
