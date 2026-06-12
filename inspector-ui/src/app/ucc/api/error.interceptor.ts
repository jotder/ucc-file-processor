import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { TokenStore } from './token-store.service';

/**
 * Global API error handling. A 401 means the token is missing/invalid → clear it, surface a
 * toast and bounce to the Connect screen. Errors are re-thrown so per-screen handlers can
 * still react (e.g. assist 503).
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const toastr = inject(ToastrService);
  const tokens = inject(TokenStore);
  const router = inject(Router);
  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401) {
        tokens.clear();
        toastr.error('Not authorized — please reconnect with a valid token.');
        router.navigate(['/connect']);
      }
      return throwError(() => err);
    })
  );
};
