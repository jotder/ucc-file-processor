import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { sql } from '@codemirror/lang-sql';
import { Extension } from '@codemirror/state';
import { EditorView, highlightActiveLine, keymap, lineNumbers, placeholder } from '@codemirror/view';
import { tags as t } from '@lezer/highlight';

/**
 * CodeMirror 6 wiring for the Pro SQL editor. Every colour resolves to a gamma `--gamma-*` CSS var, so the
 * editor tracks the active light/dark scheme automatically — and stays clean against the no-hardcoded-color
 * guard (it scans for hex / `rgb(<digit>`; `var(...)` and `rgba(var(--…), a)` are allowed).
 */
const gammaTheme = EditorView.theme({
    '&': {
        color: 'var(--gamma-text-default)',
        backgroundColor: 'var(--gamma-bg-default)',
        fontSize: '0.8125rem',
        border: '1px solid var(--gamma-border)',
        borderRadius: '6px',
    },
    '.cm-content': {
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
        caretColor: 'var(--gamma-text-default)',
        padding: '8px 0',
    },
    '.cm-gutters': {
        backgroundColor: 'var(--gamma-bg-default)',
        color: 'var(--gamma-text-secondary)',
        border: 'none',
    },
    '.cm-activeLine': { backgroundColor: 'rgba(var(--gamma-primary-rgb), 0.06)' },
    '.cm-activeLineGutter': { backgroundColor: 'rgba(var(--gamma-primary-rgb), 0.06)' },
    '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--gamma-text-default)' },
    '&.cm-focused': { outline: '2px solid rgba(var(--gamma-primary-rgb), 1)', outlineOffset: '2px' },
    '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
        backgroundColor: 'rgba(var(--gamma-primary-rgb), 0.20)',
    },
});

const gammaHighlight = HighlightStyle.define([
    { tag: [t.keyword, t.operatorKeyword, t.modifier], color: 'var(--gamma-primary)', fontWeight: '600' },
    { tag: [t.string, t.special(t.string)], color: 'var(--gamma-accent)' },
    { tag: [t.number, t.bool, t.null], color: 'var(--gamma-warn)' },
    { tag: [t.lineComment, t.blockComment], color: 'var(--gamma-text-secondary)', fontStyle: 'italic' },
    { tag: [t.function(t.variableName), t.function(t.propertyName)], color: 'var(--gamma-accent)' },
    { tag: [t.operator, t.punctuation, t.separator], color: 'var(--gamma-text-secondary)' },
]);

/** Build the extension set for one editor instance. `onChange` fires with the full doc on every edit. */
export function sqlEditorExtensions(opts: { onChange: (value: string) => void; placeholderText?: string }): Extension[] {
    return [
        lineNumbers(),
        highlightActiveLine(),
        history(),
        keymap.of([...defaultKeymap, ...historyKeymap]),
        sql(),
        syntaxHighlighting(gammaHighlight),
        EditorView.lineWrapping,
        EditorView.contentAttributes.of({ 'aria-label': 'SQL query editor' }),
        placeholder(opts.placeholderText ?? 'SELECT * FROM …'),
        gammaTheme,
        EditorView.updateListener.of((u) => {
            if (u.docChanged) opts.onChange(u.state.doc.toString());
        }),
    ];
}
