import type { ComponentDef } from '../../api/components.service';
import type { IconMap } from '../../api/icon-map.service';
import { NODE_KIND_COLORS } from '../../theme/chart-tokens';
import { componentCollection } from '../handlers/components.handler';
import { MockStore } from '../mock-store';

/** Shared helpers for seed packs (the default pack + the W5 Space-Template packs). */

/** Store one `ComponentDef` of `kind` under the component collection (dataset/widget/dashboard/…). */
export function putComponent(
    store: MockStore,
    space: string,
    kind: string,
    name: string,
    content: Record<string, unknown>,
): void {
    const def: ComponentDef = { type: kind, name, ref: `${kind}/${name}`, content };
    store.put(space, componentCollection(kind), name, def);
}

/** The processor icon map (category defaults + sub-type overrides) every space needs for the Pipelines canvas. */
export function seedIconMap(store: MockStore, space: string): void {
    const C = NODE_KIND_COLORS; // category accent colours, sourced from the canvas token owner
    const iconMap: IconMap = {
        SOURCE: { glyph: 'arrow-in', color: C.STREAM},
        PARSE: { glyph: 'lines', color: C.SCHEMA },
        TRANSFORM: { glyph: 'transform', color: C.ENRICHMENT },
        SINK: { glyph: 'cylinder', color: C.TABLE },
        CONTROL: { glyph: 'bell', color: C.KPI },
        'collector.file': { glyph: 'file', color: C.STREAM},
        'collector.database': { glyph: 'database', color: C.STREAM},
        'collector.stream': { glyph: 'stream', color: C.STREAM},
        'transform.filter': { glyph: 'filter', color: C.ENRICHMENT },
        'transform.route': { glyph: 'route', color: C.ENRICHMENT },
        'transform.aggregate': { glyph: 'sigma', color: C.ENRICHMENT },
        'transform.alert': { glyph: 'bell', color: C.KPI },
        'sink.file': { glyph: 'write', color: C.TABLE },
        'sink.database': { glyph: 'database', color: C.TABLE },
    };
    store.put(space, 'config', 'icon-map', iconMap);
}
