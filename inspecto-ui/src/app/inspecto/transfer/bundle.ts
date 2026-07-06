import { ComponentType } from 'app/inspecto/api';
import { RefRel, refsForComponent } from 'app/inspecto/component-model';
import { hashContent } from './content-hash';

/**
 * Metadata Bundle — the cross-instance transfer format (staging → production promotion). A bundle
 * carries **configuration only, never data rows**: dataset metadata, widgets, dashboards, saved
 * Link-Analysis / Geo-Map views, pipelines, and the registry pieces they reference (grammars,
 * schemas, transforms, sinks, connections). Everything here is pure and framework-free so the
 * format, dependency closure and import planning are unit-testable
 * (`docs/superpower/metadata-bundle.md`; spec: `bundle.spec.ts`).
 *
 * **v2 (R6) makes the subgraph self-describing** (`docs/superpower/transportability-plan.md`): every
 * item carries its outgoing lineage `refs` (each marked included/external) and `provenance`
 * (origin space + time + content hash), and the bundle carries the aggregated external `requires` —
 * its contract with the target. v1 files still import (refs/provenance absent ⇒ derived on the target).
 */

/** Everything a bundle can carry: the Studio/registry component kinds + the three non-component stores. */
export type BundleKind =
    | Extract<ComponentType, 'grammar' | 'schema' | 'transform' | 'sink' | 'dataset' | 'query' | 'widget' | 'dashboard' | 'link-analysis-view' | 'geo-map-view'>
    | 'connection'
    | 'authored-pipeline'
    | 'job'
    | 'decision-rule';

/** Import order: referenced kinds before their referencers, so a fresh instance renders everything
 *  (jobs then decision rules come last — they trigger on / invoke pipelines, jobs and widgets). */
export const BUNDLE_KINDS: { kind: BundleKind; label: string }[] = [
    { kind: 'connection', label: 'Connections' },
    { kind: 'grammar', label: 'Grammars' },
    { kind: 'schema', label: 'Schemas' },
    { kind: 'transform', label: 'Transforms' },
    { kind: 'sink', label: 'Sinks' },
    { kind: 'dataset', label: 'Datasets (metadata)' },
    { kind: 'query', label: 'Queries' },
    { kind: 'link-analysis-view', label: 'Link Analysis views' },
    { kind: 'geo-map-view', label: 'Geo Map views' },
    { kind: 'widget', label: 'Widgets' },
    { kind: 'dashboard', label: 'Dashboards' },
    { kind: 'authored-pipeline', label: 'Pipelines' },
    { kind: 'job', label: 'Jobs' },
    { kind: 'decision-rule', label: 'Decision Rules' },
];

export const BUNDLE_FORMAT = 'inspecto-metadata-bundle';
export const BUNDLE_VERSION = 2;

/** Whether a referenced artifact travels in this bundle or must already exist on the target. */
export type BundleRefResolution = 'included' | 'external';

/** One outgoing lineage edge of a bundle item — the R1 ref (kind/id/rel) + its resolution. */
export interface BundleRef {
    kind: BundleKind;
    id: string;
    rel: RefRel;
    resolution: BundleRefResolution;
}

/** Where an item's config came from — enables drift detection, idempotent re-promotion, audit. */
export interface BundleProvenance {
    sourceSpace: string | null;
    exportedAt: string;
    /** SHA-256 (hex) of the item content's canonical JSON, at export time. */
    contentHash: string;
}

export interface BundleItem {
    kind: BundleKind;
    id: string;
    /** The artifact's full config map — for components the parsed content, for pipelines the lossless
     *  AuthoredPipeline, for connections the secret-masked profile. Visual aspects (widget options,
     *  view display/camera, …) live inside, so an imported viz renders as authored. */
    content: Record<string, unknown>;
    /** v2: the item's outgoing lineage edges (absent in v1 files). */
    refs?: BundleRef[];
    /** v2: origin/time/hash (absent in v1 files). */
    provenance?: BundleProvenance;
}

export interface MetadataBundle {
    format: typeof BUNDLE_FORMAT;
    version: number;
    exportedAt: string;
    /** The exporting space id, when multi-space (informational). */
    sourceSpace: string | null;
    items: BundleItem[];
    /** v2: the deduped external refs — the bundle's contract with the target (absent in v1 files). */
    requires?: BundleRef[];
}

const KIND_ORDER = new Map(BUNDLE_KINDS.map((k, i) => [k.kind, i]));
const KNOWN_KINDS = new Set(BUNDLE_KINDS.map((k) => k.kind));
const key = (kind: BundleKind, id: string): string => `${kind}/${id}`;

const byKindThenId = (a: BundleItem, b: BundleItem): number =>
    (KIND_ORDER.get(a.kind)! - KIND_ORDER.get(b.kind)!) || a.id.localeCompare(b.id);

/** The item's lineage edges, marked included/external against the set of ids travelling in the bundle.
 *  Delegates to the R1 derivation (`refsForComponent`); `pipeline` → the bundle's `authored-pipeline`
 *  store name; refs to kinds a bundle can't carry are dropped. */
function itemRefs(item: BundleItem, includedKeys: Set<string>): BundleRef[] {
    return refsForComponent(item.kind, item.content)
        .map((r) => ({ kind: (r.kind === 'pipeline' ? 'authored-pipeline' : r.kind) as BundleKind, id: r.id, rel: r.rel }))
        .filter((r) => KNOWN_KINDS.has(r.kind))
        .map((r) => ({ ...r, resolution: (includedKeys.has(key(r.kind, r.id)) ? 'included' : 'external') as BundleRefResolution }));
}

/** The deduped external refs across all items — recomputed for v1 files that lack a top-level `requires`. */
function deriveRequires(items: BundleItem[]): BundleRef[] {
    const includedKeys = new Set(items.map((i) => key(i.kind, i.id)));
    const out = new Map<string, BundleRef>();
    for (const item of items) {
        for (const r of item.refs ?? itemRefs(item, includedKeys)) {
            if (r.resolution === 'external' && !out.has(key(r.kind, r.id))) out.set(key(r.kind, r.id), r);
        }
    }
    return [...out.values()];
}

export function buildBundle(items: BundleItem[], sourceSpace: string | null, now: Date = new Date()): MetadataBundle {
    const sorted = [...items].sort(byKindThenId);
    const exportedAt = now.toISOString();
    const includedKeys = new Set(sorted.map((i) => key(i.kind, i.id)));
    const withMeta: BundleItem[] = sorted.map((item) => ({
        kind: item.kind,
        id: item.id,
        content: item.content,
        refs: itemRefs(item, includedKeys),
        provenance: { sourceSpace, exportedAt, contentHash: hashContent(item.content) },
    }));
    return { format: BUNDLE_FORMAT, version: BUNDLE_VERSION, exportedAt, sourceSpace, items: withMeta, requires: deriveRequires(withMeta) };
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
 *  the R1 metadata-network derivation (`refsForComponent`); the model's `pipeline` kind maps onto the
 *  bundle's `authored-pipeline` store name; refs to kinds a bundle can't carry are dropped. */
export function refsOf(item: BundleItem): { kind: BundleKind; id: string }[] {
    return refsForComponent(item.kind, item.content)
        .map((r) => ({ kind: (r.kind === 'pipeline' ? 'authored-pipeline' : r.kind) as BundleKind, id: r.id }))
        .filter((r) => KNOWN_KINDS.has(r.kind));
}

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

/** The target's existing artifacts, indexed id → content hash, for the import fit-check. */
export type TargetIndex = Map<BundleKind, Map<string, string>>;

/** Build a {@link TargetIndex} from the artifacts loaded off the target instance. */
export function targetIndex(items: BundleItem[]): TargetIndex {
    const m: TargetIndex = new Map();
    for (const i of items) {
        if (!m.has(i.kind)) m.set(i.kind, new Map());
        m.get(i.kind)!.set(i.id, hashContent(i.content));
    }
    return m;
}

/** One row of the import preview: the item, whether the target already has it, whether it has drifted
 *  (exists but content differs from what the source exported), and the chosen action. */
export interface ImportRow {
    item: BundleItem;
    exists: boolean;
    drifted: boolean;
    action: 'import' | 'overwrite' | 'skip';
}

/**
 * Fit-check an import against the target index: new items default to `import`; existing ones to
 * `skip` (the user opts into `overwrite`) — flagged `drifted` when the content differs from the
 * target's, so identical re-promotions are visibly idempotent. Rows come back in {@link BUNDLE_KINDS}
 * order so referenced kinds are written first.
 */
export function planImport(bundle: MetadataBundle, target: TargetIndex): ImportRow[] {
    return [...bundle.items].sort(byKindThenId).map((item) => {
        const targetHash = target.get(item.kind)?.get(item.id);
        const exists = targetHash !== undefined;
        const drifted = exists && targetHash !== hashContent(item.content);
        return { item, exists, drifted, action: exists ? 'skip' : 'import' } as ImportRow;
    });
}

/** A `requires` (external ref) resolved against the target: does the referent already exist there? */
export interface RequireStatus {
    ref: BundleRef;
    status: 'satisfied' | 'missing';
}

/**
 * Resolve the bundle's external `requires` against the target — the "will this land whole?" panel
 * shown before any write. External refs carry no travelling content, so they classify
 * satisfied/missing only (drift is a per-item concept, surfaced on the rows). v1 files (no top-level
 * `requires`) have theirs derived from the items.
 */
export function resolveRequires(bundle: MetadataBundle, target: TargetIndex): RequireStatus[] {
    const requires = bundle.requires ?? deriveRequires(bundle.items);
    return requires.map((ref) => ({ ref, status: target.get(ref.kind)?.has(ref.id) ? 'satisfied' : 'missing' }));
}
