import {
    ChangeDetectionStrategy,
    ChangeDetectorRef,
    Component,
    Input,
    OnChanges,
    OnDestroy,
    OnInit,
    SimpleChanges,
    ViewEncapsulation,
    inject,
} from '@angular/core';
import { gammaAnimations } from '@gamma/animations';
import { GammaNavigationService } from '@gamma/components/navigation/navigation.service';
import { GammaNavigationItem } from '@gamma/components/navigation/navigation.types';
import { GammaUtilsService } from '@gamma/services/utils/utils.service';
import { ReplaySubject, Subject } from 'rxjs';
import { GammaHorizontalNavigationBasicItemComponent } from './components/basic/basic.component';
import { GammaHorizontalNavigationBranchItemComponent } from './components/branch/branch.component';
import { GammaHorizontalNavigationSpacerItemComponent } from './components/spacer/spacer.component';

@Component({
    selector: 'gamma-horizontal-navigation',
    templateUrl: './horizontal.component.html',
    styleUrls: ['./horizontal.component.scss'],
    animations: gammaAnimations,
    encapsulation: ViewEncapsulation.None,
    changeDetection: ChangeDetectionStrategy.OnPush,
    exportAs: 'gammaHorizontalNavigation',
    imports: [
        GammaHorizontalNavigationBasicItemComponent,
        GammaHorizontalNavigationBranchItemComponent,
        GammaHorizontalNavigationSpacerItemComponent,
    ],
})
export class GammaHorizontalNavigationComponent
    implements OnChanges, OnInit, OnDestroy
{
    private _changeDetectorRef = inject(ChangeDetectorRef);
    private _gammaNavigationService = inject(GammaNavigationService);
    private _gammaUtilsService = inject(GammaUtilsService);

    @Input() name: string = this._gammaUtilsService.randomId();
    @Input() navigation: GammaNavigationItem[];

    onRefreshed: ReplaySubject<boolean> = new ReplaySubject<boolean>(1);
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
        // Navigation
        if ('navigation' in changes) {
            // Mark for check
            this._changeDetectorRef.markForCheck();
        }
    }

    /**
     * On init
     */
    ngOnInit(): void {
        // Make sure the name input is not an empty string
        if (this.name === '') {
            this.name = this._gammaUtilsService.randomId();
        }

        // Register the navigation component
        this._gammaNavigationService.registerComponent(this.name, this);
    }

    /**
     * On destroy
     */
    ngOnDestroy(): void {
        // Deregister the navigation component from the registry
        this._gammaNavigationService.deregisterComponent(this.name);

        // Unsubscribe from all subscriptions
        this._unsubscribeAll.next(null);
        this._unsubscribeAll.complete();
    }

    // -----------------------------------------------------------------------------------------------------
    // @ Public methods
    // -----------------------------------------------------------------------------------------------------

    /**
     * Refresh the component to apply the changes
     */
    refresh(): void {
        // Mark for check
        this._changeDetectorRef.markForCheck();

        // Execute the observable
        this.onRefreshed.next(true);
    }

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
