import { describe, expect, it } from 'vitest';
import { MetadataEdge, MetadataNode, NodeKind } from 'app/inspecto/api';
import { legendFor, nodeColor, nodeShape, toG6Data } from './catalog-graph';

const node = (id: string, kind: string, label = id): MetadataNode =>
  ({ id, kind: kind as NodeKind, label });

const edge = (from: string, to: string, kind: string): MetadataEdge =>
  ({ from, to, kind: kind as MetadataEdge['kind'] });

describe('nodeShape', () => {
  it('maps known kinds to built-in G6 node types', () => {
    expect(nodeShape('SOURCE')).toBe('circle');
    expect(nodeShape('TABLE')).toBe('rect');
    expect(nodeShape('KPI')).toBe('diamond');
    expect(nodeShape('REPORT')).toBe('hexagon');
    expect(nodeShape('ENRICHMENT')).toBe('triangle');
  });

  it('falls back to a circle for unknown kinds', () => {
    expect(nodeShape('WIDGET' as NodeKind)).toBe('circle');
  });
});

describe('nodeColor', () => {
  it('returns a hex colour for known and unknown kinds', () => {
    expect(nodeColor('SOURCE')).toMatch(/^#[0-9A-F]{6}$/i);
    expect(nodeColor('WIDGET' as NodeKind)).toMatch(/^#[0-9A-F]{6}$/i);
  });

  it('gives each known kind a distinct colour', () => {
    const kinds: NodeKind[] = ['SOURCE', 'SCHEMA', 'TABLE', 'COLUMN', 'KPI', 'REPORT', 'ENRICHMENT'];
    expect(new Set(kinds.map(nodeColor)).size).toBe(kinds.length);
  });
});

describe('toG6Data', () => {
  it('carries id/label/kind into node data', () => {
    const { nodes } = toG6Data([node('t1', 'TABLE', 'Orders')], []);
    expect(nodes).toEqual([{ id: 't1', data: { label: 'Orders', kind: 'TABLE' } }]);
  });

  it('maps from/to to source/target and keeps the edge kind', () => {
    const { edges } = toG6Data([], [edge('a', 'b', 'EMITS')]);
    expect(edges[0].source).toBe('a');
    expect(edges[0].target).toBe('b');
    expect(edges[0].data.kind).toBe('EMITS');
  });

  it('produces unique ids even for parallel edges between the same pair', () => {
    const { edges } = toG6Data([], [
      edge('a', 'b', 'EMITS'),
      edge('a', 'b', 'REFERENCES'),
      edge('a', 'b', 'EMITS'),
    ]);
    expect(new Set(edges.map((e) => e.id)).size).toBe(3);
  });
});

describe('legendFor', () => {
  it('lists each distinct kind once, in first-seen order', () => {
    const legend = legendFor([
      node('t1', 'TABLE'), node('c1', 'COLUMN'), node('t2', 'TABLE'), node('s1', 'SOURCE'),
    ]);
    expect(legend.map((l) => l.kind)).toEqual(['TABLE', 'COLUMN', 'SOURCE']);
    expect(legend[0].fill).toBe(nodeColor('TABLE'));
  });
});
