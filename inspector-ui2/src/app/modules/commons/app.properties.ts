

import { Injectable } from '@angular/core';
import { environment } from 'environments/environment';

@Injectable({
    providedIn: 'root',
})
export class AppProperties {
    readonly appName: string = environment.appName;
    readonly appClientId: string = environment.appClientId;
    readonly appClientSecret: string = environment.appClientSecret;
    readonly appScope: string = 'project.owner';
    readonly appBaseContext: string = window.location.origin + environment.basePath;
    readonly appRedirectUri: string = this.appBaseContext.endsWith('/')
        ? `${this.appBaseContext}redirect/oauth/pronto`: `${this.appBaseContext}/redirect/oauth/pronto`;
}