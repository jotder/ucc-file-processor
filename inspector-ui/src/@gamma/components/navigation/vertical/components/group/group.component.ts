import { BooleanInput } from '@angular/cdk/coercion';
import { NgClass } from '@angular/common';
import {
    ChangeDetectionStrategy,
    ChangeDetectorRef,
    Component,
    Input,
    OnDestroy,
    OnInit,
    forwardRef,
    inject,
} from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { GammaNavigationService } from '@gamma/components/navigation/navigation.service';
import { GammaNavigationItem } from '@gamma/components/navigation/navigation.types';
import { GammaVerticalNavigationBasicItemComponent } from '@gamma/components/navigation/vertical/components/basic/basic.component';
import { GammaVerticalNavigationCollapsableItemComponent } from '@gamma/components/navigation/vertical/components/collapsable/collapsable.component';
import { GammaVerticalNavigationDividerItemComponent } from '@gamma/components/navigation/vertical/components/divider/divider.component';
import { GammaVerticalNavigationSpacerItemComponent } from '@gamma/components/navigation/vertical/components/spacer/spacer.component';
import { GammaVerticalNavigationComponent } from '@gamma/components/navigation/vertical/vertical.component';
import { Subject, takeUntil } from 'rxjs';

@Component({
    selector: 'gamma-vertical-navigation-group-item',
    templateUrl: './group.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        NgClass,
        MatIconModule,
        GammaVerticalNavigationBasicItemComponent,
        GammaVerticalNavigationCollapsableItemComponent,
        GammaVerticalNavigationDividerItemComponent,
        forwardRef(() => GammaVerticalNavigationGroupItemComponent),
        GammaVerticalNavigationSpacerItemComponent,
    ],
})
export class GammaVerticalNavigationGroupItemComponent
    implements OnInit, OnDestroy
{
    /* eslint-disable @typescript-eslint/naming-convention */
    static ngAcceptInputType_autoCollapse: BooleanInput;
    /* eslint-enable @typescript-eslint/naming-convention */

    private _changeDetectorRef = inject(ChangeDetectorRef);
    private _gammaNavigationService = inject(GammaNavigationService);

    @Input() autoCollapse: boolean;
    @Input() item: GammaNavigationItem;
    @Input() name: string;

    private _gammaVerticalNavigationComponent: GammaVerticalNavigationComponent;
    private _unsubscribeAll: Subject<any> = new Subject<any>();

    // -----------------------------------------------------------------------------------------------------
    // @ Lifecycle hooks
    // -----------------------------------------------------------------------------------------------------

    /**
     * On init
     */
    ngOnInit(): void {
        // Get the parent navigation component
        this._gammaVerticalNavigationComponent =
            this._gammaNavigationService.getComponent(this.name);

        // Subscribe to onRefreshed on the navigation component
        this._gammaVerticalNavigationComponent.onRefreshed
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

    // -----------------------------------------------------------------------------------------------------
    // @ Public methods
    // -----------------------------------------------------------------------------------------------------

    /**
     * Track by function for ngFor loops
     *
     * @param index
     * @param item
     */
    trackByFn(index: number, item: any): any {
        return item.id || index;
    }
}
