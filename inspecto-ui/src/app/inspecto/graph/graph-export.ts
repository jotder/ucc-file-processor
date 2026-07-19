import type { G6GraphData } from './graph-types';
import { SVG_EXPORT_COLORS } from 'app/inspecto/theme/chart-tokens';

/**
 * Framework-free SVG/GraphML serializers for Link Analysis Studio exports (Phase F). Both take the
 * shared {@link G6GraphData} shape directly — no G6/canvas dependency — so they unit-test with plain
 * fixtures. `toSvg` additionally needs each node's canvas position (G6 renders off-DOM; the host reads
 * it via `Graph.getElementPosition` and passes it in).
 */

const NODE_RADIUS = 18;

function escapeXml(s: string): string {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/**
 * Hand-rolled SVG serializer (not a G6 renderer-mode switch — see `docs/BACKLOG.md` INV-1): nodes as
 * labelled circles, edges as lines, sized to the graph's bounding box. Nodes without a known position
 * (absent from `positions`) are skipped — G6 hasn't laid out an off-screen/unmounted graph.
 */
export function toSvg(g: G6GraphData, positions: Map<string, { x: number; y: number }>): string {
    const pts = [...positions.values()];
    const minX = pts.length ? Math.min(...pts.map((p) => p.x)) : 0;
    const minY = pts.length ? Math.min(...pts.map((p) => p.y)) : 0;
    const maxX = pts.length ? Math.max(...pts.map((p) => p.x)) : 0;
    const maxY = pts.length ? Math.max(...pts.map((p) => p.y)) : 0;
    const pad = NODE_RADIUS * 3;
    const width = Math.max(1, maxX - minX + pad * 2);
    const height = Math.max(1, maxY - minY + pad * 2);
    const ox = pad - minX, oy = pad - minY;

    const lines = g.edges
        .map((e) => {
            const s = positions.get(e.source), t = positions.get(e.target);
            if (!s || !t) return '';
            return `<line x1="${s.x + ox}" y1="${s.y + oy}" x2="${t.x + ox}" y2="${t.y + oy}" `
                + `stroke="${SVG_EXPORT_COLORS.edge}" stroke-width="1.5"/>`;
        })
        .filter(Boolean)
        .join('\n');

    const circles = g.nodes
        .map((n) => {
            const p = positions.get(n.id);
            if (!p) return '';
            const x = p.x + ox, y = p.y + oy;
            return `<g>`
                + `<circle cx="${x}" cy="${y}" r="${NODE_RADIUS}" fill="${SVG_EXPORT_COLORS.node}"/>`
                + `<text x="${x}" y="${y + NODE_RADIUS + 14}" text-anchor="middle" font-size="11" `
                + `fill="${SVG_EXPORT_COLORS.text}" font-family="sans-serif">${escapeXml(n.data.label)}</text>`
                + `</g>`;
        })
        .filter(Boolean)
        .join('\n');

    return `<?xml version="1.0" encoding="UTF-8"?>\n`
        + `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" width="${width}" height="${height}">\n`
        + `<rect x="0" y="0" width="${width}" height="${height}" fill="${SVG_EXPORT_COLORS.background}"/>\n`
        + `${lines}\n${circles}\n`
        + `</svg>\n`;
}

/**
 * Generic/plain GraphML serializer (no vendor dialect — yEd/Gephi/NetworkX all accept the bare
 * schema): declares `label`/`kind` keys for nodes and edges up front, then one `<node>`/`<edge>` per
 * element. No geometry — plain GraphML has no standard position attribute; a target tool that wants
 * layout re-runs its own.
 */
export function toGraphml(g: G6GraphData): string {
    const nodeRows = g.nodes
        .map((n) => `<node id="${escapeXml(n.id)}">`
            + `<data key="label">${escapeXml(n.data.label)}</data>`
            + `<data key="kind">${escapeXml(String(n.data.kind))}</data>`
            + `</node>`)
        .join('\n');
    const edgeRows = g.edges
        .map((e) => `<edge id="${escapeXml(e.id)}" source="${escapeXml(e.source)}" target="${escapeXml(e.target)}">`
            + `<data key="ekind">${escapeXml(e.data.kind)}</data>`
            + `</edge>`)
        .join('\n');

    return `<?xml version="1.0" encoding="UTF-8"?>\n`
        + `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">\n`
        + `<key id="label" for="node" attr.name="label" attr.type="string"/>\n`
        + `<key id="kind" for="node" attr.name="kind" attr.type="string"/>\n`
        + `<key id="ekind" for="edge" attr.name="kind" attr.type="string"/>\n`
        + `<graph id="G" edgedefault="directed">\n`
        + `${nodeRows}\n${edgeRows}\n`
        + `</graph>\n</graphml>\n`;
}
