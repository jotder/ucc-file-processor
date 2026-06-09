import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import notify from 'devextreme/ui/notify';
import { TokenStore } from './token-store.service';

/**
 * Global API error handling. A 401 means the token is missing/invalid → clear it and bounce to the
 * Connect screen. Errors are re-thrown so per-screen handlers can still react (e.g. assist 503).
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const tokens = inject(TokenStore);
  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401) {
        tokens.clear();
        notify('Not authorized — please reconnect with a valid token.', 'error', 3000);
        router.navigate(['/connect']);
      }
      return throwError(() => err);
    })
  );
};
