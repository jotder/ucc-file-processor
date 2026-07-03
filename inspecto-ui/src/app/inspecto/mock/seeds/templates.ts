import type { SpaceTemplateInfo } from '../../api/spaces.service';
import { MockStore } from '../mock-store';
import { seedFinancialAudit } from './financial-audit.seed';
import { seedFraudMgmt } from './fraud-mgmt.seed';
import { seedLinkAnalysis } from './link-analysis.seed';
import { seedTelecomRa } from './telecom-ra.seed';

/**
 * The **Space Template** catalog (W5) — a Space Template is a reusable blueprint bundle of
 * Components (Connections → Sources → Pipelines → Datasets → Widgets → Dashboards → Rules + sample
 * rows + Ops entities) that instantiates a new Space (Type → Instance: Template is the Type, the
 * Space the Instance — docs/GLOSSARY.md).
 *
 * Templates are server-global and each carries a seed *function*, so they live here as a static TS
 * registry rather than as entities in the (localStorage-persisted, per-space) MockStore. The mock
 * `/spaces/templates` endpoint serves the metadata ({@link SpaceTemplateInfo}); `POST /spaces` with
 * a `template` id runs the pack — the UI stays REST-shaped for a later backend cutover.
 */

export interface SpaceTemplate extends SpaceTemplateInfo {
    /** The seed pack — populates a freshly created space with the blueprint. */
    seed: (store: MockStore, space: string) => void;
}

/** Strip a template to its wire-visible metadata. */
export function templateInfo({ seed: _seed, ...info }: SpaceTemplate): SpaceTemplateInfo {
    return info;
}

export function findTemplate(id: string): SpaceTemplate | undefined {
    return SPACE_TEMPLATES.find((t) => t.id === id);
}

export const SPACE_TEMPLATES: SpaceTemplate[] = [
    {
        id: 'telecom-ra',
        name: 'Telecom Revenue Assurance',
        tagline: 'Find revenue leakage between the network and billing.',
        description:
            'Switch CDRs vs. billing rated events: ingest both sides, reconcile keyed on call id with a rating tolerance, and surface leakage on an RA dashboard with alerting on the daily delta.',
        icon: 'heroicons_outline:banknotes',
        contents: ['3 connections', '2 pipelines', '3 datasets + sample CDRs', '1 reconciliation (with breaks)', '3 widgets · 1 dashboard', 'RA jobs, alerts & incidents'],
        seed: seedTelecomRa,
    },
    {
        id: 'fraud-mgmt',
        name: 'Fraud Management',
        tagline: 'Score usage in near-real-time and investigate high-risk traffic.',
        description:
            'A scoring pipeline over mediation xDRs with velocity + destination rules (SIM-box, IRSF); high-risk events feed a drillable dataset, a fraud dashboard, and open investigation Cases.',
        icon: 'heroicons_outline:shield-exclamation',
        contents: ['2 connections', '1 scoring pipeline', '2 datasets (incl. a real risk filter)', '3 widgets · 1 dashboard', 'Velocity/IRSF alert rules', 'Investigation cases'],
        seed: seedFraudMgmt,
    },
    {
        id: 'financial-audit',
        name: 'Financial Auditing',
        tagline: 'Prove every posting is paid — to the cent.',
        description:
            'GL postings vs. bank payments: load both sides from the ERP and the bank export, reconcile on transaction id with a $0.01 tolerance, and track unmatched items as audit findings.',
        icon: 'heroicons_outline:scale',
        contents: ['2 connections', '2 pipelines', '2 datasets + sample postings', '1 reconciliation (with breaks)', '3 widgets · 1 dashboard', 'Audit findings as incidents'],
        seed: seedFinancialAudit,
    },
    {
        id: 'link-analysis',
        name: 'Link Analysis',
        tagline: 'Connect subscribers, devices and accounts into investigable graphs.',
        description:
            'Entity/link tables built nightly from xDRs with community detection; ring candidates (shared devices, payment paths) surface as Cases. The interactive graph Visualization Type arrives with C5.',
        icon: 'heroicons_outline:share',
        contents: ['1 connection', '1 graph-build pipeline', '2 datasets (entities + links)', '3 widgets · 1 dashboard', 'A seeded ring-candidate case'],
        seed: seedLinkAnalysis,
    },
];
