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
 * removed from navigation. {@link readOnly} is the single source of truth consumers gate on.
 */
@Injectable({ providedIn: 'root' })
export class LensService {
    /** The three lenses, in display order — the switcher iterates this. */
    static readonly LENSES = LENSES;

    readonly currentLens = signal<Lens>(this.restore());

    /** True while authoring (create/edit/delete) actions should be hidden. Business-only today. */
    readonly readOnly = computed(() => this.currentLens() === 'business');

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
