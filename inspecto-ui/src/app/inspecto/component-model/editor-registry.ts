import { ComponentKind } from './component-kind';
import { getKind } from './component-registry';

/**
 * Editor-route registry (S7 / spike P4) — the Angular-side resolution of a {@link ComponentKind}'s
 * `authoring.editorKey` seam. The kind names its editor with a string key (framework-pure); each
 * feature registers the matching route factory here, next to where it registers its kind (the same
 * module-side-effect lifecycle as {@link registerKind}). Consumers resolve a kind to an in-app
 * router-commands array via {@link resolveEditorLink} — replacing the per-pane hardcoded
 * kind→path tables. Fail closed: an unregistered kind / editorKey resolves to `null` (no link).
 *
 * A factory may ignore the id: dialog-based panes (jobs / decision-rules / requirements /
 * pipelines) have no `/:id` detail route — their "editor" is the pane itself.
 */
export type EditorRouteFactory = (id: string) => string[];

const EDITOR_ROUTES = new Map<string, EditorRouteFactory>();

/** Register a route factory for an editor key. Throws on a duplicate (mirrors {@link registerKind});
 *  registration sites guard with {@link hasEditorRoute} so repeated side-effect imports are safe. */
export function registerEditorRoute(editorKey: string, factory: EditorRouteFactory): void {
    if (EDITOR_ROUTES.has(editorKey)) {
        throw new Error(`Duplicate editor route '${editorKey}'`);
    }
    EDITOR_ROUTES.set(editorKey, factory);
}

export function hasEditorRoute(editorKey: string): boolean {
    return EDITOR_ROUTES.has(editorKey);
}

/**
 * Resolve a component to its in-app editor route: kind → `authoring.editorKey` → registered route
 * factory. Accepts the kind object or its id (looked up on the kind registry). Returns `null` —
 * never a guess — when the kind is unknown, declares no editor, or its editorKey has no
 * registered route (fail closed).
 */
export function resolveEditorLink(kind: ComponentKind | string, id: string): string[] | null {
    const k = typeof kind === 'string' ? getKind(kind) : kind;
    const editorKey = k?.authoring?.editorKey;
    if (!editorKey) return null;
    const factory = EDITOR_ROUTES.get(editorKey);
    return factory ? factory(id) : null;
}
