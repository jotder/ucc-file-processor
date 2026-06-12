import { Injectable, signal, computed } from '@angular/core';

@Injectable({
    providedIn: 'root',
})
export class LoaderService {

    // Angular 21: Signals replace BehaviorSubject for local reactive state
    private readonly _status = signal<boolean>(false);

    /** Public read-only signal — consumers react without subscribing */
    readonly status = this._status.asReadonly();

    /** Derived signal — useful for template bindings or dependent computations */
    readonly isLoading = computed(() => this._status());

    display(value: boolean): void {
        this._status.set(value);
    }
}