import { describe, expect, it } from 'vitest';
import { ComponentKind } from './component-kind';
import { hasEditorRoute, registerEditorRoute, resolveEditorLink } from './editor-registry';

/** Spec-local kind factory (keys prefixed to avoid colliding with real registrations in the worker). */
function kind(id: string, editorKey?: string): ComponentKind {
    return {
        id,
        label: id,
        allowedPartKinds: [],
        wiring: 'none',
        config: { validate: () => [] },
        ...(editorKey ? { authoring: { editorKey } } : {}),
    };
}

describe('editor-registry', () => {
    it('resolves a kind through its editorKey to the registered route factory', () => {
        registerEditorRoute('spec-widget', (id) => ['/spec/widgets', id]);
        expect(hasEditorRoute('spec-widget')).toBe(true);
        expect(resolveEditorLink(kind('spec-w', 'spec-widget'), 'w1')).toEqual(['/spec/widgets', 'w1']);
    });

    it('supports id-less (pane / dialog-based) route factories', () => {
        registerEditorRoute('spec-job', () => ['/spec/jobs']);
        expect(resolveEditorLink(kind('spec-j', 'spec-job'), 'ignored')).toEqual(['/spec/jobs']);
    });

    it('fails closed: unknown kind id, no editorKey, or unregistered editorKey → null', () => {
        expect(resolveEditorLink('spec-no-such-kind', 'x')).toBeNull();
        expect(resolveEditorLink(kind('spec-plain'), 'x')).toBeNull();
        expect(resolveEditorLink(kind('spec-k', 'spec-unregistered-key'), 'x')).toBeNull();
    });

    it('throws on a duplicate editorKey (mirrors registerKind)', () => {
        registerEditorRoute('spec-dup', () => ['/a']);
        expect(() => registerEditorRoute('spec-dup', () => ['/b'])).toThrow(/Duplicate/);
    });
});
