import { Overlay } from '@angular/cdk/overlay';
import { NgTemplateOutlet } from '@angular/common';
import {
    Component,
    ElementRef,
    HostBinding,
    Input,
    OnChanges,
    OnDestroy,
    OnInit,
    Renderer2,
    SimpleChanges,
    ViewChild,
    ViewEncapsulation,
    inject,
} from '@angular/core';
import { FormsModule, ReactiveFormsModule, UntypedFormControl } from '@angular/forms';
import {
    MAT_AUTOCOMPLETE_SCROLL_STRATEGY,
    MatAutocomplete,
    MatAutocompleteModule,
} from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatOptionModule } from '@angular/material/core';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { Router } from '@angular/router';
import { gammaAnimations } from '@gamma/animations/public-api';
import { GammaNavigationItem } from '@gamma/components/navigation';
import { defaultNavigation } from 'app/mock-api/common/navigation/data';
import { Subject, debounceTime, takeUntil } from 'rxjs';

/** A navigable destination derived from the app navigation. */
export interface SearchDestination {
    title: string;
    link: string;
    icon?: string;
    /** The owning nav group (for context in the result row). */
    group?: string;
}

/** An action command surfaced in the palette (supplied by the shell — e.g. switch lens). */
export interface SearchCommand {
    title: string;
    icon?: string;
    group?: string;
    /** Executed when the row is chosen. */
    run: () => void;
}

/** A palette row is either a jump-to-page destination or an action command. */
export type PaletteItem = SearchDestination | SearchCommand;

const isCommand = (item: PaletteItem): item is SearchCommand => 'run' in item;
const RECENTS_KEY = 'inspecto.search.recents';
const RECENTS_MAX = 5;

/**
 * Header search — a client-side "jump to" palette over the inspecto navigation. Typing filters the
 * known panes (Dashboard, Pipelines, Events, …) and selecting one routes to it; there is no backend
 * search call (the former Fuse demo searched contacts/tasks, which don't exist here).
 *
 * Opened by click or, app-wide, by **Ctrl/Cmd+K** (the shell focuses it). With an empty query it
 * shows recent pages + the shell-supplied action {@link commands}; typing filters both
 * (`docs/superpower/ui-design-review.md` R3).
 */
@Component({
    selector: 'search',
    templateUrl: './search.component.html',
    encapsulation: ViewEncapsulation.None,
    exportAs: 'gammaSearch',
    animations: gammaAnimations,
    imports: [
        MatButtonModule,
        MatIconModule,
        FormsModule,
        MatAutocompleteModule,
        ReactiveFormsModule,
        MatOptionModule,
        NgTemplateOutlet,
        MatFormFieldModule,
        MatInputModule,
    ],
    providers: [
        {
            provide: MAT_AUTOCOMPLETE_SCROLL_STRATEGY,
            useFactory: () => {
                const overlay = inject(Overlay);
                return () => overlay.scrollStrategies.block();
            },
        },
    ],
})
export class SearchComponent implements OnChanges, OnInit, OnDestroy {
    @Input() appearance: 'basic' | 'bar' = 'basic';
    @Input() debounce: number = 200;
    @Input() minLength: number = 1;
    /** Action commands surfaced in the palette (supplied by the shell — e.g. lens switching). */
    @Input() commands: SearchCommand[] = [];

    private router = inject(Router);

    opened: boolean = false;
    /** Rows shown in the panel; `null` = nothing to show (panel closed). With an empty query this
     *  holds recents + commands, so an opened palette is useful before the user types. */
    results: PaletteItem[] | null = null;
    searchControl: UntypedFormControl = new UntypedFormControl();

    private readonly destinations: SearchDestination[] = this.buildDestinations();
    private _matAutocomplete: MatAutocomplete;
    private _unsubscribeAll: Subject<void> = new Subject<void>();

    constructor(
        private _elementRef: ElementRef,
        private _renderer2: Renderer2,
    ) {}

    @HostBinding('class') get classList(): Record<string, boolean> {
        return {
            'search-appearance-bar': this.appearance === 'bar',
            'search-appearance-basic': this.appearance === 'basic',
            'search-opened': this.opened,
        };
    }

    @ViewChild('barSearchInput')
    set barSearchInput(value: ElementRef) {
        if (value) {
            setTimeout(() => value.nativeElement.focus());
        }
    }

    @ViewChild('matAutocomplete')
    set matAutocomplete(value: MatAutocomplete) {
        this._matAutocomplete = value;
    }

    ngOnChanges(changes: SimpleChanges): void {
        if ('appearance' in changes) {
            this.close();
        }
    }

    ngOnInit(): void {
        this.searchControl.valueChanges
            .pipe(debounceTime(this.debounce), takeUntil(this._unsubscribeAll))
            .subscribe((value) => {
                const q = (typeof value === 'string' ? value : '').trim().toLowerCase();
                if (!q || q.length < this.minLength) {
                    // Empty query while open ⇒ show recents + commands, not a bare panel.
                    this.results = this.opened ? this.suggestions() : null;
                    return;
                }
                const matches = (title: string, group?: string) =>
                    title.toLowerCase().includes(q) || !!group?.toLowerCase().includes(q);
                this.results = [
                    ...this.destinations.filter((d) => matches(d.title, d.group)),
                    ...this.commands.filter((c) => matches(c.title, c.group)),
                ].slice(0, 10);
            });
    }

    /** Recents (most-recent-first) followed by the shell's action commands — shown on an empty query. */
    private suggestions(): PaletteItem[] {
        return [...this.recentDestinations(), ...this.commands];
    }

    /** Resolve the persisted recent links back to live destinations (stale links dropped). */
    private recentDestinations(): SearchDestination[] {
        let links: string[] = [];
        try {
            links = JSON.parse(localStorage.getItem(RECENTS_KEY) ?? '[]');
        } catch {
            links = [];
        }
        return links
            .map((l) => this.destinations.find((d) => d.link === l))
            .filter((d): d is SearchDestination => !!d);
    }

    /** Record a chosen destination as a recent (dedup, most-recent-first, capped). */
    private recordRecent(link: string): void {
        try {
            const links: string[] = JSON.parse(localStorage.getItem(RECENTS_KEY) ?? '[]');
            const next = [link, ...links.filter((l) => l !== link)].slice(0, RECENTS_MAX);
            localStorage.setItem(RECENTS_KEY, JSON.stringify(next));
        } catch {
            // recents are a convenience — storage failures are non-fatal
        }
    }

    ngOnDestroy(): void {
        this._unsubscribeAll.next();
        this._unsubscribeAll.complete();
    }

    /** Run the chosen command, or navigate to the chosen destination, then reset the search. */
    goTo(item: PaletteItem): void {
        if (!item) return;
        if (isCommand(item)) {
            item.run();
            this.close();
            return;
        }
        if (item.link) {
            this.recordRecent(item.link);
            this.router.navigateByUrl(item.link);
            this.close();
        }
    }

    onKeydown(event: KeyboardEvent): void {
        if (event.code === 'Escape') {
            if (this.appearance === 'bar' && !this._matAutocomplete.isOpen) {
                this.close();
            }
        }
    }

    /** Open the bar search and seed the panel with recents + commands. */
    open(): void {
        this.opened = true;
        this.results = this.suggestions();
    }

    /** Clear + close the search. */
    close(): void {
        this.searchControl.setValue('');
        this.results = null;
        this.opened = false;
    }

    trackByFn(index: number, item: PaletteItem): string | number {
        return (isCommand(item) ? item.title : item.link) || index;
    }

    /** Flatten the app navigation (groups → leaves) into jump destinations. */
    private buildDestinations(): SearchDestination[] {
        const out: SearchDestination[] = [];
        const walk = (items: GammaNavigationItem[], group?: string): void => {
            for (const it of items ?? []) {
                if (it.children?.length) {
                    walk(it.children, it.title);
                } else if (it.link) {
                    out.push({ title: it.title ?? it.link, link: it.link, icon: it.icon, group });
                }
            }
        };
        walk(defaultNavigation);
        return out;
    }
}
