import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { SqlCodemirrorComponent } from './sql-codemirror.component';
import { SqlHistoryService } from './sql-history.service';

/**
 * The Pro **SQL editor control**: a CodeMirror editor plus a toolbar — a **history** menu (recent runs +
 * favorites, picking one copies it into the editor), a **favorite** star (toggles the current query), and a
 * **Run** button. The editor seeds from the `sql` input (the builder's generated SQL) and resets to it
 * whenever that changes ({@link linkedSignal}); local edits override until the next regenerate. Running and
 * history persistence are owned by the host — this control just emits `(run)`; the host executes, then
 * records successful runs via {@link SqlHistoryService}.
 */
@Component({
    selector: 'inspecto-sql-editor',
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatMenuModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        InspectoAlertComponent,
        SqlCodemirrorComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './sql-editor.component.html',
})
export class SqlEditorComponent {
    private readonly history = inject(SqlHistoryService);

    /** Generated SQL from the builder — the editor resets to this when it changes. */
    readonly sql = input('');
    readonly sourceName = input('data');
    readonly running = input(false);
    readonly error = input<string | null>(null);
    /** When true, show a second "Run on server" button (host wires it to a backend query). */
    readonly serverRun = input(false);

    /** Emits the current editor text on every edit (so the host can track it for "save as rule"). */
    readonly sqlChange = output<string>();
    /** Emits the SQL to execute client-side (over the loaded rows) when Run is pressed. */
    readonly run = output<string>();
    /** Emits the SQL to execute on the server (full dataset) when "Run on server" is pressed. */
    readonly runBackend = output<string>();

    /** Editor contents: derived from `sql`, locally editable, reset when `sql` changes. */
    readonly draft = linkedSignal(() => this.sql());

    readonly recent = computed(() => this.history.recent(this.sourceName()));
    readonly favorites = computed(() => this.history.favorites(this.sourceName()));
    readonly isFavorite = computed(() => this.history.isFavorite(this.sourceName(), this.draft()));

    onDraft(value: string): void {
        this.draft.set(value);
        this.sqlChange.emit(value);
    }

    /** Copy a history/favorite entry into the editor. */
    select(sql: string): void {
        this.draft.set(sql);
        this.sqlChange.emit(sql);
    }

    toggleFavorite(): void {
        this.history.toggleFavorite(this.sourceName(), this.draft());
    }

    onRun(): void {
        this.run.emit(this.draft());
    }

    onRunBackend(): void {
        this.runBackend.emit(this.draft());
    }

    /** Collapse a multi-line query to one line for the menu. */
    oneLine(sql: string): string {
        return sql.replace(/\s+/g, ' ').trim();
    }
}
