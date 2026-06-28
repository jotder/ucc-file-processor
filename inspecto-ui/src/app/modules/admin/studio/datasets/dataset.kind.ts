import { ComponentKind, ConfigFinding, getKind, registerKind } from 'app/inspecto/component-model';
import { DatasetConfig } from './dataset-types';

/**
 * The `dataset` {@link ComponentKind} — Studio's first kind on the unified component model. Atomic (no parts),
 * `wiring:'none'`; its config is a {@link DatasetConfig}. Authoring + exec are string seams resolved
 * Angular-side (so the model stays framework-pure). Registering this proves Studio is built *on* the model.
 */
export const DATASET_KIND: ComponentKind<DatasetConfig> = {
    id: 'dataset',
    label: 'Dataset',
    allowedPartKinds: [],
    wiring: 'none',
    config: {
        validate: validateDatasetConfig,
        create: () => ({ kind: 'virtual', sourceName: 'data', query: null, columns: [], metrics: [] }),
    },
    authoring: { editorKey: 'dataset' },
    exec: { runnerKey: 'query' },
};

/** Tiny hand-written validator (no schema engine, per the adoption plan's STOP): source required; a virtual dataset needs a query. */
export function validateDatasetConfig(config: unknown): ConfigFinding[] {
    const c = (config ?? {}) as Partial<DatasetConfig>;
    const findings: ConfigFinding[] = [];
    if (!c.sourceName) {
        findings.push({ severity: 'error', path: 'sourceName', message: 'A source is required.' });
    }
    if (c.kind === 'virtual' && !c.query) {
        findings.push({ severity: 'error', path: 'query', message: 'A virtual dataset needs a query.' });
    }
    if (c.kind !== 'virtual' && !c.physicalRef) {
        findings.push({ severity: 'warning', path: 'physicalRef', message: 'No physical reference set.' });
    }
    return findings;
}

// Side-effect registration (guarded so a re-import / repeated spec load doesn't trip the dup-id guard).
if (!getKind(DATASET_KIND.id)) {
    registerKind(DATASET_KIND);
}
