import { describe, expect, it } from 'vitest';
import { MetadataEdge, MetadataNode } from '../../shared/api';
import {
  legendFor, nodeColor, nodeShape, toDiagramEdges, toDiagramNodes,
} from './catalog-graph';

const node = (id: string, kind: string, label = id): MetadataNode =>
  ({ id, kind: kind as MetadataNode['kind'], label });

describe('nodeShape', () => {
  it('maps known kinds to distinct built-in shapes', () => {
    expect(nodeShape('SOURCE')).toBe('database');
    expect(nodeShape('TABLE')).toBe('rectangle');
    expect(nodeShape('KPI')).toBe('process');
    const known = ['SOURCE', 'SCHEMA', 'TABLE', 'COLUMN', 'KPI', 'REPORT', 'ENRICHMENT']
      .map((k) => nodeShape(k as MetadataNode['kind']));
    expect(new Set(known).size).toBe(known.length); // all distinct
  });

  it('falls back to a terminator for unknown kinds', () => {
    expect(nodeShape('WIDGET' as MetadataNode['kind'])).toBe('terminator');
  });
});

describe('nodeColor', () => {
  it('returns a hex colour for known and unknown kinds', () => {
    expect(nodeColor('SOURCE')).toMatch(/^#[0-9A-F]{6}$/i);
    expect(nodeColor('WIDGET' as MetadataNode['kind'])).toMatch(/^#[0-9A-F]{6}$/i);
  });
});

describe('toDiagramNodes', () => {
  it('carries id/label/kind and derives shape + stroke style', () => {
    const [d] = toDiagramNodes([node('t1', 'TABLE', 'Orders')]);
    expect(d).toEqual({
      id: 't1', label: 'Orders', kind: 'TABLE', shape: 'rectangle',
      style: { stroke: nodeColor('TABLE'), 'stroke-width': 2 },
    });
  });
});

describe('toDiagramEdges', () => {
  const edge = (from: string, to: string, kind: string): MetadataEdge =>
    ({ from, to, kind: kind as MetadataEdge['kind'] });

  it('preserves from/to/kind', () => {
    const [d] = toDiagramEdges([edge('a', 'b', 'EMITS')]);
    expect(d.from).toBe('a');
    expect(d.to).toBe('b');
    expect(d.kind).toBe('EMITS');
  });

  it('produces unique keys even for parallel edges between the same pair', () => {
    const edges = toDiagramEdges([
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
