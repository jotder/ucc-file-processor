import { ComponentType } from 'app/inspecto/api';
import { refsForComponent } from 'app/inspecto/component-model';

/**
 * Metadata Bundle — the cross-instance transfer format (staging → production promotion). A bundle
 * carries **configuration only, never data rows**: dataset metadata, widgets, dashboards, saved
 * Link-Analysis / Geo-Map views, pipelines, and the registry pieces they reference (grammars,
 * schemas, transforms, sinks, connections). Everything here is pure and framework-free so the
 * format, dependency closure and import planning are unit-testable
 * (`docs/superpower/metadata-bundle.md`; spec: `bundle.spec.ts`).
 */

/** Everything a bundle can carry: the Studio/registry component kinds + the two non-component stores. */
export type BundleKind =
    | Extract<ComponentType, 'grammar' | 'schema' | 'transform' | 'sink' | 'dataset' | 'widget' | 'dashboard' | 'link-analysis-view' | 'geo-map-view'>
    | 'connection'
    | 'authored-pipeline';

/** Import order: referenced kinds before their referencers, so a fresh instance renders everything. */
export const BUNDLE_KINDS: { kind: BundleKind; label: string }[] = [
    { kind: 'connection', label: 'Connections' },
    { kind: 'grammar', label: 'Grammars' },
    { kind: 'schema', label: 'Schemas' },
    { kind: 'transform', label: 'Transforms' },
    { kind: 'sink', label: 'Sinks' },
    { kind: 'dataset', label: 'Datasets (metadata)' },
    { kind: 'link-analysis-view', label: 'Link Analysis views' },
    { kind: 'geo-map-view', label: 'Geo Map views' },
    { kind: 'widget', label: 'Widgets' },
    { kind: 'dashboard', label: 'Dashboards' },
    { kind: 'authored-pipeline', label: 'Pipelines' },
];

export const BUNDLE_FORMAT = 'inspecto-metadata-bundle';
export const BUNDLE_VERSION = 1;

export interface BundleItem {
    kind: BundleKind;
    id: string;
    /** The artifact's full config map — for components the parsed content, for pipelines the lossless
     *  AuthoredPipeline, for connections the secret-masked profile. Visual aspects (widget options,
     *  view display/camera, …) live inside, so an imported viz renders as authored. */
    content: Record<string, unknown>;
}

export interface MetadataBundle {
    format: typeof BUNDLE_FORMAT;
    version: number;
    exportedAt: string;
    /** The exporting space id, when multi-space (informational). */
    sourceSpace: string | null;
    items: BundleItem[];
}

const KIND_ORDER = new Map(BUNDLE_KINDS.map((k, i) => [k.kind, i]));
const KNOWN_KINDS = new Set(BUNDLE_KINDS.map((k) => k.kind));

export function buildBundle(items: BundleItem[], sourceSpace: string | null, now: Date = new Date()): MetadataBundle {
    const sorted = [...items].sort(
        (a, b) => (KIND_ORDER.get(a.kind)! - KIND_ORDER.get(b.kind)!) || a.id.localeCompare(b.id),
    );
    return { format: BUNDLE_FORMAT, version: BUNDLE_VERSION, exportedAt: now.toISOString(), sourceSpace, items: sorted };
}

/** Parse + validate an uploaded bundle file. Returns the bundle or human-readable errors. */
export function parseBundle(text: string): { bundle?: MetadataBundle; errors: string[] } {
    let raw: unknown;
    try {
        raw = JSON.parse(text);
    } catch {
        return { errors: ['Not valid JSON.'] };
    }
    const b = raw as Partial<MetadataBundle>;
    const errors: string[] = [];
    if (b?.format !== BUNDLE_FORMAT) errors.push(`Not an Inspecto metadata bundle (format must be "${BUNDLE_FORMAT}").`);
    if (typeof b?.version !== 'number' || b.version > BUNDLE_VERSION) errors.push(`Unsupported bundle version "${b?.version}".`);
    if (!Array.isArray(b?.items)) errors.push('Missing "items" array.');
    if (errors.length) return { errors };
    for (const [i, it] of (b.items as BundleItem[]).entries()) {
        if (!it || typeof it.id !== 'string' || !it.id) errors.push(`Item ${i}: missing id.`);
        else if (!KNOWN_KINDS.has(it.kind)) errors.push(`Item "${it.id}": unknown kind "${it.kind}".`);
        else if (typeof it.content !== 'object' || it.content === null) errors.push(`Item "${it.id}": missing content.`);
    }
    return errors.length ? { errors } : { bundle: b as MetadataBundle, errors: [] };
}

/** The artifacts an item references — what "include dependencies" pulls into the export. Delegates to
 *  the R1 metadata-network derivation (`refsForComponent`); refs to kinds a bundle can't carry are dropped. */
export function refsOf(item: BundleItem): { kind: BundleKind; id: string }[] {
    return refsForComponent(item.kind, item.content)
        .filter((r) => KNOWN_KINDS.has(r.kind as BundleKind))
        .map((r) => ({ kind: r.kind as BundleKind, id: r.id }));
}

const key = (kind: BundleKind, id: string): string => `${kind}/${id}`;

/**
 * Expand a selection to its dependency closure (BFS over {@link refsOf}), resolving against
 * everything available on this instance. References that don't resolve are reported, not fatal —
 * the bundle still exports, the import preview will show them as unresolved on the target too.
 */
export function withDependencies(
    selected: BundleItem[],
    available: BundleItem[],
): { items: BundleItem[]; missing: string[] } {
    const byKey = new Map(available.map((i) => [key(i.kind, i.id), i]));
    const out = new Map(selected.map((i) => [key(i.kind, i.id), i]));
    const missing = new Set<string>();
    const queue = [...selected];
    while (queue.length) {
        for (const ref of refsOf(queue.shift()!)) {
            const k = key(ref.kind, ref.id);
            if (out.has(k)) continue;
            const found = byKey.get(k);
            if (!found) {
                missing.add(k);
                continue;
            }
            out.set(k, found);
            queue.push(found);
        }
    }
    return { items: [...out.values()], missing: [...missing].sort() };
}

/** One row of the import preview: the item, whether the target already has it, and the chosen action. */
export interface ImportRow {
    item: BundleItem;
    exists: boolean;
    action: 'import' | 'overwrite' | 'skip';
}

/**
 * Plan an import against the target's existing ids: new items default to `import`, existing ones to
 * `skip` — the user flips existing ones (datasets especially) to `overwrite` explicitly. Rows come
 * back in {@link BUNDLE_KINDS} order so referenced kinds are written first.
 */
export function planImport(bundle: MetadataBundle, existing: Map<BundleKind, Set<string>>): ImportRow[] {
    return [...bundle.items]
        .sort((a, b) => (KIND_ORDER.get(a.kind)! - KIND_ORDER.get(b.kind)!) || a.id.localeCompare(b.id))
        .map((item) => {
            const exists = existing.get(item.kind)?.has(item.id) ?? false;
            return { item, exists, action: exists ? 'skip' : 'import' } as ImportRow;
        });
}
