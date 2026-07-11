import { normalizeIncidentStatus, OperationalObject } from 'app/inspecto/api';

export { normalizeIncidentStatus };

/**
 * The mail-view model over {@link OperationalObject}: the canonical incident lifecycle
 * (GLOSSARY §9: IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED), the priority ladder, and the
 * mail-metaphor concepts (folders, tags, escalation, postmortem) that ride in the generic
 * `attributes` bag — no backend model change (design: docs/superpower/incidents-mail-ui-design.md).
 */

export const INCIDENT_PRIORITIES = ['CRITICAL', 'MAJOR', 'MINOR', 'LOW'] as const;

/** The (normalized) statuses per object type — folder set and Tag-Rule criteria options. */
export const INCIDENT_STATUSES = ['IDENTIFIED', 'DIAGNOSING', 'RESOLVED', 'ARCHIVED'] as const;
export const CASE_STATUSES = ['OPEN', 'INVESTIGATING', 'ESCALATED', 'RESOLVED', 'CLOSED'] as const;

/** The status shown/foldered for an object — normalized for incidents, as-is otherwise. */
export function displayStatus(o: OperationalObject): string {
    return o.objectType === 'INCIDENT' ? normalizeIncidentStatus(o.status) : (o.status ?? '').toUpperCase();
}

/** Tags ride in `attributes.tags` as a CSV. */
export function objectTags(o: OperationalObject): string[] {
    return (o.attributes?.['tags'] ?? '')
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean);
}

/** The 3-layer category path (`attributes.category`, "L1 / L2 / L3"). */
export function objectCategory(o: OperationalObject): string {
    return o.attributes?.['category'] ?? '';
}

/** Escalation: a flag for incidents (`attributes.escalated`), the ESCALATED *status* for cases. */
export function isEscalated(o: OperationalObject): boolean {
    return o.objectType === 'CASE'
        ? (o.status ?? '').toUpperCase() === 'ESCALATED'
        : o.attributes?.['escalated'] === 'true';
}

/** "Me" for the My Cases folder — the auth-free Personal edition has no session identity. */
export function currentOperator(): string {
    return localStorage.getItem('inspecto.operator') || 'operator';
}

// ── Postmortem (attributes.postmortem, JSON) ──────────────────────────────────────────────────

export interface PostmortemTimelineEntry {
    time: string;
    text: string;
}

export interface PostmortemAction {
    done: boolean;
    text: string;
    owner: string;
    due: string;
}

/** The Incident Postmortem template — Summary · Timeline · 5 Whys · Corrective actions. */
export interface Postmortem {
    commander: string;
    incidentDate: string;
    downtime: string;
    businessImpact: string;
    timeline: PostmortemTimelineEntry[];
    fiveWhys: string[]; // always 5 rows
    actions: PostmortemAction[];
}

export function emptyPostmortem(): Postmortem {
    return {
        commander: '',
        incidentDate: '',
        downtime: '',
        businessImpact: '',
        timeline: [],
        fiveWhys: ['', '', '', '', ''],
        actions: [],
    };
}

/** Parse the stored postmortem; null when absent or unreadable (the form then starts empty). */
export function parsePostmortem(o: OperationalObject): Postmortem | null {
    const raw = o.attributes?.['postmortem'];
    if (!raw) return null;
    try {
        const p = JSON.parse(raw) as Partial<Postmortem>;
        return {
            ...emptyPostmortem(),
            ...p,
            timeline: p.timeline ?? [],
            fiveWhys: [...(p.fiveWhys ?? []), '', '', '', '', ''].slice(0, 5),
            actions: p.actions ?? [],
        };
    } catch {
        return null;
    }
}

// ── Folders (the mail metaphor's left nav) ────────────────────────────────────────────────────

/** A left-nav folder: a named predicate over the loaded rows (`me` = {@link currentOperator}). */
export interface MailFolder {
    id: string;
    label: string;
    icon: string;
    match: (o: OperationalObject, me: string) => boolean;
}

const notArchived = (o: OperationalObject): boolean => !['ARCHIVED', 'CLOSED'].includes(displayStatus(o));

/** Important → My Cases · Starred → Escalated · Inbox → Identified · Draft → Diagnosing · Sent → Resolved · Trash → Archived. */
export const INCIDENT_FOLDERS: MailFolder[] = [
    { id: 'mine', label: 'My Cases', icon: 'heroicons_outline:user', match: (o, me) => o.assignee === me && notArchived(o) },
    { id: 'escalated', label: 'Escalated', icon: 'heroicons_outline:arrow-trending-up', match: (o) => isEscalated(o) && notArchived(o) },
    { id: 'identified', label: 'Identified', icon: 'heroicons_outline:inbox', match: (o) => displayStatus(o) === 'IDENTIFIED' },
    { id: 'diagnosing', label: 'Diagnosing', icon: 'heroicons_outline:document-text', match: (o) => displayStatus(o) === 'DIAGNOSING' },
    { id: 'resolved', label: 'Resolved', icon: 'heroicons_outline:paper-airplane', match: (o) => displayStatus(o) === 'RESOLVED' },
    { id: 'archived', label: 'Archived', icon: 'heroicons_outline:archive-box', match: (o) => displayStatus(o) === 'ARCHIVED' },
];

/** Cases keep their existing lifecycle — same look and feel, per-status folders. */
export const CASE_FOLDERS: MailFolder[] = [
    { id: 'mine', label: 'My Cases', icon: 'heroicons_outline:user', match: (o, me) => o.assignee === me && notArchived(o) },
    { id: 'escalated', label: 'Escalated', icon: 'heroicons_outline:arrow-trending-up', match: (o) => displayStatus(o) === 'ESCALATED' },
    { id: 'open', label: 'Open', icon: 'heroicons_outline:inbox', match: (o) => displayStatus(o) === 'OPEN' },
    { id: 'investigating', label: 'Investigating', icon: 'heroicons_outline:magnifying-glass', match: (o) => displayStatus(o) === 'INVESTIGATING' },
    { id: 'resolved', label: 'Resolved', icon: 'heroicons_outline:paper-airplane', match: (o) => displayStatus(o) === 'RESOLVED' },
    { id: 'closed', label: 'Closed', icon: 'heroicons_outline:archive-box', match: (o) => displayStatus(o) === 'CLOSED' },
];
