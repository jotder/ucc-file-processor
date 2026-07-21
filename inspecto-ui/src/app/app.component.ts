import { Component, OnInit, inject } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { Platform } from '@angular/cdk/platform';
import {
    NavigationExtras,
    Router,
    RouterModule,
    UrlTree,
} from '@angular/router';

import { GammaNavigationService } from '@gamma/components/navigation/navigation.service';
import { defaultNavigation } from 'app/core/navigation/navigation-data';
import { environment } from 'environments/environment';

@Component({
    selector: 'app-root',
    templateUrl: './app.component.html',
    styleUrls: ['./app.component.scss'],
    standalone: true,
    imports: [RouterModule],
})
export class AppComponent implements OnInit {
    private readonly document = inject(DOCUMENT);
    private readonly platform = inject(Platform);
    private readonly router = inject(Router);
    private readonly gammaNavigationService = inject(GammaNavigationService);

    constructor() {
        // Seed the classic-shell navigation. The live sidebar is fed by NavigationService (route
        // resolver); this 'basics' registration keeps the Gamma navigation store populated for the shell.
        this.gammaNavigationService.storeNavigation('basics', defaultNavigation);

        // Add is-mobile class if on a mobile platform
        if (this.platform.ANDROID || this.platform.IOS) {
            this.document.body.classList.add('is-mobile');
        }
    }

    ngOnInit(): void {
        this.patchRouterNavigate();
    }

    /**
     * Patch router.navigate to open a new tab on Ctrl/Cmd+click.
     * Uses arrow function to preserve `this` without .bind().
     */
    patchRouterNavigate(): void {
        const originalNavigate = this.router.navigate.bind(this.router);

        this.router.navigate = (
            commands: any[],
            extras?: NavigationExtras,
        ): Promise<boolean> => {
            const tree: UrlTree = this.router.createUrlTree(commands, extras);
            const url = this.router.serializeUrl(tree);
            const basePath = environment.basePath;
            const mainUrl = basePath === '/' ? url : basePath + url;

            if (window.event && (window.event as MouseEvent).ctrlKey) {
                window.open(mainUrl, '_blank');
                return Promise.resolve(false);
            }

            return originalNavigate(commands, extras);
        };
    }
}
