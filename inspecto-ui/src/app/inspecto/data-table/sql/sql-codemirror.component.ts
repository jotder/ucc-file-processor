import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, OnDestroy, effect, input, output, viewChild } from '@angular/core';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { sqlEditorExtensions } from './codemirror-setup';

/**
 * Thin, presentational CodeMirror 6 host: a SQL editor with gamma-themed syntax highlighting. Pure
 * value-in / value-out — no business logic. External `value` changes are pushed into the document (guarded
 * so a user edit doesn't echo back), and every edit emits `valueChange`.
 */
@Component({
    selector: 'inspecto-sql-codemirror',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `<div #host class="block w-full overflow-hidden"></div>`,
})
export class SqlCodemirrorComponent implements AfterViewInit, OnDestroy {
    readonly value = input('');
    readonly valueChange = output<string>();

    private readonly hostEl = viewChild.required<ElementRef<HTMLDivElement>>('host');
    private view?: EditorView;
    /** True while we push an external value into the doc, so that update doesn't echo back as a user edit. */
    private syncing = false;

    constructor() {
        // Reflect external value changes (e.g. the builder regenerated the SQL) into the editor.
        effect(() => {
            const v = this.value();
            const view = this.view;
            if (view && v !== view.state.doc.toString()) {
                this.syncing = true;
                view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: v } });
                this.syncing = false;
            }
        });
    }

    ngAfterViewInit(): void {
        this.view = new EditorView({
            parent: this.hostEl().nativeElement,
            state: EditorState.create({
                doc: this.value(),
                extensions: sqlEditorExtensions({ onChange: (val) => this.syncing || this.valueChange.emit(val) }),
            }),
        });
    }

    ngOnDestroy(): void {
        this.view?.destroy();
    }
}
