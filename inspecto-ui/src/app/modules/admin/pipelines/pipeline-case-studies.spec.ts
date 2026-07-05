import { describe, expect, it } from 'vitest';
import type { AuthoredPipeline } from 'app/inspecto/api/pipelines.service';
import { CASE_STUDY_GRAMMARS, PIPELINE_CASE_STUDIES } from 'app/inspecto/mock/seeds/pipeline-case-studies.seed';

/**
 * Invariants for the pipeline case-study pack CS1–CS5 (docs/superpower/pipeline-case-studies.md).
 * Each case study exists to exercise a specific editor boundary — these tests are the contract
 * that future seed edits can't silently defuse it. Mirrors `geo-case-studies.spec.ts`.
 */

/** Connections seeded by operations.seed.ts — every `use: connections/<id>` must resolve here. */
const SEEDED_CONNECTIONS = ['cdr_sftp_prod', 'pg_warehouse', 's3_archive', 'local_inbox', 'legacy_ftp_down'];
/** Grammars seeded outside the pack (registry seeds in default-space.seed.ts). */
const REGISTRY_GRAMMARS = ['cdr_csv', 'pipe_delimited'];

const byName = new Map(PIPELINE_CASE_STUDIES.map((p) => [p.name, p]));
const cs = (name: string): AuthoredPipeline => byName.get(name)!;

const rels = (p: AuthoredPipeline): Set<string> => new Set(p.edges.map((e) => e.rel));
const indegree = (p: AuthoredPipeline, id: string): number => p.edges.filter((e) => e.to === id).length;

/** Count weakly-connected components (union-find over undirected edges). */
function componentCount(p: AuthoredPipeline): number {
    const parent = new Map<string, string>(p.nodes.map((n) => [n.id, n.id]));
    const find = (x: string): string => {
        let r = x;
        while (parent.get(r) !== r) r = parent.get(r)!;
        return r;
    };
    for (const e of p.edges) parent.set(find(e.from), find(e.to));
    return new Set(p.nodes.map((n) => find(n.id))).size;
}

/** Longest path (node count) in the DAG — memoized DFS. */
function longestChain(p: AuthoredPipeline): number {
    const out = new Map<string, string[]>();
    for (const e of p.edges) out.set(e.from, [...(out.get(e.from) ?? []), e.to]);
    const memo = new Map<string, number>();
    const depth = (id: string): number => {
        if (memo.has(id)) return memo.get(id)!;
        const d = 1 + Math.max(0, ...(out.get(id) ?? []).map(depth));
        memo.set(id, d);
        return d;
    };
    return Math.max(...p.nodes.map((n) => depth(n.id)));
}

describe('pipeline case-study pack — structural sanity (all five)', () => {
    it('has exactly the five case studies', () => {
        expect(PIPELINE_CASE_STUDIES.map((p) => p.name)).toEqual([
            'mediation_backbone',
            'fraud_velocity_stream',
            'audit_recon_feeds',
            'format_gauntlet',
            'deadletter_torture',
        ]);
    });

    it('node ids are unique and every edge endpoint exists', () => {
        for (const p of PIPELINE_CASE_STUDIES) {
            const ids = p.nodes.map((n) => n.id);
            expect(new Set(ids).size, p.name).toBe(ids.length);
            for (const e of p.edges) {
                expect(ids, `${p.name}: ${e.from}`).toContain(e.from);
                expect(ids, `${p.name}: ${e.to}`).toContain(e.to);
            }
        }
    });

    it('every connection/grammar reference resolves to a seeded id', () => {
        const grammarIds = [...Object.keys(CASE_STUDY_GRAMMARS), ...REGISTRY_GRAMMARS];
        for (const p of PIPELINE_CASE_STUDIES) {
            for (const n of p.nodes) {
                if (!n.use) continue;
                const [kind, id] = n.use.split('/');
                if (kind === 'connections') expect(SEEDED_CONNECTIONS, `${p.name}/${n.id}`).toContain(id);
                if (kind === 'grammar') expect(grammarIds, `${p.name}/${n.id}`).toContain(id);
            }
        }
    });

    it('the pack covers every exotic parser format via reusable grammars', () => {
        const formats = new Set(Object.values(CASE_STUDY_GRAMMARS).map((g) => g['parser_type']));
        expect(formats).toEqual(new Set(['asn1', 'json', 'xlsx', 'xml', 'html', 'txt', 'parquet']));
    });
});

describe('CS1 mediation_backbone — canvas scale + fan-out/fan-in', () => {
    const p = cs('mediation_backbone');

    it('is a 19+-node graph with 3 collectors and 3 sinks', () => {
        expect(p.nodes.length).toBeGreaterThanOrEqual(19);
        expect(p.nodes.filter((n) => n.type.startsWith('collector.')).length).toBe(3);
        expect(p.nodes.filter((n) => n.type.startsWith('sink.')).length).toBe(3);
    });

    it('routes into the four named service branches which fan back into one aggregate', () => {
        const r = rels(p);
        for (const branch of ['route:voice', 'route:sms', 'route:data', 'route:roaming']) expect(r).toContain(branch);
        expect(indegree(p, 'agg')).toBe(4);
    });

    it('the dedup stage also fans in all three normalized legs', () => {
        expect(indegree(p, 'dedup')).toBe(3);
    });
});

describe('CS2 fraud_velocity_stream — clone-mode streaming + alerting', () => {
    const p = cs('fraud_velocity_stream');

    it('tees the stream with a clone-mode route into realtime + archive branches', () => {
        const clone = p.nodes.find((n) => n.id === 'clone')!;
        expect(clone.config?.['mode']).toBe('clone');
        expect(rels(p)).toContain('route:realtime');
        expect(rels(p)).toContain('route:archive');
    });

    it('raises a CRITICAL alert and upserts candidates by key', () => {
        expect(p.nodes.find((n) => n.id === 'a_fraud')!.config?.['severity']).toBe('CRITICAL');
        const sink = p.nodes.find((n) => n.id === 's_cases')!;
        expect(sink.config?.['mode']).toBe('upsert');
        expect(sink.config?.['key_columns']).toBeTruthy();
    });
});

describe('CS3 audit_recon_feeds — disconnected legs on one canvas', () => {
    const p = cs('audit_recon_feeds');

    it('renders three weakly-connected components (two legs + the unwired draft island)', () => {
        expect(componentCount(p)).toBe(3);
    });

    it('the billing leg is a watermarked database extract with a gap alert', () => {
        const b = p.nodes.find((n) => n.id === 'b_collect')!;
        expect(String(b.config?.['query'])).toContain(':watermark');
        expect(rels(p)).toContain('gap');
    });

    it('loads the two sides the seeded switch_vs_billing reconciliation compares', () => {
        const tables = p.nodes.filter((n) => n.type === 'sink.database').map((n) => n.config?.['table']);
        expect(tables).toContain('switch_cdr');
        expect(tables).toContain('billing_cdr');
    });
});

describe('CS4 format_gauntlet — every parser format, every reject wired', () => {
    const p = cs('format_gauntlet');

    it('routes 5 ways and fans all five parsers back into one union', () => {
        expect(p.edges.filter((e) => e.rel.startsWith('route:')).length).toBe(5);
        expect(indegree(p, 'u_norm')).toBe(5);
    });

    it('every parser node has its unmatched branch wired to the reject pile', () => {
        const parsers = p.nodes.filter((n) => n.type.startsWith('parser.')).map((n) => n.id);
        expect(parsers.length).toBe(5);
        for (const id of parsers) {
            expect(p.edges.some((e) => e.from === id && e.rel === 'unmatched' && e.to === 's_rejects'), id).toBe(true);
        }
    });
});

describe('CS5 deadletter_torture — every control relation on a deep chain', () => {
    const p = cs('deadletter_torture');

    it('wires every control-flow relation kind', () => {
        const r = rels(p);
        for (const rel of ['failure', 'gap', 'unmatched', 'dropped', 'invalid', 'kept', 'success']) expect(r).toContain(rel);
    });

    it('has a 9-stage main chain and both alert severities', () => {
        expect(longestChain(p)).toBeGreaterThanOrEqual(9);
        const severities = p.nodes.filter((n) => n.type === 'transform.alert').map((n) => n.config?.['severity']);
        expect(new Set(severities)).toEqual(new Set(['WARNING', 'CRITICAL']));
    });

    it('collects from the known-down FTP connection (the failure paths are the point)', () => {
        expect(p.nodes.find((n) => n.id === 't_collect')!.use).toBe('connections/legacy_ftp_down');
    });
});
