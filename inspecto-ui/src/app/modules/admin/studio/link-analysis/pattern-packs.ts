import { PatternStep } from 'app/inspecto/graph';

/**
 * **Link Analysis — pattern packs** (V2). A pattern pack is a named, parameterized investigation
 * template: a motif that pre-fills the pattern-match builder so an investigator starts from a known
 * fraud/network shape instead of a blank motif. Because node/edge **kinds** are data-specific, the
 * shipped packs are *structural* starting points (direction + length); the investigator specializes
 * the kind dropdowns to their graph. Domain-seeded packs (bound to a specific Space's dataset kinds)
 * are a follow-on — see docs/BACKLOG.md.
 *
 * Some shapes are better served by a dedicated tool than by a path motif — those carry a `tool` hint
 * pointing the investigator at it (circular flow ⇒ the Cycles tool, dense rings ⇒ Cohesive groups).
 */
export interface PatternPack {
    id: string;
    label: string;
    category: 'money' | 'telecom' | 'identity';
    description: string;
    /** The motif loaded into the builder. Blank kinds are wildcards to be refined per graph. */
    steps: PatternStep[];
    /** A better-fit analysis tool for this shape, shown as a hint (no motif can express it as a path). */
    tool?: 'cycles' | 'cohesion' | 'similarity';
}

export const PATTERN_PACKS: PatternPack[] = [
    {
        id: 'layering-chain',
        label: 'Layering chain',
        category: 'money',
        description: 'Funds relayed through a chain of intermediaries (A → B → C → D) to obscure origin — classic placement/layering.',
        steps: [{}, { direction: 'out' }, { direction: 'out' }, { direction: 'out' }],
    },
    {
        id: 'pass-through',
        label: 'Pass-through intermediary',
        category: 'money',
        description: 'A single intermediary that receives then forwards (A → M → B). Inspect the middle node — it is the mule/shell to scrutinize.',
        steps: [{}, { direction: 'out' }, { direction: 'out' }],
    },
    {
        id: 'inbound-collector',
        label: 'Inbound collector',
        category: 'money',
        description: 'Many parties converging INTO one account (they → M → target). Start from the collector and follow incoming links.',
        steps: [{}, { direction: 'in' }, { direction: 'in' }],
    },
    {
        id: 'forwarding-relay',
        label: 'Call-forwarding relay',
        category: 'telecom',
        description: 'A relay hop A → B → C — the building block of call-forwarding abuse and SIM-box relaying.',
        steps: [{}, { direction: 'out' }, { direction: 'out' }],
    },
    {
        id: 'circular-flow',
        label: 'Circular flow',
        category: 'money',
        description: 'Value or calls returning to their origin (A → … → A). Use the Cycles tool — a closed loop is not a simple path motif.',
        steps: [{}, { direction: 'out' }, { direction: 'out' }],
        tool: 'cycles',
    },
    {
        id: 'shared-associates',
        label: 'Shared associates',
        category: 'identity',
        description: 'Distinct identities that share the same devices/accounts. Use Similarity from a seed node, or Cohesive groups for the whole ring.',
        steps: [{}, { direction: 'both' }],
        tool: 'similarity',
    },
];
