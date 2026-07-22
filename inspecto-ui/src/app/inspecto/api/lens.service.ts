import { computed, Injectable, signal } from '@angular/core';

/** The three persona lenses over the one console (`docs/GLOSSARY.md` §1-A). Not a permission — RBAC
 *  arrives with the security module and maps onto lenses. */
export type Lens = 'business' | 'builder' | 'ops';

export interface LensMeta {
    id: Lens;
    label: string;
    icon: string;
}

const STORAGE_KEY = 'inspecto.currentLens';
const DEFAULT_LENS: Lens = 'builder';

const LENSES: LensMeta[] = [
    { id: 'business', label: 'Business', icon: 'heroicons_outline:briefcase' },
    { id: 'builder', label: 'Builder', icon: 'heroicons_outline:wrench-screwdriver' },
    { id: 'ops', label: 'Ops', icon: 'heroicons_outline:server-stack' },
];

/**
 * Holds the active persona lens as a signal, mirroring {@link SpacesService}'s restore/select shape:
 * restored from `localStorage` synchronously in the constructor, persisted on every change.
 *
 * Per the Wave-1 product-owner decision (`docs/superpower/frontend-review-and-completion-plan.md`
 * §6 Q1, recorded 2026-07-02): the **Business** lens can see every Workbench pane, but every
 * create/edit/delete (authoring) action is hidden — panes stay visible and read-only, they are not
 * removed from navigation.
 *
 * **Capability seam (RBAC groundwork, 2026-07-03 — `docs/superpower/rbac-groundwork.md`):** panes gate
 * on the named **capability** signals below, never on `readOnly`/lens identity directly. A Lens is a
 * *view*, not a permission (GLOSSARY §1-A); today every capability is derived from the self-selected
 * lens (an honor-system preview), and when the security module lands these same signals are re-derived
 * from the authenticated subject's **role grants** — call sites don't change. Add a new named capability
 * per distinct authorization question; don't reuse one because its current value happens to match.
 */
@Injectable({ providedIn: 'root' })
export class LensService {
    /** The three lenses, in display order — the switcher iterates this. */
    static readonly LENSES = LENSES;

    readonly currentLens = signal<Lens>(this.restore());

    /** True while the active lens is the read-only one (Business). Internal derivation for the
     *  capabilities below — gate on a capability, not on this. */
    readonly readOnly = computed(() => this.currentLens() === 'business');

    /** Action-node grants pushed by {@code AccessStateService} once the saved lens Access Profiles
     *  load (`docs/superpower/lens-access-config-design.md` §7 — this is the "one file re-derives
     *  these signals" seam from rbac-groundwork, exercised with lens subjects). `null` = no config
     *  loaded ⇒ every action allowed, exactly the pre-profile behavior. */
    private readonly actionGrants =
        signal<Record<string, Partial<Record<Lens, boolean>>> | null>(null);

    /** Called by {@code AccessStateService} whenever lens Access Profiles (re)load. */
    setActionGrants(grants: Record<string, Partial<Record<Lens, boolean>>> | null): void {
        this.actionGrants.set(grants);
    }

    private allows(actionNodeId: string): boolean {
        return this.actionGrants()?.[actionNodeId]?.[this.currentLens()] ?? true;
    }

    /** May author in the Workbench (Pipelines / Jobs / Components create-edit-delete). RBAC: Pipeline
     *  Developer, Power user, Super user. (Connection onboarding split out to {@link canOnboardConnections}
     *  2026-07-22 — the credential/egress surface is Admin-owned, not Builder.) */
    readonly canAuthorWorkbench = computed(() => !this.readOnly() && this.allows('workbench.author'));

    /** May onboard/configure Connections (create / edit / delete a connection profile) — its own
     *  authorization question because Connections are the credential + network-egress surface, a worse
     *  blast radius than authoring a pipeline (rbac-groundwork §3/§4.1 Q1, product sign-off 2026-07-22).
     *  RBAC: Admin, Super. In the lens honor-system preview it defaults allowed for the non-Business
     *  lenses, exactly as Workbench authoring did before the split. */
    readonly canOnboardConnections = computed(() => !this.readOnly() && this.allows('connections.onboard'));

    /** May operate runs (trigger / pause / resume / reprocess) — the plan's "read-only observe"
     *  exception for Business on the Runs pane. RBAC: Operations, Pipeline Developer, Power/Super. */
    readonly canOperateRuns = computed(() => !this.readOnly() && this.allows('runs.operate'));

    /** May triage Requirements (accept / reject / deliver) — the Builder-facing intake queue (C1).
     *  RBAC: Pipeline Developer, Operations, Power/Super. */
    readonly canTriageRequirements = computed(() => !this.readOnly() && this.allows('requirements.triage'));

    /** May author Alert Rules (create / edit / delete on the Alerts pane — audit C3). A distinct
     *  question from Workbench authoring: monitoring config is Ops-owned. RBAC: Operations,
     *  Power/Super. */
    readonly canAuthorAlertRules = computed(() => !this.readOnly() && this.allows('alerts.author'));

    /** May configure lens access (the Settings ▸ Access matrix). RBAC: Admin, Super. */
    readonly canConfigureAccess = computed(() => !this.readOnly() && this.allows('access.configure'));

    /** Set the active lens and persist it across reloads. */
    selectLens(lens: Lens): void {
        this.currentLens.set(lens);
        if (typeof localStorage === 'undefined') return;
        localStorage.setItem(STORAGE_KEY, lens);
    }

    private restore(): Lens {
        if (typeof localStorage === 'undefined') return DEFAULT_LENS;
        const saved = localStorage.getItem(STORAGE_KEY);
        return saved === 'business' || saved === 'builder' || saved === 'ops' ? saved : DEFAULT_LENS;
    }
}
