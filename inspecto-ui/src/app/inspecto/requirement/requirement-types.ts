/**
 * A Business-authored **Requirement** — C1 (Requirements intake), Wave 3. Pure data, no Angular. Stored
 * as a `requirement` component (mock-served today); mirrors `rule-types.ts`/`dataset-types.ts`'s "just a
 * component" shape. Lifecycle per the Wave-3 interview decision (2026-07-03): Business submits
 * (`submitted`); Builder triages into a queue and decides (`accepted`/`rejected`, with an optional note);
 * an accepted requirement is later marked `delivered` once satisfied (a free-text note today — linking to
 * the actual delivering Component(s) is deferred, see `docs/superpower/reviews/requirements-intake.md`).
 */
export type RequirementKind = 'kpi' | 'report' | 'reconciliation' | 'rule';
export type RequirementStatus = 'submitted' | 'accepted' | 'rejected' | 'delivered';

export interface Requirement {
    id: string;
    title: string;
    kind: RequirementKind;
    description: string;
    status: RequirementStatus;
    submittedAt: string;
    /** Builder's accept/reject note (present once decided). */
    decisionNote?: string;
    decidedAt?: string;
    /** Free-text pointer to what satisfied the requirement (present once delivered). */
    deliveredNote?: string;
    deliveredAt?: string;
}

/** Build a freshly submitted {@link Requirement} — the id is a slug + short suffix, not user-authored
 *  (nothing else references a requirement by id, so there's nothing to ask the user to name). */
export function buildRequirement(title: string, kind: RequirementKind, description: string): Requirement {
    const slug = title.trim().toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'requirement';
    const suffix = Math.random().toString(36).slice(2, 6);
    return {
        id: `${slug}_${suffix}`,
        title: title.trim(),
        kind,
        description: description.trim(),
        status: 'submitted',
        submittedAt: new Date().toISOString(),
    };
}

/** Builder's accept/reject decision. */
export function decideRequirement(r: Requirement, accept: boolean, note?: string): Requirement {
    return { ...r, status: accept ? 'accepted' : 'rejected', decisionNote: note?.trim() || undefined, decidedAt: new Date().toISOString() };
}

/** Mark an accepted requirement delivered. */
export function deliverRequirement(r: Requirement, note?: string): Requirement {
    return { ...r, status: 'delivered', deliveredNote: note?.trim() || undefined, deliveredAt: new Date().toISOString() };
}
