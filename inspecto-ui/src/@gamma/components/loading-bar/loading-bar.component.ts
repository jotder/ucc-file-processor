import { coerceBooleanProperty } from '@angular/cdk/coercion';

import {
    Component,
    inject,
    Input,
    OnChanges,
    OnDestroy,
    OnInit,
    SimpleChanges,
    ViewEncapsulation,
} from '@angular/core';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { GammaLoadingService } from '@gamma/services/loading';
import { Subject, takeUntil } from 'rxjs';

@Component({
    selector: 'gamma-loading-bar',
    templateUrl: './loading-bar.component.html',
    styleUrls: ['./loading-bar.component.scss'],
    encapsulation: ViewEncapsulation.None,
    exportAs: 'gammaLoadingBar',
    imports: [MatProgressBarModule],
})
export class GammaLoadingBarComponent implements OnChanges, OnInit, OnDestroy {
    private _gammaLoadingService = inject(GammaLoadingService);

    @Input() autoMode: boolean = true;
    mode: 'determinate' | 'indeterminate';
    progress: number = 0;
    show: boolean = false;
    private _unsubscribeAll: Subject<any> = new Subject<any>();

    // -----------------------------------------------------------------------------------------------------
    // @ Lifecycle hooks
    // -----------------------------------------------------------------------------------------------------

    /**
     * On changes
     *
     * @param changes
     */
    ngOnChanges(changes: SimpleChanges): void {
        // Auto mode
        if ('autoMode' in changes) {
            // Set the auto mode in the service
            this._gammaLoadingService.setAutoMode(
                coerceBooleanProperty(changes.autoMode.currentValue)
            );
        }
    }

    /**
     * On init
     */
    ngOnInit(): void {
        // Subscribe to the service
        //
        // Assignments are deferred to a microtask so a value pushed
        // synchronously during this initial subscribe (e.g. progress
        // jumping from its initial state to 0) lands after the current
        // change-detection tick instead of mutating the bound field
        // mid-tick, which trips dev-mode's checkNoChanges (NG0100).
        this._gammaLoadingService.mode$
            .pipe(takeUntil(this._unsubscribeAll))
            .subscribe((value) => {
                Promise.resolve().then(() => {
                    this.mode = value;
                });
            });

        this._gammaLoadingService.progress$
            .pipe(takeUntil(this._unsubscribeAll))
            .subscribe((value) => {
                Promise.resolve().then(() => {
                    this.progress = value;
                });
            });

        this._gammaLoadingService.show$
            .pipe(takeUntil(this._unsubscribeAll))
            .subscribe((value) => {
                Promise.resolve().then(() => {
                    this.show = value;
                });
            });
    }

    /**
     * On destroy
     */
    ngOnDestroy(): void {
        // Unsubscribe from all subscriptions
        this._unsubscribeAll.next(null);
        this._unsubscribeAll.complete();
    }
}
