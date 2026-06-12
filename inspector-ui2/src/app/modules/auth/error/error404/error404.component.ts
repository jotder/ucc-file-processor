import { Component } from '@angular/core';
import { GammaConfigService } from '@gamma/services/config';


@Component({
    selector: 'error404',
    templateUrl: './error404.component.html',
    styleUrls: ['./error404.component.scss']
})
export class Error404Component {
    /**
     * Constructor
     *
     * @param {GammaConfigService} _gammaConfigService
     */
    constructor(
        private _gammaConfigService: GammaConfigService
    ) {
        // Configure the layout
        this._gammaConfigService.config = {
            layout: {
                navbar: {
                    hidden: true
                },
                toolbar: {
                    hidden: true
                },
                footer: {
                    hidden: true
                },
                sidepanel: {
                    hidden: true
                }
            }
        };
    }
}
