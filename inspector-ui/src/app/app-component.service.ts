import { Injectable } from '@angular/core';
import { Observable, map, catchError } from 'rxjs';
import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';

import { SecurityPrincipal } from './modules/commons/security-principal';
import { AppProperties } from './modules/commons/app.properties';
import { AppHttpService } from './modules/commons/app.http.service';
import { environment } from 'environments/environment';

@Injectable({
    providedIn: 'root'
})
export class AppComponentService extends AppHttpService {

    constructor(public httpClient: HttpClient, private securityPrincipal: SecurityPrincipal,
        private _props: AppProperties) {
        super(httpClient);
    }

    renewAccessToken(): Observable<HttpResponse<any>> {

        const body = new HttpParams()
            .set('grant_type', 'refresh_token')
            .set('refresh_token', this.securityPrincipal.getRefreshToken())
            .set('client_id', '1070682796450139008')
            .set('client_secret', 'f6f69f63-95b3-4aa3-93c8-1539666e65c1');
        return this.http
            .post<any>(environment.appUrl + '/oauth/token', body)
    }

    saveRouterActionEvent(eventPayload: any): Observable<any> {
        const apiUrl = environment.appUrl + environment.apiVersion + '/act-logging/appPageChangeEvent';
        return this.http
            .post(apiUrl, JSON.stringify(eventPayload), this.options).pipe(
                map((response: Response) => {
                    return response;
                }),
                catchError(this.handleError));
    }

    getAppPages(): Observable<any> {
        const apiUrl = environment.appUrl + environment.apiVersion + '/app-setting/getAppPages';
        return this.http.get<any>(apiUrl, this.options)
            .pipe(
                map((response: Response) => {
                    return response;
                }),
                catchError(this.handleError));
    }

    saveNewAppPage(pageMeta: any): Observable<any> {
        const apiUrl = environment.appUrl + environment.apiVersion + '/app-setting/newAppPage';
        return this.http.post(apiUrl, JSON.stringify(pageMeta), this.options)
            .pipe(
                map((response: Response) => {
                    return response;
                }),
                catchError(this.handleError));
    }

    retrieveToken(code: string): any {
        const body = new FormData();
        body.append('grant_type', 'authorization_code');
        body.append('client_id', this._props.appClientId);
        body.append('client_secret', this._props.appClientSecret);
        body.append('redirect_uri', this._props.appRedirectUri);
        body.append('code', code);

        const headers = new HttpHeaders({
            'Authorization': 'Basic ' + window.btoa(this._props.appClientId + ":" + this._props.appClientSecret)
        });

        const apiUrl = environment.authServerUrl + environment.authVersion + environment.authVersion + '/token';
        return this.http.post(apiUrl, body, { headers: headers }).pipe(
            map((response: Response) => {
                return response;
            }),
            catchError(this.handleError));

        //     .pipe(
        //         map((response: Response) => {
        //             return response;
        //         })
        //     ).pipe(catchError(this.handleError))

    }

    checkToken(token: string): Observable<any> {
        const body = new FormData();
        body.append('token', token);

        const headers = new HttpHeaders({
            'Authorization': 'Basic ' + window.btoa(this._props.appClientId + ":" + this._props.appClientSecret)
        });

        const apiUrl = environment.authServerUrl +  environment.authVersion + '/check_token';
        return this.http.post(apiUrl, body)
            .pipe(
                map((response: Response) => {
                    return response;
                }),
                catchError(this.handleError));
    }

    getUserDetails(): Observable<any> {
        const apiUrl = environment.gatewayServerUrl + environment.apiVersion + '/um/getUserDetails';
        // return this.http.get(apiUrl, this.options)

        return this.http.get(apiUrl, this.options).pipe(
            map((response: Response) => {
                return response;
            }),
            catchError(this.handleError));

    }
}

// 'http://68.183.16.242:6601/api/v1/um/getUserDetails'