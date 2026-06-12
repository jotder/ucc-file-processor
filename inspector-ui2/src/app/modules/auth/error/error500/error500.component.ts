import { Component } from '@angular/core';
import { GammaConfigService } from '@gamma/services/config';


@Component({
  selector: 'error-500',
  templateUrl: './error500.component.html',
  styleUrls: ['./error500.component.scss']
})
export class Error500Component {
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
