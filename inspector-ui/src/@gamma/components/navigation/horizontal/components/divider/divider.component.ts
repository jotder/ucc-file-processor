import { NgClass } from '@angular/common';
import {
    ChangeDetectionStrategy,
    ChangeDetectorRef,
    Component,
    inject,
    Input,
    OnDestroy,
    OnInit,
} from '@angular/core';
import { GammaHorizontalNavigationComponent } from '@gamma/components/navigation/horizontal/horizontal.component';
import { GammaNavigationService } from '@gamma/components/navigation/navigation.service';
import { GammaNavigationItem } from '@gamma/components/navigation/navigation.types';
import { Subject, takeUntil } from 'rxjs';

@Component({
    selector: 'gamma-horizontal-navigation-divider-item',
    templateUrl: './divider.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [NgClass],
})
export class GammaHorizontalNavigationDividerItemComponent
    implements OnInit, OnDestroy
{
    private _changeDetectorRef = inject(ChangeDetectorRef);
    private _gammaNavigationService = inject(GammaNavigationService);

    @Input() item: GammaNavigationItem;
    @Input() name: string;

    private _gammaHorizontalNavigationComponent: GammaHorizontalNavigationComponent;
    private _unsubscribeAll: Subject<any> = new Subject<any>();

    // -----------------------------------------------------------------------------------------------------
    // @ Lifecycle hooks
    // -----------------------------------------------------------------------------------------------------

    /**
     * On init
     */
    ngOnInit(): void {
        // Get the parent navigation component
        this._gammaHorizontalNavigationComponent =
            this._gammaNavigationService.getComponent(this.name);

        // Subscribe to onRefreshed on the navigation component
        this._gammaHorizontalNavigationComponent.onRefreshed
            .pipe(takeUntil(this._unsubscribeAll))
            .subscribe(() => {
                // Mark for check
                this._changeDetectorRef.markForCheck();
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
