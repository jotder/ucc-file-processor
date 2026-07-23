import { computed, inject, Injectable, signal } from '@angular/core';
import { SessionService } from './session.service';

/** The three persona lenses over the one console (`docs/GLOSSARY.md` §1-A). Not a permission —
 *  under RBAC (Standard, R2) the subject's role grants constrain which lenses are selectable. */
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
 * **Capability seam (RBAC groundwork, 2026-07-03; the promised re-derivation landed with RBAC R2,
 * 2026-07-23 — `docs/superpower/rbac-abac-plan.md` §3):** panes gate on the named **capability**
 * signals below, never on `readOnly`/lens identity directly. A Lens is a *view*, not a permission
 * (GLOSSARY §1-A). On Personal (`authMode 'none'`) every capability derives from the self-selected
 * lens — the honor-system preview, byte-identical to before. Under OIDC each capability additionally
 * requires the matching grant in {@link SessionService.capabilities} — the *effective* set
 * `/bootstrap` reports after the backend's role/Access-Profile enforcement — and the "View as"
 * switcher is constrained to the {@link allowedLenses} those grants project onto. Call sites are
 * unchanged, as designed. Add a new named capability per distinct authorization question; don't
 * reuse one because its current value happens to match.
 */
@Injectable({ providedIn: 'root' })
export class LensService {
    /** The three lenses, in display order — {@link allowedLenses} filters this. */
    static readonly LENSES = LENSES;

    private readonly session = inject(SessionService);

    /** The user's chosen ("View as") lens — persisted. May be constrained away by grants: the
     *  active lens is {@link currentLens}, which snaps back here whenever the preference is allowed
     *  again (e.g. an admin restores a revoked role), so a temporary revocation never overwrites
     *  the stored choice. */
    private readonly preferredLens = signal<Lens>(this.restore());

    /** The lenses this subject may view as. Honor system (Personal): all three. Under OIDC the
     *  subject's roles project onto lenses via the effective grants (rbac-groundwork §3 taxonomy):
     *  Builder needs `canAuthorWorkbench`, Ops needs `canOperateRuns`, Business (read-only) is
     *  always available. The switcher iterates this. */
    readonly allowedLenses = computed<LensMeta[]>(() => {
        if (this.session.authMode() !== 'oidc') return LENSES;
        const caps = this.session.capabilities();
        return LENSES.filter(
            (l) =>
                l.id === 'business' ||
                (l.id === 'builder' && caps.includes('canAuthorWorkbench')) ||
                (l.id === 'ops' && caps.includes('canOperateRuns')),
        );
    });

    /** The active lens: the preferred one when allowed, else the most capable allowed lens. */
    readonly currentLens = computed<Lens>(() => {
        const allowed = this.allowedLenses();
        const preferred = this.preferredLens();
        if (allowed.some((l) => l.id === preferred)) return preferred;
        return allowed[allowed.length - 1]?.id ?? 'business';
    });

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

    /** Under OIDC, is `cap` among the subject's effective capabilities (`/bootstrap`, post
     *  server-side R2 enforcement)? Honor-system mode grants everything — the lens decides. */
    private granted(cap: string): boolean {
        return this.session.authMode() !== 'oidc' || this.session.capabilities().includes(cap);
    }

    /** May author in the Workbench (Pipelines / Jobs / Components create-edit-delete). RBAC: Pipeline
     *  Developer, Power user, Super user. (Connection onboarding split out to {@link canOnboardConnections}
     *  2026-07-22 — the credential/egress surface is Admin-owned, not Builder.) */
    readonly canAuthorWorkbench = computed(
        () => this.granted('canAuthorWorkbench') && !this.readOnly() && this.allows('workbench.author'));

    /** May onboard/configure Connections (create / edit / delete a connection profile) — its own
     *  authorization question because Connections are the credential + network-egress surface, a worse
     *  blast radius than authoring a pipeline (rbac-groundwork §3/§4.1 Q1, product sign-off 2026-07-22).
     *  RBAC: Admin, Super. In the lens honor-system preview it defaults allowed for the non-Business
     *  lenses, exactly as Workbench authoring did before the split. */
    readonly canOnboardConnections = computed(
        () => this.granted('canOnboardConnections') && !this.readOnly() && this.allows('connections.onboard'));

    /** May operate runs (trigger / pause / resume / reprocess) — the plan's "read-only observe"
     *  exception for Business on the Runs pane. RBAC: Operations, Pipeline Developer, Power/Super. */
    readonly canOperateRuns = computed(
        () => this.granted('canOperateRuns') && !this.readOnly() && this.allows('runs.operate'));

    /** May triage Requirements (accept / reject / deliver) — the Builder-facing intake queue (C1).
     *  RBAC: Pipeline Developer, Operations, Power/Super. */
    readonly canTriageRequirements = computed(
        () => this.granted('canTriageRequirements') && !this.readOnly() && this.allows('requirements.triage'));

    /** May author Alert Rules (create / edit / delete on the Alerts pane — audit C3). A distinct
     *  question from Workbench authoring: monitoring config is Ops-owned. RBAC: Operations,
     *  Power/Super. */
    readonly canAuthorAlertRules = computed(
        () => this.granted('canAuthorAlertRules') && !this.readOnly() && this.allows('alerts.author'));

    /** May configure lens access (the Settings ▸ Access matrix). RBAC: Admin, Super. */
    readonly canConfigureAccess = computed(
        () => this.granted('canConfigureAccess') && !this.readOnly() && this.allows('access.configure'));

    /** Set the preferred lens and persist it across reloads. A lens outside {@link allowedLenses}
     *  is remembered but not activated (the switcher never offers one). */
    selectLens(lens: Lens): void {
        this.preferredLens.set(lens);
        if (typeof localStorage === 'undefined') return;
        localStorage.setItem(STORAGE_KEY, lens);
    }

    private restore(): Lens {
        if (typeof localStorage === 'undefined') return DEFAULT_LENS;
        const saved = localStorage.getItem(STORAGE_KEY);
        return saved === 'business' || saved === 'builder' || saved === 'ops' ? saved : DEFAULT_LENS;
    }
}
