import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, tap, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { ConnectivityService } from './connectivity.service';
import { TokenStore } from './token-store.service';

/**
 * Global API error handling + connectivity tracking.
 *
 * - A 401 means the token is missing/invalid → clear it, toast, and bounce to Connect.
 * - A `status === 0` means the backend is unreachable → mark connectivity degraded; the persistent
 *   `<inspecto-connectivity-banner>` (driven by {@link ConnectivityService}) shows the message and a
 *   Retry button, so we no longer emit a per-request "unreachable" toast here.
 * - Any successful response clears the degraded state.
 *
 * Errors are re-thrown so per-screen handlers can still react (e.g. assist 503).
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const toastr = inject(ToastrService);
  const tokens = inject(TokenStore);
  const router = inject(Router);
  const connectivity = inject(ConnectivityService);
  return next(req).pipe(
    tap((event) => {
      if (event instanceof HttpResponse) connectivity.reportReachable();
    }),
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401) {
        tokens.clear();
        toastr.error('Not authorized — please reconnect with a valid token.');
        router.navigate(['/connect']);
      } else if (err.status === 0) {
        connectivity.reportUnreachable();
      }
      return throwError(() => err);
    })
  );
};
