import { throwError, Observable } from 'rxjs';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
@Injectable({
    providedIn: 'root',
})
export class AppHttpService {

    public headers = new HttpHeaders().set('Content-Type', 'application/json');
    public options = { headers: this.headers, withCredentials: true };

    constructor(public http: HttpClient) {
    }

    handleError(error: any): Observable<Response> {
        return throwError(error);
    }

}
