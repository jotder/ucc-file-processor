import { Injectable } from '@angular/core';
import { environment } from 'environments/environment';

const TOKEN_KEY = 'x-auth-token';
@Injectable({
    providedIn: 'root',
})
export class AppLocalStorage {


    save(type, token): void {
        window.localStorage[this.getAppVariableName(type)] = token;
    }

    get(type): string {
        return window.localStorage[this.getAppVariableName(type)];
    }

    delete(type): string {
        return window.localStorage.removeItem[this.getAppVariableName(type)];
    }

    destroy(): void {
        window.localStorage.removeItem(TOKEN_KEY);
    }

    getAppVariableName(attributeName: string) {
        return environment.appName + '__' + attributeName;
    }
}
