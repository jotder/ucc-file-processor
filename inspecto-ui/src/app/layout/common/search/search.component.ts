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
interface SearchDestination {
    title: string;
    link: string;
    icon?: string;
    /** The owning nav group (for context in the result row). */
    group?: string;
}

/**
 * Header search — a client-side "jump to" palette over the inspecto navigation. Typing filters the
 * known panes (Dashboard, Pipelines, Events, …) and selecting one routes to it; there is no backend
 * search call (the former Fuse demo searched contacts/tasks, which don't exist here).
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

    private router = inject(Router);

    opened: boolean = false;
    /** Filtered matches; `null` = no query yet (panel stays closed). */
    results: SearchDestination[] | null = null;
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
                    this.results = null;
                    return;
                }
                this.results = this.destinations
                    .filter((d) => d.title.toLowerCase().includes(q) || !!d.group?.toLowerCase().includes(q))
                    .slice(0, 10);
            });
    }

    ngOnDestroy(): void {
        this._unsubscribeAll.next();
        this._unsubscribeAll.complete();
    }

    /** Navigate to the chosen destination and reset the search. */
    goTo(dest: SearchDestination): void {
        if (dest?.link) {
            this.router.navigateByUrl(dest.link);
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

    /** Open the bar search. */
    open(): void {
        this.opened = true;
    }

    /** Clear + close the search. */
    close(): void {
        this.searchControl.setValue('');
        this.results = null;
        this.opened = false;
    }

    trackByFn(index: number, item: SearchDestination): string | number {
        return item?.link || index;
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
