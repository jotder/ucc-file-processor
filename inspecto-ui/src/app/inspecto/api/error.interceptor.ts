import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, tap, throwError } from 'rxjs';
import { ConnectivityService } from './connectivity.service';

/**
 * Global API error handling + connectivity tracking.
 *
 * - A `status === 0` means the backend is unreachable → mark connectivity degraded; the persistent
 *   `<inspecto-connectivity-banner>` (driven by {@link ConnectivityService}) shows the message and a
 *   Retry button, so we no longer emit a per-request "unreachable" toast here.
 * - Any successful response clears the degraded state.
 *
 * The backend ControlApi is fully open (no auth) — there is no 401 handling: there is no token to
 * clear and no Connect screen to bounce to. Errors are re-thrown so per-screen handlers can still
 * react (e.g. assist 503).
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const connectivity = inject(ConnectivityService);
  return next(req).pipe(
    tap((event) => {
      if (event instanceof HttpResponse) connectivity.reportReachable();
    }),
    catchError((err: HttpErrorResponse) => {
      if (err.status === 0) {
        connectivity.reportUnreachable();
      }
      return throwError(() => err);
    })
  );
};
